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

package io.github.dsheirer.audio.broadcast.zello;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-15.11.3): machine-wide Zello connection budget - Zello allows 10 new connections per minute per IP.
 */
public class ZelloConnectRateLimiterTest
{
    /**
     * Test: 11 channels reconnecting at once (Daly 2026-11-03 12:07) - the first 9 connect now, the rest wait until
     * the first slots leave the minute, and no 60 s window ever holds more than 9.
     */
    @Test
    void testBurstIsSpreadOverTheMinute()
    {
        AtomicLong clock = new AtomicLong(1_000_000);
        ZelloConnectRateLimiter limiter = new ZelloConnectRateLimiter(clock::get, 9, 60_000);
        List<Long> slots = new ArrayList<>();

        for(int x = 0; x < 11; x++)
        {
            slots.add(clock.get() + limiter.reserve());
        }

        for(int x = 0; x < 9; x++)
        {
            assertEquals(clock.get(), slots.get(x), "Slot " + x + " connects now");
        }

        assertEquals(clock.get() + 60_000, slots.get(9), "10th waits for the first to leave the window");
        assertEquals(clock.get() + 60_000, slots.get(10), "11th waits for the second");
        assertMaxPerWindow(slots, 9, 60_000);
    }

    /**
     * Test: connections spread out in time are never delayed.
     */
    @Test
    void testSpreadConnectionsAreNotDelayed()
    {
        AtomicLong clock = new AtomicLong(0);
        ZelloConnectRateLimiter limiter = new ZelloConnectRateLimiter(clock::get, 9, 60_000);

        for(int x = 0; x < 50; x++)
        {
            assertEquals(0, limiter.reserve(), "Connection " + x + " 7 s after the previous one");
            clock.addAndGet(7_000);
        }
    }

    /**
     * Test: a continuous storm of reconnect requests stays within the budget in every window.
     */
    @Test
    void testStormStaysWithinBudget()
    {
        AtomicLong clock = new AtomicLong(0);
        ZelloConnectRateLimiter limiter = new ZelloConnectRateLimiter(clock::get, 9, 60_000);
        List<Long> slots = new ArrayList<>();

        for(int x = 0; x < 200; x++)
        {
            slots.add(clock.get() + limiter.reserve());
            clock.addAndGet(500);
        }

        assertMaxPerWindow(slots, 9, 60_000);
    }

    private static void assertMaxPerWindow(List<Long> slots, int max, long window)
    {
        List<Long> sorted = new ArrayList<>(slots);
        sorted.sort(Long::compare);

        for(int x = 0; x + max < sorted.size(); x++)
        {
            assertTrue(sorted.get(x + max) - sorted.get(x) >= window, "More than " + max + " connections within " +
                    window + " ms starting at " + sorted.get(x));
        }
    }
}
