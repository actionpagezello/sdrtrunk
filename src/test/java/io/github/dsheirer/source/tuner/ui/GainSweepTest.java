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
    public void noSignalToMeasureGivesNoRecommendation()
    {
        GainSweep.Recommendation recommendation = GainSweep.recommend(List.of(step(0, -80, 1, 0), step(1, -70, 1.5, 0)));
        assertEquals(-1, recommendation.index());
        assertTrue(recommendation.explanation().contains("control channel"));
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
