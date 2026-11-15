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

package io.github.dsheirer.module.carrier;

import io.github.dsheirer.buffer.FloatAveragingBuffer;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.spectrum.converter.ComplexDecibelConverter;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.jtransforms.fft.FloatFFT_1D;

/**
 * Calculates the carrier offset for a DMR (4-FSK) signal.
 *
 * DMR uses 4-level FSK (C4FM) with four symbol frequencies at ±648 Hz (inner, ±1 deviation)
 * and ±1944 Hz (outer, ±3 deviation) from the carrier center.
 *
 * The generic CarrierOffsetProcessor finds the single highest FFT peak, which on a 4-FSK
 * signal latches onto whichever symbol frequency has the most energy at that moment.
 * This produces wildly incorrect carrier offset estimates (tens of PPM of apparent error)
 * because it is measuring symbol deviation rather than carrier offset.
 *
 * This processor instead finds both outer symbol peaks (±1944 Hz) and computes their midpoint.
 * Because the outer symbols are symmetric around the carrier center by definition, their
 * midpoint is the true carrier center regardless of data content or symbol distribution.
 * The symbol deviation cancels out exactly, leaving only the true carrier frequency offset.
 *
 * Algorithm:
 * 1. Perform a 128-point FFT on each incoming sample buffer (once per second)
 * 2. Search for the negative outer symbol peak in the left search window
 * 3. Search for the positive outer symbol peak in the right search window
 * 4. Verify both peaks have sufficient SNR (>15 dB above the noise floor)
 * 5. Compute the midpoint of the two outer peaks — this is the carrier center bin
 * 6. carrier_offset = (midpoint - center_bin) * Hz_per_bin
 * 7. Average across 5 consecutive qualifying measurements, then across 10 such sequences
 *
 * Search windows are sized to:
 * - Include the outer symbol location across ±5 PPM of pre-correction tuner error
 * - Exclude the inner symbol locations (±648 Hz) to prevent inner/outer confusion
 *
 * At the standard channelizer output of 25 kHz sample rate with a 128-point FFT:
 *   - Resolution: ~195 Hz/bin
 *   - Outer symbols at ±~10 bins from center
 *   - Inner symbols at ±~3 bins from center
 *   - Search windows: [49, 58] (negative outer) and [67, 76] (positive outer)
 *   - Coverage: ±~5 PPM of pre-correction error at 150 MHz
 */
public class DMRCarrierOffsetProcessor
{
    private static final int FFT_BIN_SIZE = 128;
    private static final int MEASUREMENT_COUNT_THRESHOLD = 5;
    private static final long CALCULATION_TIME_INTERVAL_MS = 1000;
    private static final float CENTER_INDEX = FFT_BIN_SIZE / 2.0f - 1; // 63.0

    /**
     * DMR 4-FSK symbol frequencies relative to carrier center.
     * Outer symbols (±3 deviation): ±1944 Hz
     * Inner symbols (±1 deviation): ±648 Hz
     */
    private static final float DMR_OUTER_DEVIATION_HZ = 1944.0f;
    private static final float DMR_INNER_DEVIATION_HZ = 648.0f;

    /**
     * Minimum SNR required for each outer symbol peak before accepting the measurement.
     * Measured as dB above the average of the two noise floor samples flanking the peak.
     */
    private static final float MINIMUM_PEAK_SNR_DB = 12.0f;

    /**
     * Minimum confidence sequences required before PPM reporting is enabled.
     * Each sequence requires MEASUREMENT_COUNT_THRESHOLD consecutive qualifying FFT windows.
     * At 25 kHz / 128 samples per window = ~5ms per window, so:
     *   10 sequences * 5 windows = 50 windows ≈ 250ms of continuous qualifying signal.
     */
    private static final int CONFIDENCE_THRESHOLD = 10;

    private static final float[] WINDOW = WindowFactory.getWindow(WindowType.BLACKMAN_HARRIS_7, FFT_BIN_SIZE * 2);
    private static final FloatFFT_1D FFT = new FloatFFT_1D(FFT_BIN_SIZE);

    private final FloatAveragingBuffer mAveragingBuffer = new FloatAveragingBuffer(5);
    private final FloatAveragingBuffer mOffsetAverage = new FloatAveragingBuffer(10, 3);
    private final StandardDeviation mStandardDeviation = new StandardDeviation();

    // Search window bounds — recalculated on setSampleRate()
    private int mLeftWindowMin;
    private int mLeftWindowMax;
    private int mRightWindowMin;
    private int mRightWindowMax;

    private float mResolution;
    private float mLastCarrierOffset = Float.MAX_VALUE;
    private long mEstimatedOffset;
    private long mLastCalculationTimestamp = 0;
    private int mMeasurementCount = 0;
    private int mHighQualityMeasurementCount = 0;

    /**
     * Constructs an instance with default 25 kHz sample rate (standard channelizer output).
     */
    public DMRCarrierOffsetProcessor()
    {
        setSampleRate(25000.0);
    }

    /**
     * Updates the channel sample rate and recalculates search window bounds.
     *
     * The search windows are positioned to bracket the outer symbol frequencies (±1944 Hz)
     * while excluding the inner symbol frequencies (±648 Hz). If the sample rate is too
     * coarse for the windows to be non-overlapping (e.g. 50 kHz), the windows are set to
     * a minimal safe range and a warning is logged.
     *
     * @param sampleRate in Hertz
     */
    public void setSampleRate(double sampleRate)
    {
        mResolution = (float)(sampleRate / FFT_BIN_SIZE);

        float outerBins = DMR_OUTER_DEVIATION_HZ / mResolution;
        float innerBins = DMR_INNER_DEVIATION_HZ / mResolution;

        // Gap between inner and outer symbols in bins. We place the search window
        // in this gap, with the outer edge at the outer symbol location.
        // Allow ±4 bins of tuner PPM error tolerance on the outer side.
        int tolerance = 4;

        mLeftWindowMin  = Math.max(0,             (int)(CENTER_INDEX - outerBins) - tolerance);
        mLeftWindowMax  = Math.min(FFT_BIN_SIZE - 1, (int)(CENTER_INDEX - innerBins) - 2);
        mRightWindowMin = Math.max(0,             (int)(CENTER_INDEX + innerBins) + 2);
        mRightWindowMax = Math.min(FFT_BIN_SIZE - 1, (int)(CENTER_INDEX + outerBins) + tolerance);

        reset();
    }

    /**
     * Resets all state. Call when the tuner PPM is changed so the estimate rebuilds
     * from scratch with the corrected frequency.
     */
    public void reset()
    {
        mMeasurementCount = 0;
        mHighQualityMeasurementCount = 0;
        mLastCalculationTimestamp = 0;
        mEstimatedOffset = 0;
        mLastCarrierOffset = Float.MAX_VALUE;
        mAveragingBuffer.reset();
        mStandardDeviation.clear();
        mOffsetAverage.reset();
    }

    /**
     * The estimated carrier frequency offset from channel center, in Hertz.
     * Averaged across multiple high-quality measurement sequences.
     * Only meaningful after isConfident() returns true.
     *
     * @return estimated carrier offset in Hz
     */
    public long getEstimatedOffset()
    {
        return mEstimatedOffset;
    }

    /**
     * Indicates if both outer symbol peaks were detected in the most recently processed buffer.
     * Used for driving the channel spectral display carrier indicator.
     *
     * @return true if both outer symbols had sufficient SNR in the last FFT
     */
    public boolean hasCarrier()
    {
        return mLastCarrierOffset != Float.MAX_VALUE;
    }

    /**
     * Indicates if enough stable, high-quality measurements have accumulated to trust
     * the carrier offset estimate for tuner PPM correction.
     *
     * @return true if CONFIDENCE_THRESHOLD qualifying sequences have been measured
     */
    public boolean isConfident()
    {
        return mHighQualityMeasurementCount >= CONFIDENCE_THRESHOLD;
    }

    /**
     * Processes the complex sample buffer. Updates the carrier offset estimate once per second.
     *
     * @param samples complex sample buffer
     * @return true if the estimated carrier offset was updated this call
     */
    public boolean process(ComplexSamples samples)
    {
        if(mLastCalculationTimestamp + CALCULATION_TIME_INTERVAL_MS < samples.timestamp())
        {
            int bufferOffset = 0;

            while((bufferOffset + FFT_BIN_SIZE) <= samples.length())
            {
                mLastCarrierOffset = calculateCarrierOffset(samples, bufferOffset);

                if(mLastCarrierOffset == Float.MAX_VALUE)
                {
                    // No signal or insufficient SNR on one or both outer peaks — reset streak
                    mMeasurementCount = 0;
                    mStandardDeviation.clear();
                }
                else
                {
                    mMeasurementCount++;
                    mAveragingBuffer.add(mLastCarrierOffset);
                }

                if(mMeasurementCount >= MEASUREMENT_COUNT_THRESHOLD)
                {
                    // We have MEASUREMENT_COUNT_THRESHOLD consecutive qualifying measurements.
                    // Accept this sequence only if the standard deviation is tight — indicates
                    // the outer peak positions are stable, not jumping between symbols.
                    if(mStandardDeviation.getResult() < 5.0)
                    {
                        mOffsetAverage.add(mAveragingBuffer.getAverage());
                        mHighQualityMeasurementCount++;
                        mLastCalculationTimestamp = samples.timestamp();

                        // Exit the inner loop — one sequence per second is enough
                        bufferOffset = samples.length();
                    }

                    mMeasurementCount = 0;
                    mStandardDeviation.clear();
                }

                bufferOffset += FFT_BIN_SIZE;
            }

            mEstimatedOffset = (long)mOffsetAverage.getAverage();
            return true;
        }

        return false;
    }

    /**
     * Calculates the carrier offset for one 128-sample FFT window using the outer symbol midpoint method.
     *
     * Finds the peak in the left search window (negative outer symbol) and the peak in the right
     * search window (positive outer symbol). Their midpoint is the carrier center. If either peak
     * lacks sufficient SNR, returns Float.MAX_VALUE to indicate no valid measurement.
     *
     * @param complexSamples input samples
     * @param offset into the sample buffer
     * @return carrier offset in Hz, or Float.MAX_VALUE if measurement is invalid
     */
    private float calculateCarrierOffset(ComplexSamples complexSamples, int offset)
    {
        float[] samples = complexSamples.toInterleaved(offset, FFT_BIN_SIZE).samples();
        WindowFactory.apply(WINDOW, samples);
        FFT.complexForward(samples);
        float[] magnitudes = ComplexDecibelConverter.convert(samples);

        // Find the noise floor estimate: average of several bins well outside the signal
        float noiseFloor = estimateNoiseFloor(magnitudes);

        // Find outer symbol peaks in their respective search windows
        float leftPeakBin = findWindowPeak(magnitudes, mLeftWindowMin, mLeftWindowMax, noiseFloor);
        if(leftPeakBin == Float.MAX_VALUE)
        {
            return Float.MAX_VALUE;
        }

        float rightPeakBin = findWindowPeak(magnitudes, mRightWindowMin, mRightWindowMax, noiseFloor);
        if(rightPeakBin == Float.MAX_VALUE)
        {
            return Float.MAX_VALUE;
        }

        // Sanity check: the two outer peaks should be roughly symmetric about center.
        // If they're not (within 5 bins of each other's mirror), something is wrong.
        float expectedSeparation = 2.0f * (DMR_OUTER_DEVIATION_HZ / mResolution);
        float actualSeparation = rightPeakBin - leftPeakBin;
        if(Math.abs(actualSeparation - expectedSeparation) > 6.0f)
        {
            return Float.MAX_VALUE;
        }

        // Carrier center is the midpoint of the two outer symbol peaks
        float carrierCenterBin = (leftPeakBin + rightPeakBin) / 2.0f;
        float carrierOffsetHz = (carrierCenterBin - CENTER_INDEX) * mResolution;

        mStandardDeviation.increment(carrierCenterBin);
        return carrierOffsetHz;
    }

    /**
     * Finds the bin with the highest magnitude within the specified search window,
     * using sub-bin interpolation for better frequency resolution. Returns Float.MAX_VALUE
     * if the peak SNR is below MINIMUM_PEAK_SNR_DB.
     *
     * @param magnitudes FFT magnitude array in dB
     * @param windowMin  inclusive lower bound of search window
     * @param windowMax  inclusive upper bound of search window
     * @param noiseFloor estimated noise floor in dB
     * @return interpolated peak bin index, or Float.MAX_VALUE if SNR insufficient
     */
    private float findWindowPeak(float[] magnitudes, int windowMin, int windowMax, float noiseFloor)
    {
        float peakValue = -200.0f;
        int peakIndex = windowMin;

        for(int x = windowMin; x <= windowMax; x++)
        {
            if(magnitudes[x] > peakValue)
            {
                peakValue = magnitudes[x];
                peakIndex = x;
            }
        }

        // Check SNR against noise floor
        float snr = peakValue - noiseFloor;
        if(snr < MINIMUM_PEAK_SNR_DB)
        {
            return Float.MAX_VALUE;
        }

        // Sub-bin interpolation using parabolic fit on the peak and its two neighbors.
        // Improves frequency resolution beyond the bin width.
        if(peakIndex > 0 && peakIndex < FFT_BIN_SIZE - 1)
        {
            float left  = magnitudes[peakIndex - 1];
            float right = magnitudes[peakIndex + 1];
            float denom = 2.0f * (2.0f * peakValue - left - right);

            if(denom != 0.0f)
            {
                float delta = (right - left) / denom;
                // Clamp interpolation to ±0.5 bins to avoid wild excursions
                delta = Math.max(-0.5f, Math.min(0.5f, delta));
                return peakIndex + delta;
            }
        }

        return peakIndex;
    }

    /**
     * Estimates the noise floor by averaging the magnitude of bins well outside
     * the DMR signal band (near the edges of the FFT window).
     *
     * @param magnitudes FFT magnitude array in dB
     * @return estimated noise floor in dB
     */
    private float estimateNoiseFloor(float[] magnitudes)
    {
        // Use bins near the edges of the spectrum, well away from any DMR signal energy.
        // At 25 kHz / 128 bins, the edges represent ±12.5 kHz — safely outside DMR's ±1944 Hz.
        float sum = 0.0f;
        int count = 0;

        // Sample a few bins from each edge
        int edgeWidth = 8;
        for(int x = 2; x < 2 + edgeWidth; x++)
        {
            sum += magnitudes[x];
            count++;
        }
        for(int x = FFT_BIN_SIZE - 2 - edgeWidth; x < FFT_BIN_SIZE - 2; x++)
        {
            sum += magnitudes[x];
            count++;
        }

        return (count > 0) ? (sum / count) : -120.0f;
    }
}
