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

import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.source.tuner.TunerNoiseFloorEstimator;
import io.github.dsheirer.source.tuner.TunerSignalMonitor;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-12): gain check measurements and recommendation
 */
public class GainSweepTest
{
    private static final String[][] SUMMIT = {
        {"1", "-83.3", "31.9", "-37.4", "0.000"},
        {"2", "-83.3", "34.8", "-36.9", "0.000"},
        {"3", "-83.0", "31.6", "-39.2", "0.000"},
        {"4", "-83.0", "35.1", "-37.4", "0.000"},
        {"5", "-82.7", "37.7", "-35.5", "0.000"},
        {"6", "-81.6", "38.9", "-33.9", "0.000"},
        {"7", "-79.4", "44.1", "-20.5", "0.000"},
        {"8", "-78.9", "53.5", "-13.3", "0.000"},
        {"9", "-78.5", "57.3", "-8.0", "0.000"},
        {"10", "-74.7", "58.4", "-3.6", "0.000"},
        {"11", "-54.7", "42.3", "-0.6", "0.000"},
        {"12", "-49.7", "40.4", "-0.5", "0.000"},
        {"13", "-54.3", "40.7", "-1.1", "0.000"},
        {"14", "-55.2", "48.2", "1.2", "0.000"},
        {"15", "-53.9", "46.9", "1.2", "0.001"},
        {"16", "-51.4", "44.3", "-1.0", "0.000"},
        {"17", "-49.8", "42.8", "-0.3", "0.000"},
        {"18", "-45.5", "37.1", "0.7", "0.003"},
        {"19", "-45.9", "37.2", "1.8", "2.691"},
        {"20", "-41.0", "31.0", "1.8", "3.274"},
        {"21", "-44.4", "37.4", "1.1", "0.004"},
        {"22", "-50.5", "43.5", "1.2", "0.000"},
    };

    private static final String[][] SOM_65089485 = {
        {"0.0 dB", "-63.6", "0.6", "-33.8", "0.000"},
        {"0.9 dB", "-63.6", "0.6", "-33.8", "0.000"},
        {"1.4 dB", "-63.4", "0.5", "-33.8", "0.000"},
        {"2.7 dB", "-63.5", "0.5", "-33.8", "0.000"},
        {"3.7 dB", "-63.5", "0.9", "-33.8", "0.000"},
        {"7.7 dB", "-63.4", "5.8", "-33.8", "0.000"},
        {"8.7 dB", "-63.2", "0.7", "-33.8", "0.000"},
        {"12.5 dB", "-63.2", "1.0", "-33.8", "0.000"},
        {"14.4 dB", "-63.1", "0.9", "-33.8", "0.000"},
        {"15.7 dB", "-63.0", "1.4", "-33.8", "0.000"},
        {"16.6 dB", "-62.7", "1.4", "-31.5", "0.000"},
        {"19.7 dB", "-62.6", "3.2", "-31.0", "0.000"},
        {"20.7 dB", "-62.0", "7.0", "-28.9", "0.000"},
        {"22.9 dB", "-61.4", "8.8", "-27.5", "0.000"},
        {"25.4 dB", "-61.0", "9.8", "-28.9", "0.000"},
        {"28.0 dB", "-59.1", "10.8", "-24.8", "0.000"},
        {"29.7 dB", "-58.5", "12.8", "-23.7", "0.000"},
        {"32.8 dB", "-55.1", "9.9", "-20.1", "0.000"},
        {"33.8 dB", "-56.4", "13.4", "-21.6", "0.000"},
        {"36.4 dB", "-54.4", "13.9", "-17.7", "0.000"},
        {"37.2 dB", "-53.3", "14.3", "-16.4", "0.000"},
        {"38.6 dB", "-49.6", "11.6", "-10.9", "0.000"},
        {"40.2 dB", "-45.6", "9.6", "-8.8", "0.000"},
        {"42.1 dB", "-43.3", "21.5", "-5.4", "0.000"},
        {"43.4 dB", "-42.5", "18.0", "-4.9", "0.000"},
        {"43.9 dB", "-34.0", "4.3", "-0.4", "0.000"},
        {"44.5 dB", "-34.5", "3.8", "-0.8", "0.000"},
        {"48.0 dB", "-28.6", "3.6", "-0.0", "0.081"},
        {"49.6 dB", "-28.8", "3.7", "-0.0", "0.075"},
    };

    private static final String[][] SOM_73396553 = {
        {"0.0 dB", "-63.5", "14.4", "-33.9", "0.000"},
        {"0.9 dB", "-63.4", "8.8", "-31.5", "0.000"},
        {"1.4 dB", "-63.4", "10.8", "-31.0", "0.000"},
        {"2.7 dB", "-63.4", "22.7", "-20.9", "0.000"},
        {"3.7 dB", "-63.3", "22.3", "-21.6", "0.000"},
        {"7.7 dB", "-63.4", "15.9", "-28.9", "0.000"},
        {"8.7 dB", "-63.0", "27.5", "-15.9", "0.000"},
        {"12.5 dB", "-63.1", "29.3", "-14.0", "0.000"},
        {"14.4 dB", "-63.0", "30.1", "-23.5", "0.000"},
        {"15.7 dB", "-62.9", "40.0", "-5.0", "0.000"},
        {"16.6 dB", "-62.7", "46.9", "-3.6", "0.000"},
        {"19.7 dB", "-62.4", "48.9", "-1.1", "0.000"},
        {"20.7 dB", "-41.5", "33.6", "-0.0", "46.312"},
        {"22.9 dB", "-39.5", "30.3", "-0.0", "50.736"},
        {"25.4 dB", "-41.1", "33.9", "-0.0", "34.341"},
        {"28.0 dB", "-59.9", "39.4", "-11.2", "0.000"},
        {"29.7 dB", "-53.4", "37.1", "-0.0", "6.133"},
        {"32.8 dB", "-47.0", "32.8", "-0.0", "5.499"},
        {"33.8 dB", "-55.0", "40.9", "-5.4", "0.000"},
        {"36.4 dB", "-51.3", "21.5", "-10.9", "0.000"},
        {"37.2 dB", "-49.5", "17.2", "-13.1", "0.000"},
        {"38.6 dB", "-32.2", "25.4", "-0.0", "67.268"},
        {"40.2 dB", "-35.0", "28.0", "-0.0", "44.685"},
        {"42.1 dB", "-43.8", "27.9", "-7.9", "0.000"},
        {"43.4 dB", "-42.4", "27.8", "-6.8", "0.000"},
        {"43.9 dB", "-37.5", "30.2", "-0.0", "59.263"},
        {"44.5 dB", "-37.1", "31.1", "-0.0", "60.436"},
        {"48.0 dB", "-33.1", "26.6", "-0.0", "66.170"},
        {"49.6 dB", "-30.2", "23.6", "-0.0", "44.763"},
    };

    /**
     * Results from a Gain Check table in the ap-11 logs, with the signal spread the check didn't measure then
     */
    private static List<GainSweep.StepResult> table(String[][] rows, double spread)
    {
        List<GainSweep.StepResult> results = new java.util.ArrayList<>();

        for(int x = 0; x < rows.length; x++)
        {
            results.add(new GainSweep.StepResult(x, rows[x][0], Double.parseDouble(rows[x][1]),
                Double.parseDouble(rows[x][2]), Double.parseDouble(rows[x][3]), Double.parseDouble(rows[x][4]), 0,
                spread));
        }

        return results;
    }

    private static TunerSignalMonitor.History history(long minutes, double clippedPercent, double peakDbfs, int events)
    {
        return new TunerSignalMonitor.History(new TunerSignalMonitor.Reading(1_000_000, clippedPercent, peakDbfs,
            events), minutes * 60_000);
    }

    private static int index(String[][] rows, String label)
    {
        for(int x = 0; x < rows.length; x++)
        {
            if(rows[x][0].equals(label))
            {
                return x;
            }
        }

        throw new IllegalArgumentException(label);
    }

    @Test
    public void summit800AirspyStaysAt10()
    {
        //2026-11-19 14:53: control channel on the tuner (steady), at 10 for 30 minutes with peaks near -0.3 dBFS
        GainSweep.Recommendation recommendation = GainSweep.recommend(table(SUMMIT, 1.0), index(SUMMIT, "10"),
            history(30, 0.0, -0.3, 0));
        assertEquals("10", SUMMIT[recommendation.index()][0], recommendation.explanation());
    }

    @Test
    public void somervilleTunerIsNotSentUpTo42dB()
    {
        //2026-11-19 15:06: ap-11 recommended 42.1 dB from 32.8; the tuner then overloaded 4-50% of the time.  Traffic
        //came and went (unsteady); at 32.8 for 5 minutes, peak -3.4 dBFS, no overload
        int own = index(SOM_65089485, "32.8 dB");
        GainSweep.Recommendation recommendation = GainSweep.recommend(table(SOM_65089485, 10.0), own,
            history(5, 0.0, -3.4, 0));
        assertEquals("28.0 dB", SOM_65089485[recommendation.index()][0], recommendation.explanation());

        //Too little history: nothing above 32.8 either
        recommendation = GainSweep.recommend(table(SOM_65089485, 10.0), own, history(2, 0.0, -3.4, 0));
        assertTrue(recommendation.index() <= own, recommendation.explanation());
        assertTrue(recommendation.explanation().contains("minutes of history"), recommendation.explanation());
    }

    @Test
    public void somervilleTunerOverloadedAtItsSettingOnlyGoesDown()
    {
        //2026-11-19 15:01: ap-11 recommended 19.7 dB from 15.7; with overload at 15.7 in the history only lower
        //settings are allowed
        int own = index(SOM_73396553, "15.7 dB");
        GainSweep.Recommendation recommendation = GainSweep.recommend(table(SOM_73396553, 10.0), own,
            history(20, 0.15, 0.0, 0));
        assertTrue(recommendation.index() < own, recommendation.explanation());
        assertTrue(recommendation.explanation().contains("only lower settings"), recommendation.explanation());
    }

    @Test
    public void rspOverloadEventsInTheHistoryOnlyGoDown()
    {
        List<GainSweep.StepResult> results = List.of(new GainSweep.StepResult(0, "LNA 6", -80, 20, -30, 0, 0, 1),
            new GainSweep.StepResult(1, "LNA 5", -78, 25, -25, 0, 0, 1),
            new GainSweep.StepResult(2, "LNA 4", -75, 26, -20, 0, 0, 1));
        GainSweep.Recommendation recommendation = GainSweep.recommend(results, 2, history(30, 0.0, -6.0, 2772));
        assertTrue(recommendation.index() < 2, recommendation.explanation());
    }

    @Test
    public void gainInDbIsReadFromRtlLabelsOnly()
    {
        assertEquals(28.0, GainSweep.gainDb("28.0 dB"), 1e-9);
        assertTrue(Double.isNaN(GainSweep.gainDb("10")));
        assertTrue(Double.isNaN(GainSweep.gainDb("LNA 4")));
    }

    private static GainSweep.StepResult step(int index, double noise, double signal, double clipped)
    {
        return new GainSweep.StepResult(index, String.valueOf(index), noise, signal, -10, clipped, 0);
    }

    @Test
    public void recommendsTheLowestGainWithinOneDbOfTheBestSignals()
    {
        //Signals improve while the tuner's own noise dominates, level off, then overload sets in
        List<GainSweep.StepResult> results = List.of(
            step(0, -80, 10, 0), step(1, -78, 16, 0), step(2, -74, 21.5, 0), step(3, -70, 22.0, 0),
            step(4, -66, 22.2, 0), step(5, -60, 19, 0.2));
        GainSweep.Recommendation recommendation = GainSweep.recommend(results);
        assertEquals(2, recommendation.index(), recommendation.explanation());
        assertTrue(recommendation.explanation().contains("Overload starts above 4"), recommendation.explanation());
    }

    @Test
    public void overloadedSettingsAreNeverRecommended()
    {
        List<GainSweep.StepResult> results = List.of(step(0, -80, 10, 0), step(1, -70, 30, 0.05));
        assertEquals(0, GainSweep.recommend(results).index());
    }

    @Test
    public void overloadEverywhereRecommendsTheLowestGainAndAFilter()
    {
        GainSweep.Recommendation recommendation = GainSweep.recommend(List.of(step(0, -50, 20, 1), step(1, -40, 15, 5)));
        assertEquals(0, recommendation.index());
        assertTrue(recommendation.explanation().contains("filter"));
    }

    @Test
    public void withoutSignalsToMeasureTheNoiseFloorDecides()
    {
        //ap-12: no strong signals - the setting where the noise floor has risen 3 dB above the tuner's own noise
        GainSweep.Recommendation recommendation = GainSweep.recommend(List.of(step(0, -80, 1, 0), step(1, -80, 1, 0),
            step(2, -79.5, 1.5, 0), step(3, -77, 1.5, 0), step(4, -70, 1.5, 0)));
        assertEquals(3, recommendation.index(), recommendation.explanation());
        assertTrue(recommendation.explanation().contains("noise floor"));
    }

    @Test
    public void noiseFloorIsTheMedianAndSignalsTheStrongestBins()
    {
        double[] band = new double[300];
        java.util.Arrays.fill(band, -90);
        band[10] = -40;
        band[20] = -40;
        band[30] = -40;
        assertEquals(-90, GainSweep.noiseFloor(band), 1e-9);
        assertEquals(-40, GainSweep.strongestSignals(band), 1e-9);
    }

    /**
     * End to end on a simulated tuner: an 8-bit-like ADC with its own fixed noise, an antenna noise floor and a signal
     * that both scale with the gain, and clipping at full scale.  The best setting is the one where the antenna noise
     * just rises above the ADC noise, before the signal clips.
     */
    @Test
    public void sweepOnASimulatedTunerFindsTheSettingBeforeOverload() throws Exception
    {
        double[] gains = {0.002, 0.02, 0.2, 2.0};
        AtomicInteger setting = new AtomicInteger(0);
        TunerNoiseFloorEstimator estimator = new TunerNoiseFloorEstimator(() -> 100_000_000L, () -> 2_400_000.0,
            () -> 2_000_000);
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "simulated");
        AtomicBoolean running = new AtomicBoolean(true);

        Thread generator = new Thread(() -> {
            Random random = new Random(1);
            int length = 32768;
            long n = 0;

            while(running.get())
            {
                double gain = gains[setting.get()];
                float[] samples = new float[length * 2];

                for(int x = 0; x < length; x++, n++)
                {
                    double phase = 2 * Math.PI * n * 0.15;
                    //Antenna noise and a signal 30 dB above it in its bin, both scaled by the gain
                    double i = gain * (0.05 * random.nextGaussian() + 0.4 * Math.cos(phase));
                    double q = gain * (0.05 * random.nextGaussian() + 0.4 * Math.sin(phase));
                    //ADC noise, independent of the gain
                    i += 0.004 * random.nextGaussian();
                    q += 0.004 * random.nextGaussian();
                    samples[2 * x] = (float)Math.max(-1, Math.min(1, i));
                    samples[2 * x + 1] = (float)Math.max(-1, Math.min(1, q));
                }

                FloatNativeBuffer buffer = new FloatNativeBuffer(samples, System.currentTimeMillis(), 2400);
                estimator.receive(buffer);
                monitor.receive(buffer);

                try
                {
                    Thread.sleep(length / 2400);
                }
                catch(InterruptedException e)
                {
                    return;
                }
            }
        });
        generator.setDaemon(true);
        generator.start();

        GainSteps steps = new GainSteps()
        {
            public boolean isAvailable() { return true; }
            public String getUnavailableReason() { return ""; }
            public String getControlName() { return "Gain"; }
            public int count() { return gains.length; }
            public int current() { return setting.get(); }
            public String label(int index) { return "step " + index; }
            public void apply(int index) { setting.set(index); }
        };

        setting.set(1);

        try
        {
            GainSweep sweep = new GainSweep(steps, estimator, monitor);
            List<GainSweep.StepResult> results = sweep.run(new GainSweep.Listener()
            {
                public void stepStarted(int step, int of, String label) {}
                public void stepMeasured(GainSweep.StepResult result) {}
            });

            assertEquals(4, results.size());
            assertEquals(1, setting.get(), "the original setting is put back");
            assertFalse(results.get(2).isOverloaded(), results.toString());
            assertTrue(results.get(3).isOverloaded(), results.toString());
            assertTrue(results.get(1).signalDb() > results.get(0).signalDb() + 10, results.toString());
            assertEquals(2, GainSweep.recommend(results).index(), results.toString());
        }
        finally
        {
            running.set(false);
        }
    }
}
