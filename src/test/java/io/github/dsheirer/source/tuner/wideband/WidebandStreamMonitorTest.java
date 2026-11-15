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

package io.github.dsheirer.source.tuner.wideband;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the counting in the wide-band tuner stream monitor (edacs-12).
 */
public class WidebandStreamMonitorTest
{
    private static final long MILLISECOND = 1_000_000L;
    private final AtomicLong mClock = new AtomicLong(1000);

    private WidebandStreamMonitor monitor()
    {
        return new WidebandStreamMonitor("Test USB Bus:1 Port:1",
            LoggerFactory.getLogger(WidebandStreamMonitor.LOGGER_PREFIX + ".Test"), mClock::get);
    }

    @Test
    public void countsBuffersSamplesAndRate()
    {
        WidebandStreamMonitor monitor = monitor();
        monitor.streamStarted(10_000_000);

        //100 buffers of 65,536 samples, one every 10 ms = 1 second
        for(int x = 0; x < 100; x++)
        {
            mClock.addAndGet(10 * MILLISECOND);
            monitor.received(65536, 0);
        }

        WidebandStreamMonitor.Report report = monitor.closeInterval(mClock.get());
        assertEquals(100, report.buffers());
        assertEquals(6_553_600L, report.samples());
        assertEquals(0, report.dropped());
        assertEquals(1.0, report.seconds(), 1E-9);
        assertEquals(6_553_600.0, report.measuredSampleRate(), 1.0);
        assertEquals(10.0, report.largestGapMilliseconds(), 1E-6);
        assertEquals(0.0, report.droppedPercent(), 1E-9);
    }

    @Test
    public void countsDroppedSamplesPerBuffer()
    {
        WidebandStreamMonitor monitor = monitor();
        monitor.streamStarted(6_000_000);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.received(1000, 0);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.received(1000, 500);
        mClock.addAndGet(40 * MILLISECOND);
        monitor.received(1000, 500);

        WidebandStreamMonitor.Report report = monitor.closeInterval(mClock.get());
        assertEquals(3, report.buffers());
        assertEquals(1000, report.dropped());
        assertEquals(1000, report.totalDropped());
        assertEquals(25.0, report.droppedPercent(), 1E-9);
        assertEquals(40.0, report.largestGapMilliseconds(), 1E-6);

        //The next interval starts from zero but the total since the stream started is kept
        mClock.addAndGet(10 * MILLISECOND);
        monitor.received(1000, 7);
        WidebandStreamMonitor.Report next = monitor.closeInterval(mClock.get());
        assertEquals(1, next.buffers());
        assertEquals(7, next.dropped());
        assertEquals(1007, next.totalDropped());
    }

    @Test
    public void convertsRunningTotalToPerBuffer()
    {
        WidebandStreamMonitor monitor = monitor();
        monitor.streamStarted(10_000_000);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.receivedCumulative(1000, 0);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.receivedCumulative(1000, 300);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.receivedCumulative(1000, 300);
        mClock.addAndGet(10 * MILLISECOND);
        monitor.receivedCumulative(1000, 450);
        //The driver's total restarted
        mClock.addAndGet(10 * MILLISECOND);
        monitor.receivedCumulative(1000, 20);

        WidebandStreamMonitor.Report report = monitor.closeInterval(mClock.get());
        assertEquals(5, report.buffers());
        assertEquals(470, report.dropped());
    }

    @Test
    public void reportsByItselfAfterAMinuteAndIgnoresBuffersWhenStopped()
    {
        WidebandStreamMonitor monitor = monitor();

        //Not started yet: ignored
        monitor.received(1000, 1000);
        monitor.streamStarted(10_000_000);

        //61 buffers one second apart: the 60th closes the first interval, leaving one buffer in the next
        for(int x = 0; x < 61; x++)
        {
            mClock.addAndGet(1000 * MILLISECOND);
            monitor.received(1000, 1);
        }

        WidebandStreamMonitor.Report report = monitor.closeInterval(mClock.get());
        assertEquals(1, report.buffers());
        assertEquals(1, report.dropped());
        assertEquals(61, report.totalDropped());

        monitor.streamStopped();
        monitor.received(1000, 1000);
        assertEquals(0, monitor.closeInterval(mClock.get()).buffers());
    }
}
