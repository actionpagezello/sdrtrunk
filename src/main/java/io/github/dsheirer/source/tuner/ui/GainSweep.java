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
 * the time); then, with a control channel on the tuner, the lowest setting where its signal-to-noise ratio is within
 * 1 dB of the best (ap-13), and without one the setting where the noise floor has risen 3 dB above the tuner's own
 * noise.  ap-16: the control channel rule never goes below the noise floor setting, so the weaker channels on the
 * tuner keep their gain, and overload reported only by the tuner lowers the gain by two settings at most.
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
    /**
     * ap-16: when the tuner itself reported overload at its setting but no samples reached full scale, the
     * recommendation goes down at most this many settings.  ap-15 sent a Daly RSP1B from LNA 0 to LNA 9 on 10 such
     * reports, and every weak channel on it went silent.
     */
    static final int EVENTS_ONLY_MAX_STEPS_DOWN = 2;

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
     * @param referenceSnrDb ap-13: signal-to-noise ratio of the tuner's control channel at this setting, dB, or NaN
     * when the tuner has no control channel on the air continuously
     */
    public record StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                             double clippedPercent, int overloadEvents, double signalSpreadDb, double referenceSnrDb)
    {
        /**
         * Constructs a result without a control channel measurement
         */
        public StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                          double clippedPercent, int overloadEvents, double signalSpreadDb)
        {
            this(index, label, noiseFloorDb, signalDb, peakDbfs, clippedPercent, overloadEvents, signalSpreadDb,
                Double.NaN);
        }

        /**
         * Constructs a result without a measure of how steady the signals were
         */
        public StepResult(int index, String label, double noiseFloorDb, double signalDb, double peakDbfs,
                          double clippedPercent, int overloadEvents)
        {
            this(index, label, noiseFloorDb, signalDb, peakDbfs, clippedPercent, overloadEvents, Double.NaN,
                Double.NaN);
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

        /**
         * ap-13: the control channel the check measures against, or null for none (the noise floor decides)
         */
        default void referenceChosen(GainCheckReference.Candidate reference)
        {
        }
    }

    //ap-13: control channel selection, at the tuner's own setting before the check starts
    static final int REFERENCE_ESTIMATES = 4;
    static final long REFERENCE_TIMEOUT_MS = 8000;
    //A control channel is on the air the whole time: at least this far above the noise in every measurement ...
    static final double REFERENCE_MINIMUM_SNR_DB = 10.0;
    //... and steady to within this
    static final double REFERENCE_MAXIMUM_SPREAD_DB = 3.0;

    private final List<GainCheckReference.Candidate> mCandidates;
    private GainCheckReference.Candidate mReference;

    public GainSweep(GainSteps steps, TunerNoiseFloorEstimator estimator, TunerSignalMonitor monitor)
    {
        this(steps, estimator, monitor, List.of());
    }

    /**
     * Constructs an instance
     * @param candidates ap-13: possible control channels on the tuner (GainCheckReference.find())
     */
    public GainSweep(GainSteps steps, TunerNoiseFloorEstimator estimator, TunerSignalMonitor monitor,
                     List<GainCheckReference.Candidate> candidates)
    {
        mSteps = steps;
        mEstimator = estimator;
        mMonitor = monitor;
        mCandidates = candidates;
    }

    /**
     * ap-13: the control channel measured against, or null
     */
    public GainCheckReference.Candidate getReference()
    {
        return mReference;
    }

    /**
     * ap-13: picks the control channel to measure against: of the candidates, the one that was at least 10 dB above
     * the noise in every one of 4 measurements and steady to within 3 dB, the strongest if there are several.  A
     * conventional P25 or DMR channel, or a control channel too weak or fading, isn't picked.
     */
    private GainCheckReference.Candidate chooseReference() throws InterruptedException
    {
        if(mCandidates.isEmpty())
        {
            return null;
        }

        List<List<Double>> readings = new ArrayList<>();

        for(int x = 0; x < mCandidates.size(); x++)
        {
            readings.add(new ArrayList<>());
        }

        long start = System.currentTimeMillis();
        long lastTimestamp = 0;
        int measured = 0;

        while(measured < REFERENCE_ESTIMATES && System.currentTimeMillis() - start < REFERENCE_TIMEOUT_MS &&
            !mCancelled.get())
        {
            mEstimator.getBandPowers();
            TunerNoiseFloorEstimator.ChannelSnr first = null;

            for(int x = 0; x < mCandidates.size(); x++)
            {
                GainCheckReference.Candidate candidate = mCandidates.get(x);
                TunerNoiseFloorEstimator.ChannelSnr snr = mEstimator.getChannelSnr(candidate.frequency(),
                    candidate.bandwidth());

                if(snr != null && snr.timestamp() > lastTimestamp)
                {
                    readings.get(x).add(snr.snrDb());
                    first = snr;
                }
            }

            if(first != null)
            {
                lastTimestamp = first.timestamp();
                measured++;
            }

            Thread.sleep(100);
        }

        GainCheckReference.Candidate best = null;
        double bestMinimum = Double.NEGATIVE_INFINITY;

        for(int x = 0; x < mCandidates.size(); x++)
        {
            List<Double> values = readings.get(x);

            if(values.size() < REFERENCE_ESTIMATES)
            {
                continue;
            }

            double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
            double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);

            if(minimum >= REFERENCE_MINIMUM_SNR_DB && maximum - minimum <= REFERENCE_MAXIMUM_SPREAD_DB &&
                minimum > bestMinimum)
            {
                best = mCandidates.get(x);
                bestMinimum = minimum;
            }
        }

        return best;
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
     * Runs the sweep on the calling thread (not the JavaFX application thread), then puts the original setting back.
     * @return results, one per measured setting, lowest gain first
     */
    public List<StepResult> run(Listener listener) throws Exception
    {
        int original = onUi(mSteps::current);
        int count = onUi(mSteps::count);
        List<StepResult> results = new ArrayList<>();
        //ap-13: the control channel, measured at the tuner's own setting before anything changes
        mReference = chooseReference();
        listener.referenceChosen(mReference);
        mMonitor.setIntensive(true);

        try
        {
            for(int index = 0; index < count && !mCancelled.get(); index++)
            {
                String label = labelOf(index);
                listener.stepStarted(index + 1, count, label);
                final int step = index;
                runOnUi(() -> mSteps.apply(step));
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
            runOnUi(() -> mSteps.apply(original));
        }

        return results;
    }

    private String labelOf(int index) throws Exception
    {
        return onUi(() -> mSteps.label(index));
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
        List<Double> referenceSnr = new ArrayList<>();
        long lastTimestamp = measureStart;

        while(estimates.size() < ESTIMATES_PER_STEP && System.currentTimeMillis() - measureStart < STEP_TIMEOUT_MS &&
            !mCancelled.get())
        {
            TunerNoiseFloorEstimator.BandPowers powers = mEstimator.getBandPowers();

            if(powers != null && powers.timestamp() > lastTimestamp)
            {
                estimates.add(powers.binPowersDb());
                lastTimestamp = powers.timestamp();
                GainCheckReference.Candidate reference = mReference;

                if(reference != null)
                {
                    TunerNoiseFloorEstimator.ChannelSnr snr = mEstimator.getChannelSnr(reference.frequency(),
                        reference.bandwidth());

                    if(snr != null)
                    {
                        referenceSnr.add(snr.snrDb());
                    }
                }
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
        double reference = Double.NaN;

        if(!referenceSnr.isEmpty())
        {
            double sum = 0;

            for(double value : referenceSnr)
            {
                sum += Math.pow(10.0, value / 10.0);
            }

            reference = 10.0 * Math.log10(sum / referenceSnr.size());
        }

        return new StepResult(index, label, noise, signal, reading.peakDbfs(), reading.clippedPercent(),
            reading.overloadEvents(), spread, reference);
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
        return recommend(results, -1, null, null);
    }

    /**
     * Recommends a setting without a control channel
     */
    public static Recommendation recommend(List<StepResult> results, int current, TunerSignalMonitor.History history)
    {
        return recommend(results, current, history, null);
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
     *    ap-16: when the tuner reported overload but no samples reached full scale, at most two settings lower.
     * 3. The noise floor gives the lowest useful setting: the lowest allowed setting where the noise floor has risen
     *    3 dB above the tuner's own noise at its lowest settings - below that the tuner's own noise hides weak signals,
     *    and past it more gain mostly amplifies noise along with the signals.
     * 4. ap-13: with a control channel on the tuner (on the air continuously), the lowest allowed setting where its
     *    signal-to-noise ratio is within 1 dB of the best - ap-16: but not below the noise floor setting, since the
     *    other channels on the tuner may be much weaker than the control channel.
     *
     * @param results of the check, lowest gain first
     * @param current index of the tuner's own setting, or -1 when unknown
     * @param history at the tuner's own setting, or null
     * @param referenceName name of the control channel measured against, or null for none
     */
    public static Recommendation recommend(List<StepResult> results, int current, TunerSignalMonitor.History history,
                                           String referenceName)
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
        int minIndex = Integer.MIN_VALUE;
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

                note.append("), so only lower settings are considered");

                //ap-16: overload reported only by the tuner (no samples at full scale) is mild: at most two lower
                if(!TunerSignalMonitor.isReportable(history.reading().clippedPercent()))
                {
                    int position = results.indexOf(own);
                    minIndex = results.get(Math.max(0, position - EVENTS_ONLY_MAX_STEPS_DOWN)).index();
                    note.append(", and since only the tuner reported it (no samples reached full scale), at most ")
                        .append(EVENTS_ONLY_MAX_STEPS_DOWN).append(" settings lower");
                }

                note.append(".  ");
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
            if(result.index() <= maxIndex && result.index() >= minIndex)
            {
                allowed.add(result);
            }
        }

        if(allowed.isEmpty())
        {
            if(minIndex != Integer.MIN_VALUE)
            {
                //ap-16: the settings within reach overloaded during the check; stay at the lowest of them
                StepResult lowestInReach = null;

                for(StepResult result : results)
                {
                    if(result.index() >= minIndex && lowestInReach == null)
                    {
                        lowestInReach = result;
                    }
                }

                if(lowestInReach != null)
                {
                    return new Recommendation(lowestInReach.index(), note + "The settings within reach overloaded " +
                        "during the check too.  Recommended: " + lowestInReach.label() + ".  A strong nearby " +
                        "transmitter is the likely cause: a band-pass or notch filter (or an attenuator) ahead of the " +
                        "tuner will help more than lowering the gain further, which also loses the weaker channels.");
                }
            }

            return new Recommendation(results.get(0).index(), note + "Every lower setting overloaded during the " +
                "check too.  Use the lowest gain, and look for a very strong nearby transmitter: a band-pass or notch " +
                "filter (or an attenuator) ahead of the tuner will help more than any gain setting.");
        }

        //Noise floor rule: the lowest useful setting
        double[] lowest = new double[Math.min(3, results.size())];

        for(int x = 0; x < lowest.length; x++)
        {
            lowest[x] = results.get(x).noiseFloorDb();
        }

        double ownNoise = noiseFloor(lowest);
        StepResult noiseChoice = null;
        String noiseReason;

        if(Double.isNaN(ownNoise))
        {
            noiseChoice = allowed.get(allowed.size() - 1);
            noiseReason = "the highest setting allowed (the noise floor couldn't be measured)";
        }
        else
        {
            for(StepResult result : allowed)
            {
                if(noiseChoice == null && !Double.isNaN(result.noiseFloorDb()) &&
                    result.noiseFloorDb() >= ownNoise + NOISE_RISE_DB)
                {
                    noiseChoice = result;
                }
            }

            if(noiseChoice != null)
            {
                noiseReason = "here the noise floor is " +
                    String.format("%.1f", noiseChoice.noiseFloorDb() - ownNoise) + " dB above the tuner's own noise, " +
                    "the point past which more gain mostly amplifies noise along with the signals";
            }
            else
            {
                noiseChoice = allowed.get(allowed.size() - 1);
                noiseReason = "the highest setting allowed: the noise floor didn't rise by " + NOISE_RISE_DB +
                    " dB at any allowed setting, so the tuner's own noise still dominates there";
            }
        }

        //ap-13: control channel rule - its signal-to-noise ratio at each allowed setting
        double best = Double.NEGATIVE_INFINITY;
        int measured = 0;

        for(StepResult result : allowed)
        {
            if(!Double.isNaN(result.referenceSnrDb()))
            {
                measured++;
                best = Math.max(best, result.referenceSnrDb());
            }
        }

        if(referenceName != null && measured == allowed.size() && best >= MINIMUM_SIGNAL_DB)
        {
            for(StepResult result : allowed)
            {
                if(result.referenceSnrDb() >= best - SIGNAL_TOLERANCE_DB)
                {
                    StepResult highestClean = clean.get(clean.size() - 1);
                    String overload = highestClean.index() < results.get(results.size() - 1).index() ?
                        "  Overload starts above " + highestClean.label() + "." : "";

                    //ap-16: not below the noise floor setting - the other channels may be much weaker
                    if(result.index() < noiseChoice.index())
                    {
                        return new Recommendation(noiseChoice.index(), note + "Recommended: " + noiseChoice.label() +
                            ".  The control channel " + referenceName + " is already " +
                            String.format("%.1f", result.referenceSnrDb()) + " dB above the noise at " +
                            result.label() + ", but weaker channels on this tuner need more gain than that: at " +
                            noiseChoice.label() + " " + noiseReason + "." + overload);
                    }

                    return new Recommendation(result.index(), note + "Recommended: " + result.label() +
                        ".  Measured on the control channel " + referenceName + ": it is " +
                        String.format("%.1f", result.referenceSnrDb()) + " dB above the noise here, and more gain " +
                        "doesn't improve it (best " + String.format("%.1f", best) + " dB) and leaves less headroom." +
                        overload);
                }
            }
        }

        String why = referenceName == null ? "This tuner has no control channel on the air continuously, so this " +
            "follows the noise floor" : "The control channel " + referenceName + " couldn't be measured at every " +
            "setting, so this follows the noise floor";

        return new Recommendation(noiseChoice.index(), note + "Recommended: " + noiseChoice.label() + ".  " + why +
            ": " + noiseReason + ".");
    }

    /**
     * Runs a call on the JavaFX application thread (the tuner editors' controls are JavaFX since ap-14) and waits for
     * its result.
     */
    private static <T> T onUi(java.util.concurrent.Callable<T> callable) throws Exception
    {
        if(javafx.application.Platform.isFxApplicationThread())
        {
            return callable.call();
        }

        java.util.concurrent.CompletableFuture<T> future = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try
            {
                future.complete(callable.call());
            }
            catch(Throwable t)
            {
                future.completeExceptionally(t);
            }
        });

        try
        {
            return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch(java.util.concurrent.ExecutionException e)
        {
            throw e.getCause() instanceof Exception exception ? exception : e;
        }
    }

    private static void runOnUi(Runnable runnable) throws Exception
    {
        onUi(() -> {
            runnable.run();
            return null;
        });
    }
}
