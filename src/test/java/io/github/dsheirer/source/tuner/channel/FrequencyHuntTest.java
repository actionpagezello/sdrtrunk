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

package io.github.dsheirer.source.tuner.channel;

import io.github.dsheirer.source.tuner.channel.rotation.FrequencyHunt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (edacs-14): the hunting log lines.  The scenario is MBTA 2026-11-12: control channel on 853.7250 MHz off
 * the air for just under 50 minutes, six frequencies in the channel's list, one frequency change a second.
 */
public class FrequencyHuntTest
{
    private static final long CONTROL = 853_725_000L;
    private static final long OTHER = 851_175_000L;

    /**
     * Hunts from start to end, one request and one check a second, returning the lines.
     */
    private static java.util.List<String> hunt(FrequencyHunt hunt, long start, long end)
    {
        java.util.List<String> lines = new java.util.ArrayList<>();

        for(long now = start; now < end; now += 1000)
        {
            hunt.requested(now, now == start ? CONTROL : OTHER);
            String line = hunt.check(now, OTHER);

            if(line != null)
            {
                lines.add(line);
            }
        }

        return lines;
    }

    @Test
    public void aLockedChannelLogsNothing()
    {
        FrequencyHunt hunt = new FrequencyHunt("Channel [18/EDACS]", 6);

        assertFalse(hunt.isHunting());
        assertNull(hunt.check(1_000_000, CONTROL));
    }

    @Test
    public void anOutageLogsStartProgressAndEnd()
    {
        FrequencyHunt hunt = new FrequencyHunt("Channel [18/EDACS]", 6);
        long start = 10_000_000;
        long end = start + 49 * 60_000 + 30_000;
        java.util.List<String> lines = hunt(hunt, start, end);

        //One line at 15 seconds and one every 10 minutes after the start: 10, 20, 30, 40 minutes
        assertEquals(5, lines.size(), lines.toString());
        assertEquals("Channel [18/EDACS] has had no control channel for 15 seconds (lost on 853.7250 MHz) - hunting " +
            "through its [6] frequencies", lines.get(0));
        assertEquals("Channel [18/EDACS] is still hunting for a control channel after 10 minutes - none found on any " +
            "of its [6] frequencies", lines.get(1));

        //The control channel returns: the requests stop, and five seconds later the hunt is over
        assertTrue(hunt.isHunting());
        assertNull(hunt.check(end + 3000, CONTROL));
        String found = hunt.check(end + 4000, CONTROL);

        assertEquals("Channel [18/EDACS] found a control channel on 853.7250 MHz after hunting for 49 minutes - the " +
            "same frequency it lost", found);
        assertFalse(hunt.isHunting());
        assertNull(hunt.check(end + 60_000, CONTROL));
    }

    @Test
    public void aMovedControlChannelNamesBothFrequencies()
    {
        FrequencyHunt hunt = new FrequencyHunt("Channel [18/EDACS]", 15);
        hunt(hunt, 0, 20_000);

        assertEquals("Channel [18/EDACS] found a control channel on 851.1750 MHz after hunting for 19 seconds - it " +
            "lost 853.7250 MHz", hunt.check(19_000 + FrequencyHunt.SETTLED_MILLISECONDS, OTHER));
    }

    @Test
    public void aShortFadeIsCountedNotLogged()
    {
        FrequencyHunt hunt = new FrequencyHunt("Channel [3/P25-1]", 4);

        //Three fades of four seconds each
        for(int fade = 0; fade < 3; fade++)
        {
            long start = fade * 100_000L;
            assertTrue(hunt(hunt, start, start + 4000).isEmpty());
            assertNull(hunt.check(start + 3000 + FrequencyHunt.SETTLED_MILLISECONDS, CONTROL));
            assertFalse(hunt.isHunting());
        }

        //Then a real outage - the line carries the count of the fades
        java.util.List<String> lines = hunt(hunt, 1_000_000, 1_000_000 + 20_000);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith("; [3] shorter hunts since the last one logged"), lines.get(0));

        //And the count starts again
        assertNotNull(hunt.check(1_019_000 + FrequencyHunt.SETTLED_MILLISECONDS, CONTROL));
        lines = hunt(hunt, 2_000_000, 2_000_000 + 20_000);
        assertFalse(lines.get(0).contains("shorter hunts"), lines.get(0));
    }

    @Test
    public void resetEndsTheHuntSilently()
    {
        FrequencyHunt hunt = new FrequencyHunt("Channel [18/EDACS]", 6);
        hunt(hunt, 0, 60_000);
        hunt.reset();

        assertFalse(hunt.isHunting());
        assertNull(hunt.check(120_000, CONTROL));
    }
}
