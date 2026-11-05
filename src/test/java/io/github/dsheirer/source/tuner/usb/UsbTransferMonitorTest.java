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

package io.github.dsheirer.source.tuner.usb;

import io.github.dsheirer.source.tuner.usb.UsbTransferMonitor.Action;
import io.github.dsheirer.source.tuner.usb.UsbTransferMonitor.Status;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-15.11.4): USB transfer runaway detection.  Rates are Daly's RTL-2832 on 2026-11-04: 2.4 MSPS with 64 KiB
 * transfers is 73 transfers/s; the runaway was about 17,500/s.
 */
public class UsbTransferMonitorTest
{
    private static final int LENGTH = 65_536;
    private static final double EXPECTED = 2_400_000.0 / 32_768;

    /**
     * Feeds transfers at a rate for a duration and collects the actions returned.
     * @return [end time, actions...]
     */
    private static List<Action> feed(UsbTransferMonitor monitor, long[] clock, double perSecond, long durationMs,
                                     Status status, int length)
    {
        List<Action> actions = new ArrayList<>();
        long end = clock[0] + durationMs;
        double step = 1000.0 / perSecond;
        double t = clock[0];

        while(t < end)
        {
            Action action = monitor.record(status, length, (long)t);

            if(action == Action.LOG_SUMMARY)
            {
                monitor.takeSummary((long)t);
            }

            if(action != Action.NONE)
            {
                actions.add(action);
            }

            t += step;
        }

        clock[0] = end;
        return actions;
    }

    /**
     * Test: normal streaming for 10 minutes - no action and no summary.
     */
    @Test
    void testNormalStreamingIsQuiet()
    {
        UsbTransferMonitor monitor = new UsbTransferMonitor(() -> EXPECTED, LENGTH);
        long[] clock = {1_000_000};
        assertTrue(feed(monitor, clock, EXPECTED, 600_000, Status.COMPLETED, LENGTH).isEmpty());
    }

    /**
     * Test: Daly's runaway - restart after 3 s; restarts while it persists, at most 3 in 10 minutes, then stop.
     */
    @Test
    void testRunawayRestartsThenStops()
    {
        UsbTransferMonitor monitor = new UsbTransferMonitor(() -> EXPECTED, LENGTH);
        long[] clock = {1_000_000};
        feed(monitor, clock, EXPECTED, 10_000, Status.COMPLETED, LENGTH);

        List<Action> first = feed(monitor, clock, 17_500, 4_500, Status.COMPLETED, 512);
        assertEquals(List.of(Action.RESTART_STREAMING), first.stream().filter(a -> a != Action.LOG_SUMMARY).toList(),
                "One restart within the first 4.5 s");
        assertTrue(monitor.getLastRunawayRate() > 15_000);

        List<Action> persist = feed(monitor, clock, 17_500, 120_000, Status.COMPLETED, 512);
        List<Action> significant = persist.stream().filter(a -> a != Action.LOG_SUMMARY).toList();
        assertEquals(List.of(Action.RESTART_STREAMING, Action.RESTART_STREAMING, Action.STOP_TUNER),
                significant.subList(0, 3), "Two more restarts (settle 15 s each), then stop");
    }

    /**
     * Test: a runaway that a restart cures leaves normal streaming quiet again.
     */
    @Test
    void testRestartCuresRunaway()
    {
        UsbTransferMonitor monitor = new UsbTransferMonitor(() -> EXPECTED, LENGTH);
        long[] clock = {1_000_000};
        feed(monitor, clock, 17_500, 4_000, Status.COMPLETED, 512);
        List<Action> after = feed(monitor, clock, EXPECTED, 600_000, Status.COMPLETED, LENGTH);
        assertFalse(after.contains(Action.RESTART_STREAMING));
        assertFalse(after.contains(Action.STOP_TUNER));
        assertEquals(1, monitor.getRecentRestartCount());
    }

    /**
     * Test: timed-out and short transfers produce one summary per minute; unknown sample rate disables detection.
     */
    @Test
    void testSummaryAndUnknownRate()
    {
        UsbTransferMonitor monitor = new UsbTransferMonitor(() -> 0, LENGTH);
        long[] clock = {0};
        List<Action> actions = feed(monitor, clock, 5_000, 125_000, Status.TIMED_OUT, 100);
        assertEquals(2, actions.size(), "Two summaries in 125 s and no runaway action with an unknown rate");
        assertTrue(actions.stream().allMatch(a -> a == Action.LOG_SUMMARY));

        UsbTransferMonitor other = new UsbTransferMonitor(() -> EXPECTED, LENGTH);
        other.record(Status.STALL, 0, 0);
        String summary = other.takeSummary(1000);
        assertTrue(summary.contains("stall 1") && summary.contains("empty 1"), summary);
    }
}
