/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.nbfm;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.string.SimpleStringIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.AnalogDecoderState;
import io.github.dsheirer.module.decode.config.ChannelToneFilter;
import io.github.dsheirer.module.decode.ctcss.CTCSSCode;
import io.github.dsheirer.module.decode.dcs.DCSCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NBFM decoder state - tracks channel tone filtering configuration and detected tones
 */
public class NBFMDecoderState extends AnalogDecoderState
{
    private String mChannelName;
    private Identifier mChannelNameIdentifier;
    private Identifier mTalkgroupIdentifier;

    // Tone filter configuration (from DecodeConfigNBFM)
    private boolean mToneFilterEnabled = false;
    private List<ChannelToneFilter> mConfiguredFilters = new ArrayList<>();

    // De-emphasis configuration
    private DecodeConfigNBFM.DeemphasisMode mDeemphasisMode = DecodeConfigNBFM.DeemphasisMode.NONE;

    // Current status
    private volatile String mToneStatus = "No tone detected";

    // Per-tone detection counts: key = display string, value = [acceptCount, rejectCount]
    private final Map<String, int[]> mToneCounts = new LinkedHashMap<>();

    /**
     * Constructs an instance
     * @param channelName to use for this channel
     * @param decodeConfig with talkgroup identifier and tone filter settings
     */
    public NBFMDecoderState(String channelName, DecodeConfigNBFM decodeConfig)
    {
        if(decodeConfig == null)
        {
            throw new IllegalArgumentException("DecodeConfigNBFM must not be null");
        }
        mChannelName = (channelName != null && !channelName.isEmpty()) ? channelName : "NBFM CHANNEL";
        mChannelNameIdentifier = new SimpleStringIdentifier(mChannelName, IdentifierClass.CONFIGURATION, Form.CHANNEL_NAME, Role.ANY);
        mTalkgroupIdentifier = new NBFMTalkgroup(decodeConfig.getTalkgroup());

        mToneFilterEnabled = decodeConfig.isToneFilterEnabled();
        if(mToneFilterEnabled && decodeConfig.getToneFilters() != null)
        {
            mConfiguredFilters.addAll(decodeConfig.getToneFilters());
        }

        mDeemphasisMode = decodeConfig.getDeemphasis();
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    @Override
    protected Identifier getChannelNameIdentifier()
    {
        return mChannelNameIdentifier;
    }

    @Override
    protected Identifier getTalkgroupIdentifier()
    {
        return mTalkgroupIdentifier;
    }

    // Last code written to mToneStatus — used to skip redundant String allocations
    // when the same tone fires continuously (~43x/sec while squelch is open).
    private CTCSSCode mLastToneStatusCTCSS = null;
    private DCSCode mLastToneStatusDCS = null;
    private boolean mLastToneStatusWasAllowed = false;

    /**
     * Called by NBFMDecoder when a matching CTCSS tone is detected (allowed)
     */
    public void setDetectedCTCSS(CTCSSCode code)
    {
        if(code != null)
        {
            // Only allocate a new status string when the tone or allowed-state changes.
            // The detector fires every buffer (~43x/sec); skipping redundant writes
            // avoids ~43 String allocations per second while the tone is stable.
            if(code != mLastToneStatusCTCSS || !mLastToneStatusWasAllowed)
            {
                mToneStatus = "CTCSS: " + code.getDisplayString() + " [ALLOWED]";
                mLastToneStatusCTCSS = code;
                mLastToneStatusDCS = null;
                mLastToneStatusWasAllowed = true;
            }
            incrementCount(code.getDisplayString(), true);
        }
    }

    /**
     * Called by NBFMDecoder when a matching DCS code is detected (allowed)
     */
    public void setDetectedDCS(DCSCode code)
    {
        if(code != null)
        {
            if(code != mLastToneStatusDCS || !mLastToneStatusWasAllowed)
            {
                mToneStatus = "DCS: " + code.toString() + " [ALLOWED]";
                mLastToneStatusDCS = code;
                mLastToneStatusCTCSS = null;
                mLastToneStatusWasAllowed = true;
            }
            incrementCount("DCS " + code.toString(), true);
        }
    }

    /**
     * Called by NBFMDecoder when a non-matching CTCSS tone is detected (rejected)
     */
    public void setRejectedCTCSS(CTCSSCode code)
    {
        if(code != null)
        {
            if(code != mLastToneStatusCTCSS || mLastToneStatusWasAllowed)
            {
                mToneStatus = "CTCSS: " + code.getDisplayString() + " [REJECTED]";
                mLastToneStatusCTCSS = code;
                mLastToneStatusDCS = null;
                mLastToneStatusWasAllowed = false;
            }
            incrementCount(code.getDisplayString(), false);
        }
    }

    /**
     * Called by NBFMDecoder when a non-matching DCS code is detected (rejected)
     */
    public void setRejectedDCS(DCSCode code)
    {
        if(code != null)
        {
            if(code != mLastToneStatusDCS || mLastToneStatusWasAllowed)
            {
                mToneStatus = "DCS: " + code.toString() + " [REJECTED]";
                mLastToneStatusDCS = code;
                mLastToneStatusCTCSS = null;
                mLastToneStatusWasAllowed = false;
            }
            incrementCount("DCS " + code.toString(), false);
        }
    }

    /**
     * Called by NBFMDecoder when tone is lost
     */
    public void setToneLost()
    {
        mToneStatus = "No tone detected";
        mLastToneStatusCTCSS = null;
        mLastToneStatusDCS = null;
    }

    private void incrementCount(String toneLabel, boolean accepted)
    {
        synchronized(mToneCounts)
        {
            int[] counts = mToneCounts.computeIfAbsent(toneLabel, k -> new int[]{0, 0});
            if(accepted)
            {
                counts[0]++;
            }
            else
            {
                counts[1]++;
            }
        }
    }

    @Override
    public String getActivitySummary()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Activity Summary - Decoder:NBFM\n");

        if(mDeemphasisMode != DecodeConfigNBFM.DeemphasisMode.NONE)
        {
            sb.append("\nDe-emphasis: ").append(mDeemphasisMode.getMicroseconds()).append("\u00b5s");
        }

        sb.append("\n\nTone Filter: ");
        if(mToneFilterEnabled)
        {
            sb.append("ENABLED\n");
            if(mConfiguredFilters.size() == 1)
            {
                sb.append("Configured Filter: ").append(mConfiguredFilters.get(0).getDisplayString()).append("\n");
            }
            else if(mConfiguredFilters.size() > 1)
            {
                sb.append("Configured Filters (").append(mConfiguredFilters.size()).append("):\n");
                for(ChannelToneFilter filter : mConfiguredFilters)
                {
                    sb.append("  ").append(filter.getDisplayString()).append("\n");
                }
            }
            else
            {
                sb.append("Configured Filter: (none)\n");
            }
        }
        else
        {
            sb.append("Disabled\n");
        }

        sb.append("\nCurrent Status: ").append(mToneStatus).append("\n");

        synchronized(mToneCounts)
        {
            if(!mToneCounts.isEmpty())
            {
                sb.append("\nDetected Tones:\n");
                for(Map.Entry<String, int[]> entry : mToneCounts.entrySet())
                {
                    int accepted = entry.getValue()[0];
                    int rejected = entry.getValue()[1];
                    String status;
                    if(accepted > 0 && rejected == 0)
                    {
                        status = "ALLOWED (" + accepted + ")";
                    }
                    else if(rejected > 0 && accepted == 0)
                    {
                        status = "REJECTED (" + rejected + ")";
                    }
                    else
                    {
                        status = "ALLOWED (" + accepted + ") REJECTED (" + rejected + ")";
                    }
                    sb.append("\t").append(entry.getKey()).append(" - ").append(status).append("\n");
                }
            }
        }

        sb.append("\n");
        return sb.toString();
    }
}
