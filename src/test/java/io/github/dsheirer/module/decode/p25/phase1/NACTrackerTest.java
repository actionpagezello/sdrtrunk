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

package io.github.dsheirer.module.decode.p25.phase1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * JUnit tests for the NAC tracker.
 */
public class NACTrackerTest
{
    /**
     * Test: the dominant NAC needs the minimum number of observations.
     */
    @Test
    void testDominantNAC()
    {
        NACTracker tracker = new NACTracker();
        assertEquals(0, tracker.getTrackedNAC(), "Empty");
        tracker.track(0x293);
        tracker.track(0x293);
        tracker.track(0x111);
        assertEquals(0, tracker.getTrackedNAC(), "Below the minimum observations");
        tracker.track(0x293);
        assertEquals(0x293, tracker.getTrackedNAC(), "Dominant");
    }

    /**
     * Test: when the tracker is full, the NAC with the fewest observations is evicted (oldest observation breaks a
     * tie), so a burst of corrupted NAC values can't evict the dominant NAC.  All of the observations occur within
     * the same millisecond, as they do when a recording decodes faster than real time.
     */
    @Test
    void testWeakestNACEvicted()
    {
        for(int repeat = 0; repeat < 100; repeat++)
        {
            NACTracker tracker = new NACTracker();

            //0x293 is the most observed, but the least recently observed when the noise NACs arrive
            for(int x = 0; x < 5; x++)
            {
                tracker.track(0x293);
            }

            for(int x = 0; x < 4; x++)
            {
                tracker.track(0x111);
            }

            //More single-observation noise NACs than the tracker holds
            for(int nac = 0x400; nac < 0x410; nac++)
            {
                tracker.track(nac);
            }

            assertEquals(0x293, tracker.getTrackedNAC(), "Dominant NAC survives noise NACs, repeat " + repeat);

            //0x111 also survives: the single-observation NACs are evicted first
            for(int x = 0; x < 2; x++)
            {
                tracker.track(0x111);
            }

            assertEquals(0x111, tracker.getTrackedNAC(), "Second NAC kept its count, repeat " + repeat);
        }
    }
}
