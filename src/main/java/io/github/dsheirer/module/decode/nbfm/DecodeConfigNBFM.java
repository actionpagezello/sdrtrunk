/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.dsp.squelch.NoiseSquelch;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * Decoder configuration for an NBFM channel with VoxSend audio processing
 */
public class DecodeConfigNBFM extends DecodeConfigAnalog
{
    private boolean mAudioFilter = true;
    private float mSquelchNoiseOpenThreshold = NoiseSquelch.DEFAULT_NOISE_OPEN_THRESHOLD;
    private float mSquelchNoiseCloseThreshold = NoiseSquelch.DEFAULT_NOISE_CLOSE_THRESHOLD;
    private int mSquelchHysteresisOpenThreshold = NoiseSquelch.DEFAULT_HYSTERESIS_OPEN_THRESHOLD;
    private int mSquelchHysteresisCloseThreshold = NoiseSquelch.DEFAULT_HYSTERESIS_CLOSE_THRESHOLD;

    // VOXSEND AUDIO FILTER CONFIGURATION
    private boolean mDeemphasisEnabled = true;
    private double mDeemphasisTimeConstant = 75.0; // microseconds
    private boolean mLowPassEnabled = true;
    private double mLowPassCutoff = 3400.0; // Hz
    private boolean mBassBoostEnabled = false;
    private float mBassBoostDb = 0.0f; // 0 to +12 dB
    private boolean mNoiseGateEnabled = false;
    private float mNoiseGateThreshold = 4.0f; // percentage 0-100%
    private float mNoiseGateReduction = 0.8f; // 0.0 to 1.0
    private int mNoiseGateHoldTime = 500; // milliseconds
    private boolean mAgcEnabled = true;
    private float mAgcTargetLevel = -18.0f; // dB (stores voice enhancement amount)
    private float mAgcMaxGain = 24.0f; // dB (stores input gain)

    /**
     * Constructs an instance
     */
    public DecodeConfigNBFM()
    {
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type", namespace = "http://www.w3.org/2001/XMLSchema-instance")
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    @Override
    protected Bandwidth getDefaultBandwidth()
    {
        return Bandwidth.BW_12_5;
    }

    /**
     * Channel sample stream specification.
     */
    @JsonIgnore
    @Override
    public ChannelSpecification getChannelSpecification()
    {
        switch(getBandwidth())
        {
            case BW_7_5:
                return new ChannelSpecification(25000.0, 7500, 3500.0, 3750.0);
            case BW_12_5:
                return new ChannelSpecification(25000.0, 12500, 6000.0, 7000.0);
            case BW_25_0:
                return new ChannelSpecification(50000.0, 25000, 12500.0, 13500.0);
            default:
                throw new IllegalArgumentException("Unrecognized FM bandwidth value: " + getBandwidth());
        }
    }

    /**
     * Indicates if the user wants the demodulated audio to be high-pass filtered.
     * @return enable status, defaults to true.
     */
    @JacksonXmlProperty(isAttribute = true, localName = "audioFilter")
    public boolean isAudioFilter()
    {
        return mAudioFilter;
    }

    /**
     * Sets the enabled state of high-pass filtering of the demodulated audio.
     * @param audioFilter to true to enable high-pass filtering.
     */
    public void setAudioFilter(boolean audioFilter)
    {
        mAudioFilter = audioFilter;
    }

    /**
     * Squelch noise open threshold in the range 0.0 to 1.0 with a default of 0.1
     * @return noise open threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseOpenThreshold")
    public float getSquelchNoiseOpenThreshold()
    {
        return mSquelchNoiseOpenThreshold;
    }

    /**
     * Squelch noise close threshold in the range 0.0 to 1.0, greater than or equal to open threshold, with a default of 0.2
     * @return noise close threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchNoiseCloseThreshold")
    public float getSquelchNoiseCloseThreshold()
    {
        return mSquelchNoiseCloseThreshold;
    }

    /**
     * Sets the squelch noise threshold.
     * @param open in range 0.0 to 1.0 with a default of 0.1
     */
    public void setSquelchNoiseOpenThreshold(float open)
    {
        if(open < NoiseSquelch.MINIMUM_NOISE_THRESHOLD || open > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch noise open threshold is out of range: " + open);
        }

        mSquelchNoiseOpenThreshold = open;
    }

    /**
     * Sets the squelch noise close threshold.
     * @param close in range 0.0 to 1.0 and greater than or equal to open, with a default of 0.1
     */
    public void setSquelchNoiseCloseThreshold(float close)
    {
        if(close < NoiseSquelch.MINIMUM_NOISE_THRESHOLD || close > NoiseSquelch.MAXIMUM_NOISE_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch noise close threshold is out of range: " + close);
        }

        mSquelchNoiseCloseThreshold = close;
    }

    /**
     * Squelch hysteresis open threshold in range 1-10 with a default of 4.
     * @return hysteresis open threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisOpenThreshold")
    public int getSquelchHysteresisOpenThreshold()
    {
        return mSquelchHysteresisOpenThreshold;
    }

    /**
     * Sets the squelch time threshold in the range 1-10.
     * @param open threshold
     */
    public void setSquelchHysteresisOpenThreshold(int open)
    {
        if(open < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD || open > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch hysteresis open threshold is out of range: " + open);
        }

        mSquelchHysteresisOpenThreshold = open;
    }

    /**
     * Squelch hysteresis close threshold in range 1-10 with a default of 4.
     * @return hysteresis close threshold
     */
    @JacksonXmlProperty(isAttribute = true, localName = "squelchHysteresisCloseThreshold")
    public int getSquelchHysteresisCloseThreshold()
    {
        return mSquelchHysteresisCloseThreshold;
    }

    /**
     * Sets the squelch close threshold in the range 1-10.
     * @param close threshold
     */
    public void setSquelchHysteresisCloseThreshold(int close)
    {
        if(close < NoiseSquelch.MINIMUM_HYSTERESIS_THRESHOLD || close > NoiseSquelch.MAXIMUM_HYSTERESIS_THRESHOLD)
        {
            throw new IllegalArgumentException("Squelch hysteresis close threshold is out of range: " + close);
        }

        mSquelchHysteresisCloseThreshold = close;
    }

    // ========== VOXSEND AUDIO FILTER GETTERS AND SETTERS ==========

    /**
     * Indicates if FM de-emphasis filter is enabled.
     * @return true if enabled (default)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "deemphasisEnabled")
    public boolean isDeemphasisEnabled()
    {
        return mDeemphasisEnabled;
    }

    /**
     * Sets the enabled state of the FM de-emphasis filter.
     * @param enabled true to enable de-emphasis filtering
     */
    public void setDeemphasisEnabled(boolean enabled)
    {
        mDeemphasisEnabled = enabled;
    }

    /**
     * Gets the de-emphasis time constant in microseconds.
     * @return time constant (75.0 for North America, 50.0 for Europe)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "deemphasisTimeConstant")
    public double getDeemphasisTimeConstant()
    {
        return mDeemphasisTimeConstant;
    }

    /**
     * Sets the de-emphasis time constant.
     * @param timeConstant in microseconds (75.0 for North America, 50.0 for Europe)
     */
    public void setDeemphasisTimeConstant(double timeConstant)
    {
        mDeemphasisTimeConstant = timeConstant;
    }

    /**
     * Indicates if low-pass filter is enabled.
     * @return true if enabled (default)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "lowPassEnabled")
    public boolean isLowPassEnabled()
    {
        return mLowPassEnabled;
    }

    /**
     * Sets the enabled state of the low-pass filter.
     * @param enabled true to enable low-pass filtering
     */
    public void setLowPassEnabled(boolean enabled)
    {
        mLowPassEnabled = enabled;
    }

    /**
     * Gets the low-pass filter cutoff frequency in Hz.
     * @return cutoff frequency (default 3400 Hz)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "lowPassCutoff")
    public double getLowPassCutoff()
    {
        return mLowPassCutoff;
    }

    /**
     * Sets the low-pass filter cutoff frequency.
     * @param cutoff in Hz (recommended 3000-4000 Hz)
     */
    public void setLowPassCutoff(double cutoff)
    {
        mLowPassCutoff = cutoff;
    }

    /**
     * Indicates if bass boost is enabled.
     * @return true if enabled (default false)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "bassBoostEnabled")
    public boolean isBassBoostEnabled()
    {
        return mBassBoostEnabled;
    }

    /**
     * Sets the enabled state of bass boost.
     * @param enabled true to enable bass boost
     */
    public void setBassBoostEnabled(boolean enabled)
    {
        mBassBoostEnabled = enabled;
    }

    /**
     * Gets the bass boost amount in dB.
     * @return boost amount 0-12 dB (default 0)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "bassBoostDb")
    public float getBassBoostDb()
    {
        return mBassBoostDb;
    }

    /**
     * Sets the bass boost amount.
     * @param boostDb Bass boost in dB (0 to +12 dB)
     */
    public void setBassBoostDb(float boostDb)
    {
        mBassBoostDb = Math.max(0.0f, Math.min(12.0f, boostDb));
    }

    /**
     * Indicates if noise gate (intelligent squelch) is enabled.
     * @return true if enabled (default is false)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "noiseGateEnabled")
    public boolean isNoiseGateEnabled()
    {
        return mNoiseGateEnabled;
    }

    /**
     * Sets the enabled state of the noise gate.
     * @param enabled true to enable noise gate
     */
    public void setNoiseGateEnabled(boolean enabled)
    {
        mNoiseGateEnabled = enabled;
    }

    /**
     * Gets the noise gate threshold percentage.
     * @return threshold 0-100% (default 4%)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "noiseGateThreshold")
    public float getNoiseGateThreshold()
    {
        return mNoiseGateThreshold;
    }

    /**
     * Sets the noise gate threshold percentage.
     * @param threshold percentage 0-100% (gate opens when level > threshold)
     */
    public void setNoiseGateThreshold(float threshold)
    {
        mNoiseGateThreshold = Math.max(0.0f, Math.min(100.0f, threshold));
    }

    /**
     * Gets the noise gate reduction amount.
     * @return reduction amount 0.0 to 1.0 (default 0.8 = 80%)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "noiseGateReduction")
    public float getNoiseGateReduction()
    {
        return mNoiseGateReduction;
    }

    /**
     * Sets the noise gate reduction amount.
     * @param reduction 0.0 (no reduction) to 1.0 (full mute)
     */
    public void setNoiseGateReduction(float reduction)
    {
        mNoiseGateReduction = Math.max(0.0f, Math.min(1.0f, reduction));
    }

    /**
     * Gets the noise gate hold time in milliseconds.
     * @return hold time (default 500ms)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "noiseGateHoldTime")
    public int getNoiseGateHoldTime()
    {
        return mNoiseGateHoldTime;
    }

    /**
     * Sets the noise gate hold time.
     * @param timeMs Duration to keep gate open after voice stops (0-1000ms)
     */
    public void setNoiseGateHoldTime(int timeMs)
    {
        mNoiseGateHoldTime = Math.max(0, Math.min(1000, timeMs));
    }

    /**
     * Indicates if AGC/Voice Enhancement is enabled.
     * @return true if enabled (default)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "agcEnabled")
    public boolean isAgcEnabled()
    {
        return mAgcEnabled;
    }

    /**
     * Sets the enabled state of the AGC/Voice Enhancement.
     * @param enabled true to enable
     */
    public void setAgcEnabled(boolean enabled)
    {
        mAgcEnabled = enabled;
    }

    /**
     * Gets the AGC target output level in dB FS.
     * NOTE: This is repurposed to store voice enhancement amount (mapped to -30 to -6 dB range)
     * @return target level (default -18 dB)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "agcTargetLevel")
    public float getAgcTargetLevel()
    {
        return mAgcTargetLevel;
    }

    /**
     * Sets the AGC target output level.
     * NOTE: This is repurposed to store voice enhancement amount
     * @param level in dB FS (mapped from 0-100% voice enhancement)
     */
    public void setAgcTargetLevel(float level)
    {
        mAgcTargetLevel = level;
    }

    /**
     * Gets the AGC maximum gain in dB.
     * NOTE: This is repurposed to store input gain (mapped from linear gain)
     * @return maximum gain (default 24 dB)
     */
    @JacksonXmlProperty(isAttribute = true, localName = "agcMaxGain")
    public float getAgcMaxGain()
    {
        return mAgcMaxGain;
    }

    /**
     * Sets the AGC maximum gain.
     * NOTE: This is repurposed to store input gain
     * @param gain in dB (mapped from 0.1-5.0x linear gain)
     */
    public void setAgcMaxGain(float gain)
    {
        mAgcMaxGain = gain;
    }
}
