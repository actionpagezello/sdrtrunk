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

import io.github.dsheirer.buffer.FloatNativeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-12): overload detection
 */
public class TunerSignalMonitorTest
{
    /**
     * Buffer of a tone at the amplitude, 8192 complex samples
     */
    private static FloatNativeBuffer tone(double amplitude)
    {
        float[] samples = new float[8192 * 2];

        for(int x = 0; x < 8192; x++)
        {
            double phase = 2 * Math.PI * x * 0.0123;
            samples[2 * x] = (float)Math.max(-1.0, Math.min(1.0, amplitude * Math.cos(phase)));
            samples[2 * x + 1] = (float)Math.max(-1.0, Math.min(1.0, amplitude * Math.sin(phase)));
        }

        return new FloatNativeBuffer(samples, 0, 1);
    }

    @Test
    public void aSignalWellBelowFullScaleIsNotOverload()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");
        monitor.receive(tone(0.25), 1000);
        TunerSignalMonitor.Reading reading = monitor.getRecentReading(1000);
        assertEquals(8192, reading.samplesChecked());
        assertEquals(0.0, reading.clippedPercent());
        assertEquals(20 * Math.log10(0.25), reading.peakDbfs(), 0.05);
        assertFalse(reading.isOverloaded());
        assertFalse(reading.isLowHeadroom());
    }

    @Test
    public void aClippedSignalIsOverload()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");
        //Driven 6 dB past full scale: the tops of the waveform are flattened at 1.0
        monitor.receive(tone(2.0), 1000);
        TunerSignalMonitor.Reading reading = monitor.getRecentReading(1000);
        assertTrue(reading.clippedPercent() > 50, "clipped " + reading.clippedPercent());
        assertTrue(reading.isOverloaded());
        assertEquals(0.0, reading.peakDbfs(), 0.01);
    }

    @Test
    public void aPeakNearFullScaleIsLowHeadroom()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");
        monitor.receive(tone(0.8), 1000);
        TunerSignalMonitor.Reading reading = monitor.getRecentReading(1000);
        assertFalse(reading.isOverloaded());
        assertTrue(reading.isLowHeadroom());
    }

    @Test
    public void buffersAreCheckedFourTimesASecondUnlessIntensive()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");

        for(long t = 1000; t < 2000; t += 10)
        {
            monitor.receive(tone(0.1), t);
        }

        assertEquals(4 * 8192, monitor.getRecentReading(2000).samplesChecked());

        monitor.setIntensive(true);
        monitor.startWindow();

        for(long t = 2000; t < 2100; t += 10)
        {
            monitor.receive(tone(0.1), t);
        }

        assertEquals(10 * 8192, monitor.getWindowReading().samplesChecked());
    }

    @Test
    public void readingsOlderThanThreeSecondsDropOff()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");
        monitor.receive(tone(2.0), 1000);
        assertTrue(monitor.getRecentReading(1000).isOverloaded());
        assertEquals(0, monitor.getRecentReading(1000 + TunerSignalMonitor.RECENT_MS + 1).samplesChecked());
    }

    @Test
    public void tunerReportedOverloadCounts()
    {
        TunerSignalMonitor monitor = new TunerSignalMonitor(() -> "test");
        monitor.startWindow();
        monitor.overloadEvent();
        assertTrue(monitor.getWindowReading().isOverloaded());
        assertTrue(monitor.getRecentReading().isOverloaded());
    }

    @Test
    public void onlyOnePerTenThousandOrMoreIsReported()
    {
        assertFalse(TunerSignalMonitor.isReportable(0.005));
        assertTrue(TunerSignalMonitor.isReportable(0.01));
        assertTrue(TunerSignalMonitor.isReportable(1.5));
    }
}
