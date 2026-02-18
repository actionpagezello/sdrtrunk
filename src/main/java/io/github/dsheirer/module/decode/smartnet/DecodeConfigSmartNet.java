/*
 * *****************************************************************************
 * Copyright (C) 2024 SDR Trunk Contributors
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
package io.github.dsheirer.module.decode.smartnet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for SmartNet/SmartZone trunking decoder.
 */
public class DecodeConfigSmartNet extends DecodeConfiguration
{
    private String mBandPlan = "800_standard";
    private List<Integer> mAllowedTalkgroups = new ArrayList<>();
    private boolean mTalkgroupFilterEnabled = false;
    private double mCustomBaseFrequency = 0;
    private double mCustomHighFrequency = 0;
    private double mCustomSpacing = 0.025;
    private int mCustomOffset = 0;

    public DecodeConfigSmartNet()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.SMARTNET;
    }

    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        // SmartNet control channel: 12.5 kHz NBFM, needs 25 kHz sample rate minimum
        return new ChannelSpecification(25000.0, 12500, 6000.0, 7000.0);
    }

    @JacksonXmlProperty(isAttribute = true, localName = "band_plan")
    public String getBandPlan()
    {
        return mBandPlan;
    }

    public void setBandPlan(String bandPlan)
    {
        mBandPlan = bandPlan;
    }

    @JacksonXmlProperty(localName = "allowed_talkgroups")
    public List<Integer> getAllowedTalkgroups()
    {
        return mAllowedTalkgroups;
    }

    public void setAllowedTalkgroups(List<Integer> talkgroups)
    {
        mAllowedTalkgroups = talkgroups != null ? talkgroups : new ArrayList<>();
    }

    @JacksonXmlProperty(isAttribute = true, localName = "talkgroup_filter_enabled")
    public boolean isTalkgroupFilterEnabled()
    {
        return mTalkgroupFilterEnabled;
    }

    public void setTalkgroupFilterEnabled(boolean enabled)
    {
        mTalkgroupFilterEnabled = enabled;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "custom_base_frequency")
    public double getCustomBaseFrequency()
    {
        return mCustomBaseFrequency;
    }

    public void setCustomBaseFrequency(double frequency)
    {
        mCustomBaseFrequency = frequency;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "custom_high_frequency")
    public double getCustomHighFrequency()
    {
        return mCustomHighFrequency;
    }

    public void setCustomHighFrequency(double frequency)
    {
        mCustomHighFrequency = frequency;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "custom_spacing")
    public double getCustomSpacing()
    {
        return mCustomSpacing;
    }

    public void setCustomSpacing(double spacing)
    {
        mCustomSpacing = spacing;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "custom_offset")
    public int getCustomOffset()
    {
        return mCustomOffset;
    }

    public void setCustomOffset(int offset)
    {
        mCustomOffset = offset;
    }

    @JsonIgnore
    public SmartNetBandPlan createBandPlan()
    {
        switch(mBandPlan.toLowerCase())
        {
            case "800_rebanded":
            case "800_reband":
                return new SmartNetBandPlan(SmartNetBandPlan.BandPlanType.BAND_800_REBANDED);
            case "800_splinter":
                return new SmartNetBandPlan(SmartNetBandPlan.BandPlanType.BAND_800_SPLINTER);
            case "900":
                return new SmartNetBandPlan(SmartNetBandPlan.BandPlanType.BAND_900);
            case "custom":
            case "400":
            case "400_custom":
                return new SmartNetBandPlan(mCustomBaseFrequency, mCustomHighFrequency,
                    mCustomSpacing, mCustomOffset);
            case "800_standard":
            default:
                return new SmartNetBandPlan(SmartNetBandPlan.BandPlanType.BAND_800_STANDARD);
        }
    }
}
