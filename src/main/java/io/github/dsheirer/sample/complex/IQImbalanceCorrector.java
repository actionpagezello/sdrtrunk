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
package io.github.dsheirer.sample.complex;

/**
 * Corrects IQ imbalance (gain and phase mismatch) in complex sample streams.
 * Uses a running estimate of I/Q power ratio and cross-correlation to compute
 * correction coefficients that are applied in-place to the Q channel.
 */
public class IQImbalanceCorrector
{
    private static final float ALPHA = 0.001f; // Smoothing factor for running estimates
    private double mMeanIPower = 1.0;
    private double mMeanQPower = 1.0;
    private double mMeanIQCross = 0.0;
    private double mGainCorrection = 1.0;
    private double mPhaseCorrection = 0.0;
    private long mSampleCount = 0;

    public IQImbalanceCorrector()
    {
    }

    /**
     * Applies IQ imbalance correction to the provided I and Q sample arrays in-place.
     * @param iSamples in-phase samples
     * @param qSamples quadrature samples
     */
    public void correct(float[] iSamples, float[] qSamples)
    {
        if(iSamples == null || qSamples == null || iSamples.length == 0)
        {
            return;
        }

        int length = Math.min(iSamples.length, qSamples.length);

        for(int x = 0; x < length; x++)
        {
            float i = iSamples[x];
            float q = qSamples[x];

            // Update running power estimates
            mMeanIPower = (1.0 - ALPHA) * mMeanIPower + ALPHA * (i * i);
            mMeanQPower = (1.0 - ALPHA) * mMeanQPower + ALPHA * (q * q);
            mMeanIQCross = (1.0 - ALPHA) * mMeanIQCross + ALPHA * (i * q);

            // Compute correction coefficients
            if(mMeanIPower > 0)
            {
                mGainCorrection = Math.sqrt(mMeanIPower / Math.max(mMeanQPower, 1e-10));
                mPhaseCorrection = mMeanIQCross / Math.max(mMeanIPower, 1e-10);
            }

            // Apply correction to Q channel in-place
            qSamples[x] = (float)(mGainCorrection * q - mPhaseCorrection * i);
            mSampleCount++;
        }
    }

    /**
     * Resets the corrector state.
     */
    public void reset()
    {
        mMeanIPower = 1.0;
        mMeanQPower = 1.0;
        mMeanIQCross = 0.0;
        mGainCorrection = 1.0;
        mPhaseCorrection = 0.0;
        mSampleCount = 0;
    }

    @Override
    public String toString()
    {
        return String.format("IQImbalance[gain=%.4f phase=%.4f samples=%d]",
            mGainCorrection, mPhaseCorrection, mSampleCount);
    }
}
