/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014-2026 Dennis Sheirer
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package io.github.dsheirer.module.decode.config;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * JMBE audio codec settings for the decoders that use it (DMR, NXDN, P25).  Taken from upstream SDRTrunk
 * (#2482/#2484/#2511/#2530) with the same attribute names and namespace, so a playlist keeps these settings whichever
 * of the two programs saves it.  AP-fork (ap-15.10.10): the settings are applied to each channel's JMBE 2.x codec.
 */
public abstract class DecodeConfigJMBE extends DecodeConfiguration
{
    public static float DEFAULT_COMFORT_NOISE_GAIN = 0.05f;
    public static float DEFAULT_TONE_GAIN = 1.0f;

    /**
     * AP-fork (ap-15.10.10): comfort noise gain the fork wrote to every playlist before the setting was applied
     * (fleet setting 1.0, from upstream's original default).  PlaylistManager migrates it to the default once per
     * machine.
     */
    public static float LEGACY_FORK_COMFORT_NOISE_GAIN = 1.0f;

    private boolean mAGC = true;
    private boolean mLegacyToneLevels;
    private float mToneGain = DEFAULT_TONE_GAIN;
    private float mComfortNoiseGain = DEFAULT_COMFORT_NOISE_GAIN;

    /**
     * Use the JMBE library's legacy (pre 2.0) AMBE tone levels.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "jmbeLegacyToneLevels", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public boolean isLegacyToneLevels()
    {
        return mLegacyToneLevels;
    }

    public void setLegacyToneLevels(boolean enabled)
    {
        mLegacyToneLevels = enabled;
    }

    /**
     * JMBE automatic gain control.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "jmbeAGC", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public boolean isAGC()
    {
        return mAGC;
    }

    public void setAGC(boolean enabled)
    {
        mAGC = enabled;
    }

    /**
     * Gain for the JMBE tone generator (0.0 - 1.0).
     */
    @JacksonXmlProperty(isAttribute = true, localName = "jmbeToneGain", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public float getToneGain()
    {
        return mToneGain;
    }

    public void setToneGain(float gain)
    {
        mToneGain = clamp(gain);
    }

    /**
     * Gain for the JMBE comfort noise generator (0.0 - 1.0), heard in place of lost or muted voice frames.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "jmbeNoiseGain", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public float getComfortNoiseGain()
    {
        return mComfortNoiseGain;
    }

    public void setComfortNoiseGain(float gain)
    {
        mComfortNoiseGain = clamp(gain);
    }

    private static float clamp(float gain)
    {
        if(gain > 1.0f)
        {
            return 1.0f;
        }
        else if(gain < 0)
        {
            return 0;
        }

        return gain;
    }
}
