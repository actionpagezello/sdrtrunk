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

package io.github.dsheirer.source.tuner;

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.configuration.TunerConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-15.11.5): applying a tuner configuration whose saved frequency is outside its frequency range.  Values
 * are Baker's two RTL-2832s on 2026-11-04.
 */
public class TunerControllerApplyTest
{
    private static final long HARDWARE_MINIMUM = 24_000_000;
    private static final long HARDWARE_MAXIMUM = 1_766_000_000;
    private static final double SAMPLE_RATE = 2_400_000;

    /**
     * Tuner controller like the RTL-2832: the sample rate and gain are applied after super.apply().
     */
    private static class Controller extends TunerController
    {
        private long mTuned;
        boolean mGainApplied;

        Controller() throws SourceException
        {
            super(new LoggingTunerErrorListener());
            setMinimumFrequency(HARDWARE_MINIMUM);
            setMaximumFrequency(HARDWARE_MAXIMUM);
            setUsableBandwidthPercentage(0.90);
            mFrequencyController.setSampleRate((int)SAMPLE_RATE);
        }

        @Override
        public void apply(TunerConfiguration config) throws SourceException
        {
            super.apply(config);
            mGainApplied = true;
        }

        @Override public void start() {}
        @Override public void stop() {}
        @Override public TunerType getTunerType() {return TunerType.TEST;}
        @Override public int getBufferSampleCount() {return 32_768;}
        @Override public long getTunedFrequency() {return mTuned;}
        @Override public void setTunedFrequency(long frequency) {mTuned = frequency;}
        @Override public double getCurrentSampleRate() {return SAMPLE_RATE;}
    }

    private static class Config extends TunerConfiguration
    {
        Config(long frequency, long minimum, long maximum, double ppm)
        {
            super("Test Bus:1 Port:6.2");
            setFrequency(frequency);
            setMinimumFrequency(minimum);
            setMaximumFrequency(maximum);
            setFrequencyCorrection(ppm);
        }

        @Override
        public TunerType getTunerType()
        {
            return TunerType.TEST;
        }
    }

    /**
     * Test: Baker Port 6.2 - saved 483.5125 MHz with a 150-170 MHz range.  Before: "Frequency [483512500] is above
     * the maximum [170000000]" and no gain.  Now: moved to the top of the range and everything else applied.
     */
    @Test
    void testSavedFrequencyAboveRange() throws Exception
    {
        Controller controller = new Controller();
        Config config = new Config(483_512_500, 150_000_000, 170_000_000, 1.5);
        controller.apply(config);

        long expected = 170_000_000 - controller.getUsableHalfBandwidth();
        assertEquals(expected, controller.getFrequency());
        assertEquals(expected, config.getFrequency(), "Config updated so the next start is clean");
        assertEquals(150_000_000, controller.getMinimumFrequency());
        assertEquals(170_000_000, controller.getMaximumFrequency());
        assertEquals(1.5, controller.getFrequencyCorrection(), 0.0001);
        assertTrue(controller.mGainApplied, "Subclass settings applied after super.apply()");
    }

    /**
     * Test: Baker Port 6.1.1 - saved 101.1 MHz (the default frequency) with a 150 MHz minimum.
     */
    @Test
    void testSavedFrequencyBelowRange() throws Exception
    {
        Controller controller = new Controller();
        Config config = new Config(TunerConfiguration.DEFAULT_FREQUENCY, 150_000_000, 0, 0);
        controller.apply(config);

        assertEquals(150_000_000 + controller.getUsableHalfBandwidth(), controller.getFrequency());
        assertEquals(HARDWARE_MAXIMUM, controller.getMaximumFrequency(), "Unset maximum keeps the hardware limit");
        assertTrue(controller.mGainApplied);
    }

    /**
     * Test: a frequency inside the range is applied unchanged.
     */
    @Test
    void testSavedFrequencyInsideRange() throws Exception
    {
        Controller controller = new Controller();
        Config config = new Config(155_940_000, 150_000_000, 170_000_000, 0);
        controller.apply(config);
        assertEquals(155_940_000, controller.getFrequency());
        assertEquals(155_940_000, config.getFrequency());
    }

    /**
     * Test: an inverted range is ignored rather than locking the tuner out.
     */
    @Test
    void testInvertedRangeIgnored() throws Exception
    {
        Controller controller = new Controller();
        Config config = new Config(460_000_000, 170_000_000, 150_000_000, 0);
        controller.apply(config);
        assertEquals(460_000_000, controller.getFrequency());
        assertEquals(HARDWARE_MINIMUM, controller.getMinimumFrequency());
        assertEquals(HARDWARE_MAXIMUM, controller.getMaximumFrequency());
        assertTrue(controller.mGainApplied);
    }

    /**
     * Test: moveIntoRange edges, including a range narrower than the tuner bandwidth.
     */
    @Test
    void testMoveIntoRange()
    {
        assertEquals(160_000_000, TunerController.moveIntoRange(160_000_000, 150_000_000, 170_000_000, 1_080_000));
        assertEquals(168_920_000, TunerController.moveIntoRange(483_512_500, 150_000_000, 170_000_000, 1_080_000));
        assertEquals(151_080_000, TunerController.moveIntoRange(101_100_000, 150_000_000, 170_000_000, 1_080_000));
        assertEquals(150_500_000, TunerController.moveIntoRange(483_512_500, 150_000_000, 151_000_000, 1_080_000));
        assertEquals(170_000_000, TunerController.moveIntoRange(483_512_500, 150_000_000, 170_000_000, 0));
    }
}
