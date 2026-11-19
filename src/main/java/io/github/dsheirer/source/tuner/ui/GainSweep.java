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
package io.github.dsheirer.source.tuner.ui;

import io.github.dsheirer.source.tuner.TunerNoiseFloorEstimator;
import io.github.dsheirer.source.tuner.TunerSignalMonitor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-12): gain check.  Steps a tuner through its gain settings, measures each one and recommends one.
 *
 * Each setting is measured over about two seconds after a short settling time:
 * - noise floor: the median power of the tuner's usable bandwidth in 25 kHz bins (most bins hold no signal)
 * - strongest signals: the mean power of the strongest 1% of bins (at least 3), as dB above the noise floor
 * - samples at full scale and the peak sample level, from the tuner's signal monitor
 *
 * Recommendation: of the settings without overload, the lowest gain whose strongest signals are within 1 dB of the
 * best.  Below that setting more gain still improves the signals (the tuner's own noise dominates); above it the
 * signals don't improve, because the antenna noise is amplified with them, and there is less headroom left before
 * overload.
 */
public class GainSweep
{
    private static final Logger LOG = LoggerFactory.getLogger(GainSweep.class);
    static final long SETTLE_MS = 1500;
    static final int ESTIMATES_PER_STEP = 2;
    static final long STEP_TIMEOUT_MS = 8000;
    //Strongest signals within this of the best count as equally good
    static final double SIGNAL_TOLERANCE_DB = 1.0;
    //A setting whose strongest signals are less than this above the noise had nothing to measure
    static final double MINIMUM_SIGNAL_DB = 3.0;

    private final GainSteps mSteps;
    private final TunerNoiseFloorEstimator mEstimator;
    private final TunerSignalMonitor mMonitor;
    private final AtomicBoolean mCancelled = new AtomicBoolean();

    /**
     * Result of one gain setting
     * @param index of the setting
     * @param label of the setting
     * @param noiseFloorDb median bin power, dBFS per 25 kHz bin, or NaN when not measured
     * @param signalDb strongest signals above the noise floor, dB, or NaN when not measured
     * @param peakDbfs highest sample level, dBFS, or NaN
     * @param clippedPercent samples at full scale, percent
     * @param overloadEvents overloads reported by the tuner
     */
    public record StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                             double clippedPercent, int overloadEvents)
    {
        public boolean isOverloaded()
        {
            return TunerSignalMonitor.isReportable(clippedPercent) || overloadEvents > 0;
        }
    }

    /**
     * Recommendation
     * @param index of the recommended setting, or -1 for none
     * @param explanation for the user
     */
    public record Recommendation(int index, String explanation) {}

    /**
     * Listener for progress, called on the sweep thread
     */
    public interface Listener
    {
        void stepStarted(int step, int of, String label);

        void stepMeasured(StepResult result);
    }

    public GainSweep(GainSteps steps, TunerNoiseFloorEstimator estimator, TunerSignalMonitor monitor)
    {
        mSteps = steps;
        mEstimator = estimator;
        mMonitor = monitor;
    }

    /**
     * Stops the sweep after the current step
     */
    public void cancel()
    {
        mCancelled.set(true);
    }

    public boolean isCancelled()
    {
        return mCancelled.get();
    }

    /**
     * Runs the sweep on the calling thread (not the Swing event thread), then puts the original setting back.
     * @return results, one per measured setting, lowest gain first
     */
    public List<StepResult> run(Listener listener) throws Exception
    {
        int original = onSwing(mSteps::current);
        int count = onSwing(mSteps::count);
        List<StepResult> results = new ArrayList<>();
        mMonitor.setIntensive(true);

        try
        {
            for(int index = 0; index < count && !mCancelled.get(); index++)
            {
                String label = labelOf(index);
                listener.stepStarted(index + 1, count, label);
                final int step = index;
                runOnSwing(() -> mSteps.apply(step));
                StepResult result = measure(index, label);

                if(result != null)
                {
                    results.add(result);
                    listener.stepMeasured(result);
                }
            }
        }
        finally
        {
            mMonitor.setIntensive(false);
            runOnSwing(() -> mSteps.apply(original));
        }

        return results;
    }

    private String labelOf(int index) throws Exception
    {
        return onSwing(() -> mSteps.label(index));
    }

    private StepResult measure(int index, String label) throws InterruptedException
    {
        //Keep the band measurement running and let the gain change and the old measurement pass
        long start = System.currentTimeMillis();

        while(System.currentTimeMillis() - start < SETTLE_MS && !mCancelled.get())
        {
            mEstimator.getBandPowers();
            Thread.sleep(100);
        }

        long measureStart = System.currentTimeMillis();
        mMonitor.startWindow();
        List<double[]> estimates = new ArrayList<>();
        long lastTimestamp = measureStart;

        while(estimates.size() < ESTIMATES_PER_STEP && System.currentTimeMillis() - measureStart < STEP_TIMEOUT_MS &&
            !mCancelled.get())
        {
            TunerNoiseFloorEstimator.BandPowers powers = mEstimator.getBandPowers();

            if(powers != null && powers.timestamp() > lastTimestamp)
            {
                estimates.add(powers.binPowersDb());
                lastTimestamp = powers.timestamp();
            }

            Thread.sleep(100);
        }

        if(mCancelled.get())
        {
            return null;
        }

        TunerSignalMonitor.Reading reading = mMonitor.getWindowReading();
        double[] band = average(estimates);
        double noise = band != null ? noiseFloor(band) : Double.NaN;
        double signal = band != null ? strongestSignals(band) - noise : Double.NaN;
        return new StepResult(index, label, noise, signal, reading.peakDbfs(), reading.clippedPercent(),
            reading.overloadEvents());
    }

    /**
     * Averages bin powers (in dB) over the estimates, in power.  Null when there are none or their sizes differ.
     */
    static double[] average(List<double[]> estimates)
    {
        if(estimates.isEmpty())
        {
            return null;
        }

        int size = estimates.get(0).length;
        double[] sum = new double[size];

        for(double[] estimate : estimates)
        {
            if(estimate.length != size)
            {
                return null;
            }

            for(int x = 0; x < size; x++)
            {
                sum[x] += Math.pow(10.0, estimate[x] / 10.0);
            }
        }

        for(int x = 0; x < size; x++)
        {
            sum[x] = 10.0 * Math.log10(sum[x] / estimates.size());
        }

        return sum;
    }

    /**
     * Median bin power in dB
     */
    static double noiseFloor(double[] binPowersDb)
    {
        if(binPowersDb.length == 0)
        {
            return Double.NaN;
        }

        double[] sorted = binPowersDb.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    /**
     * Mean power, in dB, of the strongest 1% of bins (at least 3)
     */
    static double strongestSignals(double[] binPowersDb)
    {
        if(binPowersDb.length == 0)
        {
            return Double.NaN;
        }

        double[] sorted = binPowersDb.clone();
        Arrays.sort(sorted);
        int count = Math.min(sorted.length, Math.max(3, sorted.length / 100));
        double sum = 0;

        for(int x = sorted.length - count; x < sorted.length; x++)
        {
            sum += Math.pow(10.0, sorted[x] / 10.0);
        }

        return 10.0 * Math.log10(sum / count);
    }

    /**
     * Recommends a setting from the results
     */
    public static Recommendation recommend(List<StepResult> results)
    {
        if(results.isEmpty())
        {
            return new Recommendation(-1, "No settings were measured.");
        }

        double best = Double.NEGATIVE_INFINITY;
        boolean anyClean = false;

        for(StepResult result : results)
        {
            if(!result.isOverloaded())
            {
                anyClean = true;

                if(!Double.isNaN(result.signalDb()))
                {
                    best = Math.max(best, result.signalDb());
                }
            }
        }

        if(!anyClean)
        {
            return new Recommendation(results.get(0).index(), "Overload at every setting, even the lowest gain.  " +
                "Use the lowest gain, and look for a very strong nearby transmitter: a band-pass or notch filter " +
                "(or an attenuator) ahead of the tuner will help more than any gain setting.");
        }

        if(best == Double.NEGATIVE_INFINITY || best < MINIMUM_SIGNAL_DB)
        {
            return new Recommendation(-1, "No signal strong enough to measure was on the air during the check.  " +
                "Run it again while a busy channel (a control channel is best) is on this tuner.");
        }

        StepResult highestClean = null;

        for(StepResult result : results)
        {
            if(!result.isOverloaded())
            {
                highestClean = result;
            }
        }

        for(StepResult result : results)
        {
            if(!result.isOverloaded() && !Double.isNaN(result.signalDb()) &&
                result.signalDb() >= best - SIGNAL_TOLERANCE_DB)
            {
                StringBuilder sb = new StringBuilder();
                sb.append("Recommended: ").append(result.label()).append(".  Signals are ")
                    .append(String.format("%.1f", result.signalDb()))
                    .append(" dB above the noise here; more gain doesn't improve them (best ")
                    .append(String.format("%.1f", best)).append(" dB) and leaves less headroom.");

                if(highestClean != null && highestClean.index() < results.get(results.size() - 1).index())
                {
                    sb.append("  Overload starts above ").append(highestClean.label()).append('.');
                }

                return new Recommendation(result.index(), sb.toString());
            }
        }

        return new Recommendation(-1, "No recommendation.");
    }

    private static <T> T onSwing(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if(SwingUtilities.isEventDispatchThread())
        {
            return callable.call();
        }

        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try
            {
                result[0] = callable.call();
            }
            catch(Exception e)
            {
                error[0] = e;
            }
        });

        if(error[0] != null)
        {
            throw error[0];
        }

        @SuppressWarnings("unchecked")
        T value = (T)result[0];
        return value;
    }

    private static void runOnSwing(Runnable runnable) throws Exception
    {
        onSwing(() -> {
            runnable.run();
            return null;
        });
    }
}
