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
 * Impulse noise blanker for complex (IQ) sample streams.
 * Detects and zeros impulse spikes that exceed a threshold relative to the
 * running average power level, preventing them from being smeared across
 * multiple samples by downstream decimation filters.
 *
 * The running average is updated by both blanked and non-blanked samples
 * (blanked at reduced weight) to prevent a feedback loop where the average
 * stays artificially low and legitimate voice peaks on weak signals get
 * blanked indefinitely.
 */
public class NoiseBlanker
{
    private static final float ALPHA = 0.001f;           // Smoothing factor for running average
    private static final float ALPHA_BLANKED = ALPHA * 0.1f; // Reduced weight for blanked samples
    private static final float THRESHOLD = 15.0f;        // Blanking threshold as multiplier of average power
                                                          // Was 6.0 but too aggressive for strong nearby signals
                                                          // where legitimate voice peaks exceed 36x average power
    private static final int RATE_CHECK_WINDOW = 2000;   // Samples between blanking-rate checks
    private static final float MAX_BLANKING_RATE = 0.05f; // 5% — above this the average is probably too low

    private double mAveragePower = 0.001;
    private long mBlankedCount = 0;
    private long mTotalCount = 0;
    private int mWindowSamples = 0;
    private int mWindowBlanked = 0;

    public NoiseBlanker()
    {
    }

    /**
     * Processes I and Q sample arrays, zeroing any samples that exceed the
     * impulse threshold relative to running average power.
     * @param iSamples in-phase samples (modified in-place)
     * @param qSamples quadrature samples (modified in-place)
     */
    public void process(float[] iSamples, float[] qSamples)
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
            double power = i * i + q * q;

            mTotalCount++;
            mWindowSamples++;

            // Check if this sample is an impulse spike
            if(power > mAveragePower * THRESHOLD * THRESHOLD && mAveragePower > 1e-10)
            {
                // Blank the spike by zeroing both I and Q
                iSamples[x] = 0.0f;
                qSamples[x] = 0.0f;
                mBlankedCount++;
                mWindowBlanked++;

                // Update average at reduced rate even for blanked samples. This
                // breaks the feedback loop: without it, blanking prevents the
                // average from rising, causing more blanking, which can suppress
                // entire weak signals. True impulse spikes are rare enough that
                // the reduced-weight updates have negligible effect on the average.
                mAveragePower = (1.0 - ALPHA_BLANKED) * mAveragePower + ALPHA_BLANKED * power;
            }
            else
            {
                // Update running average power at normal rate
                mAveragePower = (1.0 - ALPHA) * mAveragePower + ALPHA * power;
            }

            // Safety valve: if blanking rate exceeds threshold over a check
            // window, the average is stuck too low for the actual signal level.
            // Boost it to break the feedback loop faster.
            if(mWindowSamples >= RATE_CHECK_WINDOW)
            {
                if(mWindowBlanked > RATE_CHECK_WINDOW * MAX_BLANKING_RATE)
                {
                    mAveragePower *= 1.5;
                }
                mWindowSamples = 0;
                mWindowBlanked = 0;
            }
        }
    }

    /**
     * Resets the noise blanker state.
     */
    public void reset()
    {
        mAveragePower = 0.001;
        mBlankedCount = 0;
        mTotalCount = 0;
        mWindowSamples = 0;
        mWindowBlanked = 0;
    }

    @Override
    public String toString()
    {
        double blankedPercent = mTotalCount > 0 ? (100.0 * mBlankedCount / mTotalCount) : 0.0;
        return String.format("NoiseBlanker[avgPower=%.6f blanked=%d/%d (%.2f%%)]",
            mAveragePower, mBlankedCount, mTotalCount, blankedPercent);
    }
}
