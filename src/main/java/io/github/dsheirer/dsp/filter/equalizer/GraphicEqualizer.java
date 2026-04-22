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

package io.github.dsheirer.dsp.filter.equalizer;

import io.github.dsheirer.dsp.filter.IIRBiQuadraticFilter;

/**
 * 5-band graphic equalizer using cascaded IIR biquad peak filters.
 *
 * Default center frequencies are optimized for voice audio at 8 kHz sample rate:
 *   Band 1:  100 Hz  - Sub-bass / rumble
 *   Band 2:  400 Hz  - Low-mid / body
 *   Band 3: 1000 Hz  - Mid / intelligibility
 *   Band 4: 2000 Hz  - Upper-mid / presence
 *   Band 5: 3500 Hz  - High / clarity (near Nyquist at 8 kHz)
 *
 * Each band has an adjustable gain from -12 dB to +12 dB.
 * Q (bandwidth) is fixed at 1.0 (approximately 1 octave).
 */
public class GraphicEqualizer
{
    public static final int BAND_COUNT = 5;
    public static final double DEFAULT_Q = 1.0;
    public static final double MIN_GAIN_DB = -12.0;
    public static final double MAX_GAIN_DB = 12.0;

    public static final double[] DEFAULT_CENTER_FREQUENCIES = {100.0, 400.0, 1000.0, 2000.0, 3500.0};
    public static final String[] BAND_LABELS = {"100 Hz", "400 Hz", "1 kHz", "2 kHz", "3.5 kHz"};

    private final IIRBiQuadraticFilter[] mBands;
    private final double[] mGainDb;
    private final double mSampleRate;
    private boolean mEnabled;

    /**
     * Creates a 5-band graphic equalizer.
     *
     * @param sampleRate audio sample rate (typically 8000 Hz for decoded P25 audio)
     */
    public GraphicEqualizer(double sampleRate)
    {
        mSampleRate = sampleRate;
        mEnabled = false;
        mGainDb = new double[BAND_COUNT];
        mBands = new IIRBiQuadraticFilter[BAND_COUNT];

        for(int i = 0; i < BAND_COUNT; i++)
        {
            mGainDb[i] = 0.0;
            mBands[i] = new IIRBiQuadraticFilter(IIRBiQuadraticFilter.Type.PEAK,
                DEFAULT_CENTER_FREQUENCIES[i], sampleRate, DEFAULT_Q, 0.0);
        }
    }

    /**
     * Indicates if the equalizer is enabled.
     */
    public boolean isEnabled()
    {
        return mEnabled;
    }

    /**
     * Sets the enabled state of the equalizer.
     */
    public void setEnabled(boolean enabled)
    {
        mEnabled = enabled;
    }

    /**
     * Gets the gain in dB for the specified band.
     *
     * @param band index (0-4)
     * @return gain in dB
     */
    public double getBandGain(int band)
    {
        if(band >= 0 && band < BAND_COUNT)
        {
            return mGainDb[band];
        }
        return 0.0;
    }

    /**
     * Sets the gain for a specific band.
     *
     * @param band index (0-4)
     * @param gainDb gain in dB, clamped to [-12, +12]
     */
    public void setBandGain(int band, double gainDb)
    {
        if(band >= 0 && band < BAND_COUNT)
        {
            gainDb = Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
            mGainDb[band] = gainDb;

            // Reconfigure the biquad filter with new gain
            mBands[band].configure(IIRBiQuadraticFilter.Type.PEAK,
                DEFAULT_CENTER_FREQUENCIES[band], mSampleRate, DEFAULT_Q, gainDb);
        }
    }

    /**
     * Sets all band gains at once.
     *
     * @param gains array of 5 gain values in dB
     */
    public void setBandGains(double[] gains)
    {
        if(gains != null)
        {
            for(int i = 0; i < Math.min(gains.length, BAND_COUNT); i++)
            {
                setBandGain(i, gains[i]);
            }
        }
    }

    /**
     * Gets all band gains.
     *
     * @return array of 5 gain values in dB
     */
    public double[] getBandGains()
    {
        return mGainDb.clone();
    }

    /**
     * Resets all band gains to 0 dB (flat response).
     */
    public void reset()
    {
        for(int i = 0; i < BAND_COUNT; i++)
        {
            setBandGain(i, 0.0);
            mBands[i].reset();
        }
    }

    /**
     * Processes a single audio sample through all 5 EQ bands in series.
     *
     * @param sample input audio sample
     * @return filtered audio sample
     */
    public float process(float sample)
    {
        if(!mEnabled)
        {
            return sample;
        }

        double result = sample;

        for(int i = 0; i < BAND_COUNT; i++)
        {
            // Skip bands with 0 dB gain (no processing needed)
            if(mGainDb[i] != 0.0)
            {
                result = mBands[i].filter(result);
            }
        }

        return (float) result;
    }

    /**
     * Processes an array of audio samples through the equalizer.
     *
     * @param samples input audio buffer
     * @return processed audio buffer (same array, modified in place)
     */
    public float[] process(float[] samples)
    {
        if(!mEnabled || samples == null)
        {
            return samples;
        }

        for(int s = 0; s < samples.length; s++)
        {
            double result = samples[s];

            for(int i = 0; i < BAND_COUNT; i++)
            {
                if(mGainDb[i] != 0.0)
                {
                    result = mBands[i].filter(result);
                }
            }

            samples[s] = (float) result;
        }

        return samples;
    }
}
