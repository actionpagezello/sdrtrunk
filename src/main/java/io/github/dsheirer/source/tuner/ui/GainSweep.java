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
 * Recommendation (see recommend()): settings that overloaded are out; the tuner's history at its own setting limits
 * how high it can go (ap-12, after ap-11 sent a Somerville RTL from 32.8 to 42.1 dB, where it then overloaded most of
 * the time); then the lowest setting whose strongest signals are within 1 dB of the best when the signals were
 * steady, or the setting where the noise floor has risen 3 dB above the tuner's own noise when they came and went.
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
     * @param signalSpreadDb how much the strongest signals differed between the setting's measurements, dB (NaN with
     * one measurement): a few dB for a steady signal such as a control channel, much more when transmissions come
     * and go
     */
    public record StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                             double clippedPercent, int overloadEvents, double signalSpreadDb)
    {
        /**
         * Constructs a result without a measure of how steady the signals were
         */
        public StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                          double clippedPercent, int overloadEvents)
        {
            this(index, label, noiseFloorDb, signalDb, peakDbfs, clippedPercent, overloadEvents, Double.NaN);
        }

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
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;

        for(double[] estimate : estimates)
        {
            double each = strongestSignals(estimate) - noiseFloor(estimate);
            low = Math.min(low, each);
            high = Math.max(high, each);
        }

        double spread = estimates.size() > 1 ? high - low : Double.NaN;
        return new StepResult(index, label, noise, signal, reading.peakDbfs(), reading.clippedPercent(),
            reading.overloadEvents(), spread);
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

    //ap-12 recommendation settings
    //History at the tuner's own setting needed before a higher setting is considered
    static final long HISTORY_MINIMUM_MS = 5 * 60 * 1000;
    //Headroom kept below full scale when a higher setting is allowed from the history's peak
    static final double HEADROOM_MARGIN_DB = 3.0;
    //Without gain values in dB, one setting higher is allowed only when the history's peak is at or below this
    static final double ONE_STEP_UP_PEAK_DBFS = -6.0;
    //A setting's signals are steady when its two measurements are within this
    static final double STEADY_SPREAD_DB = 3.0;
    //Share of settings that must be steady to follow the signals rather than the noise floor
    static final double STEADY_SHARE = 0.7;
    //Noise floor rise, above the tuner's own noise at its lowest settings, that marks the useful gain
    static final double NOISE_RISE_DB = 3.0;

    private static final java.util.regex.Pattern DB_LABEL = java.util.regex.Pattern.compile("^(-?\\d+(\\.\\d+)?) dB");

    /**
     * Gain of a setting in dB, read from its label ("28.0 dB" for an RTL-SDR), or NaN when the label has none (Airspy
     * and HydraSDR gain steps, RSP LNA states)
     */
    static double gainDb(String label)
    {
        java.util.regex.Matcher matcher = DB_LABEL.matcher(label != null ? label : "");
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : Double.NaN;
    }

    /**
     * Recommends a setting from the results alone, without the tuner's history (for tests)
     */
    public static Recommendation recommend(List<StepResult> results)
    {
        return recommend(results, -1, null);
    }

    /**
     * Recommends a setting.
     *
     * 1. Settings that overloaded during the check are out.
     * 2. The tuner's history at its own setting limits how high the recommendation can go, because a two-second
     *    measurement can miss a strong transmitter that keys up now and then.  If the tuner overloaded at its own
     *    setting during the history (up to 30 minutes), only lower settings are considered.  If it didn't, a higher
     *    setting is allowed only as far as the history's highest peak leaves room for (keeping 3 dB of headroom); with
     *    less than 5 minutes of history, nothing higher is allowed.
     * 3. If the strongest signals were steady through the check (a control channel), the lowest allowed setting whose
     *    signals are within 1 dB of the best.  Otherwise the signals say little, and the noise floor decides: the
     *    lowest allowed setting where the noise floor has risen 3 dB above the tuner's own noise at its lowest
     *    settings - past that, more gain mostly amplifies noise along with the signals.
     *
     * @param results of the check, lowest gain first
     * @param current index of the tuner's own setting, or -1 when unknown
     * @param history at the tuner's own setting, or null
     */
    public static Recommendation recommend(List<StepResult> results, int current, TunerSignalMonitor.History history)
    {
        if(results.isEmpty())
        {
            return new Recommendation(-1, "No settings were measured.");
        }

        List<StepResult> clean = new ArrayList<>();

        for(StepResult result : results)
        {
            if(!result.isOverloaded())
            {
                clean.add(result);
            }
        }

        if(clean.isEmpty())
        {
            return new Recommendation(results.get(0).index(), "Overload at every setting, even the lowest gain.  " +
                "Use the lowest gain, and look for a very strong nearby transmitter: a band-pass or notch filter " +
                "(or an attenuator) ahead of the tuner will help more than any gain setting.");
        }

        //Limit from the history at the tuner's own setting
        int maxIndex = Integer.MAX_VALUE;
        StringBuilder note = new StringBuilder();
        StepResult own = null;

        for(StepResult result : results)
        {
            if(result.index() == current)
            {
                own = result;
            }
        }

        if(current >= 0 && own != null)
        {
            boolean enough = history != null && history.coveredMs() >= HISTORY_MINIMUM_MS &&
                history.reading().samplesChecked() > 0;
            long minutes = history != null ? history.coveredMs() / 60000 : 0;

            if(enough && history.reading().isOverloaded())
            {
                maxIndex = current - 1;
                note.append("At your setting (").append(own.label()).append(") the tuner overloaded over the last ")
                    .append(minutes).append(" minutes (").append(String.format("%.3f", history.reading().clippedPercent()))
                    .append("% of samples at full scale");

                if(history.reading().overloadEvents() > 0)
                {
                    note.append(", ").append(history.reading().overloadEvents()).append(" overload events");
                }

                note.append("), so only lower settings are considered.  ");
            }
            else if(enough)
            {
                double peak = history.reading().peakDbfs();
                double ownDb = gainDb(own.label());

                if(!Double.isNaN(ownDb))
                {
                    double allowance = Math.max(0.0, -peak - HEADROOM_MARGIN_DB);
                    maxIndex = current;

                    for(StepResult result : results)
                    {
                        double db = gainDb(result.label());

                        if(!Double.isNaN(db) && db <= ownDb + allowance + 0.05)
                        {
                            maxIndex = Math.max(maxIndex, result.index());
                        }
                    }

                    note.append("At your setting the highest peak over the last ").append(minutes)
                        .append(" minutes was ").append(String.format("%.1f", peak)).append(" dBFS, which leaves room for ")
                        .append(String.format("%.1f", allowance)).append(" dB more gain at most.  ");
                }
                else
                {
                    maxIndex = peak <= ONE_STEP_UP_PEAK_DBFS ? current + 1 : current;
                    note.append("At your setting the highest peak over the last ").append(minutes)
                        .append(" minutes was ").append(String.format("%.1f", peak)).append(" dBFS, so ")
                        .append(maxIndex > current ? "at most one setting higher is considered.  " :
                            "no higher setting is considered.  ");
                }
            }
            else
            {
                maxIndex = current;
                note.append("There are only ").append(minutes).append(" minutes of history at your setting, so no " +
                    "higher setting is considered (a strong transmitter that keys up now and then can be missed in a " +
                    "check); run it again after 5 minutes or more at one setting.  ");
            }
        }

        List<StepResult> allowed = new ArrayList<>();

        for(StepResult result : clean)
        {
            if(result.index() <= maxIndex)
            {
                allowed.add(result);
            }
        }

        if(allowed.isEmpty())
        {
            return new Recommendation(results.get(0).index(), note + "Every lower setting overloaded during the " +
                "check too.  Use the lowest gain, and look for a very strong nearby transmitter: a band-pass or notch " +
                "filter (or an attenuator) ahead of the tuner will help more than any gain setting.");
        }

        //Steady signals?
        int measured = 0;
        int steady = 0;
        double best = Double.NEGATIVE_INFINITY;

        for(StepResult result : allowed)
        {
            if(!Double.isNaN(result.signalSpreadDb()))
            {
                measured++;

                if(result.signalSpreadDb() <= STEADY_SPREAD_DB)
                {
                    steady++;
                }
            }

            if(!Double.isNaN(result.signalDb()))
            {
                best = Math.max(best, result.signalDb());
            }
        }

        boolean followSignals = best >= MINIMUM_SIGNAL_DB && (measured == 0 || steady >= STEADY_SHARE * measured);

        if(followSignals)
        {
            for(StepResult result : allowed)
            {
                if(!Double.isNaN(result.signalDb()) && result.signalDb() >= best - SIGNAL_TOLERANCE_DB)
                {
                    StepResult highestClean = clean.get(clean.size() - 1);
                    String overload = highestClean.index() < results.get(results.size() - 1).index() ?
                        "  Overload starts above " + highestClean.label() + "." : "";
                    return new Recommendation(result.index(), note + "Recommended: " + result.label() +
                        ".  Signals are " + String.format("%.1f", result.signalDb()) + " dB above the noise here; " +
                        "more gain doesn't improve them (best " + String.format("%.1f", best) + " dB) and leaves " +
                        "less headroom." + overload);
                }
            }
        }

        //Noise floor rule
        double[] lowest = new double[Math.min(3, results.size())];

        for(int x = 0; x < lowest.length; x++)
        {
            lowest[x] = results.get(x).noiseFloorDb();
        }

        double ownNoise = noiseFloor(lowest);

        if(Double.isNaN(ownNoise))
        {
            StepResult highest = allowed.get(allowed.size() - 1);
            return new Recommendation(highest.index(), note + "Recommended: " + highest.label() + ", the highest " +
                "setting allowed (the noise floor couldn't be measured).");
        }

        for(StepResult result : allowed)
        {
            if(!Double.isNaN(result.noiseFloorDb()) && result.noiseFloorDb() >= ownNoise + NOISE_RISE_DB)
            {
                return new Recommendation(result.index(), note + "Recommended: " + result.label() + ".  The signals " +
                    "came and went during the check, so this follows the noise floor: here it is " +
                    String.format("%.1f", result.noiseFloorDb() - ownNoise) + " dB above the tuner's own noise, the " +
                    "point past which more gain mostly amplifies noise along with the signals.");
            }
        }

        StepResult highest = allowed.get(allowed.size() - 1);
        return new Recommendation(highest.index(), note + "Recommended: " + highest.label() + ", the highest " +
            "setting allowed.  The signals came and went during the check, and the noise floor didn't rise by " +
            NOISE_RISE_DB + " dB at any allowed setting, so the tuner's own noise still dominates there.");
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
