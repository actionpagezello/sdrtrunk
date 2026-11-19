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
 * 20-band graphic equalizer using cascaded IIR biquad peak filters, for decoded voice audio at 8 kHz.
 *
 * Center frequencies from 170 Hz to 3.5 kHz, about a quarter octave apart (ap-11; 10 bands about half an octave apart
 * before, ap-15.10.6 to ap-10).  The ten earlier centers are kept as every other band, with a band between each pair
 * and one below 200 Hz:
 *   170, 200, 240, 280, 330, 390, 460, 550, 650, 770, 920, 1100, 1300, 1500, 1800, 2100, 2500, 2900, 3200 and 3500 Hz
 *
 * Each band has an adjustable gain from -12 dB to +12 dB.  Q (bandwidth) is fixed at 6.0, so each band covers about
 * the quarter octave between its neighbors.  With all 20 bands at +6 dB the response peaks at about +10 dB, close to
 * the +9 dB of the 10-band equalizer (Q 3.0) with all bands at +6 dB.
 *
 * Settings saved for the 10-band equalizer are converted with fromLegacyGains(): each earlier band's gain goes to the
 * same center, the band between two earlier bands gets their average and the band below 200 Hz is left flat.
 */
public class GraphicEqualizer
{
    public static final int BAND_COUNT = 20;
    public static final double DEFAULT_Q = 6.0;
    public static final double MIN_GAIN_DB = -12.0;
    public static final double MAX_GAIN_DB = 12.0;

    public static final double[] DEFAULT_CENTER_FREQUENCIES = {
        170.0, 200.0, 240.0, 280.0, 330.0, 390.0, 460.0, 550.0, 650.0, 770.0,
        920.0, 1100.0, 1300.0, 1500.0, 1800.0, 2100.0, 2500.0, 2900.0, 3200.0, 3500.0
    };
    public static final String[] BAND_LABELS = {
        "170 Hz", "200 Hz", "240 Hz", "280 Hz", "330 Hz", "390 Hz", "460 Hz", "550 Hz", "650 Hz", "770 Hz",
        "920 Hz", "1.1 kHz", "1.3 kHz", "1.5 kHz", "1.8 kHz", "2.1 kHz", "2.5 kHz", "2.9 kHz", "3.2 kHz", "3.5 kHz"
    };

    /**
     * Short band labels for the narrow slider columns in the editors
     */
    public static final String[] BAND_SHORT_LABELS = {
        "170", "200", "240", "280", "330", "390", "460", "550", "650", "770",
        "920", "1.1k", "1.3k", "1.5k", "1.8k", "2.1k", "2.5k", "2.9k", "3.2k", "3.5k"
    };

    /**
     * Number of bands of the earlier equalizer (ap-15.10.6 to ap-10): 200, 280, 390, 550, 770, 1100, 1500, 2100, 2900
     * and 3500 Hz, which are bands 1, 3, 5 ... 19 here.
     */
    public static final int LEGACY_BAND_COUNT = 10;

    /**
     * Converts gains saved for the 10-band equalizer to this equalizer's 20 bands.
     * @param legacyGains 10 gains in dB
     * @return 20 gains in dB
     */
    public static double[] fromLegacyGains(double[] legacyGains)
    {
        double[] gains = new double[BAND_COUNT];

        for(int k = 0; k < LEGACY_BAND_COUNT && k < legacyGains.length; k++)
        {
            gains[2 * k + 1] = clamp(legacyGains[k]);
        }

        for(int k = 0; k < LEGACY_BAND_COUNT - 1 && k + 1 < legacyGains.length; k++)
        {
            gains[2 * k + 2] = clamp((legacyGains[k] + legacyGains[k + 1]) / 2.0);
        }

        return gains;
    }

    /**
     * Converts this equalizer's 20 gains to the 10-band equalizer's bands (the bands at the same centers), so that a
     * playlist opened in an earlier version keeps an approximation of the settings.
     * @param gains 20 gains in dB
     * @return 10 gains in dB
     */
    public static double[] toLegacyGains(double[] gains)
    {
        double[] legacy = new double[LEGACY_BAND_COUNT];

        for(int k = 0; k < LEGACY_BAND_COUNT; k++)
        {
            int band = 2 * k + 1;
            legacy[k] = gains != null && band < gains.length ? gains[band] : 0.0;
        }

        return legacy;
    }

    /**
     * Returns a copy of the gains with exactly BAND_COUNT values, each clamped to the gain range.  An array of
     * LEGACY_BAND_COUNT values is converted with fromLegacyGains(); any other length is padded with 0 dB or cut.
     * @param gains in dB, or null
     * @return BAND_COUNT gains in dB (all 0 dB for null)
     */
    public static double[] normalizeGains(double[] gains)
    {
        if(gains != null && gains.length == LEGACY_BAND_COUNT)
        {
            return fromLegacyGains(gains);
        }

        double[] normalized = new double[BAND_COUNT];

        if(gains != null)
        {
            for(int i = 0; i < Math.min(gains.length, BAND_COUNT); i++)
            {
                normalized[i] = clamp(gains[i]);
            }
        }

        return normalized;
    }

    private static double clamp(double gainDb)
    {
        return Math.max(MIN_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb));
    }

    private final IIRBiQuadraticFilter[] mBands;
    private final double[] mGainDb;
    private final double mSampleRate;
    private boolean mEnabled;

    /**
     * Creates a 20-band graphic equalizer.
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
     * @param band index (0-19)
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
     * @param band index (0-19)
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
     * @param gains array of 20 gain values in dB
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
     * @return array of 20 gain values in dB
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
     * Processes a single audio sample through all 20 EQ bands in series.
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