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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * AP-fork (ap-15.11.3): machine-wide budget for new Zello WebSocket connections.
 *
 * Zello limits new connections to 10 per minute per IP address (Zello support, 2026-11-03).  Each channel has its own
 * connection and its own reconnect backoff, so when the network drops every channel at once they all reconnect
 * together: Daly 2026-11-03 12:07, 11 channels disconnected and reconnected within two minutes.  Startup was already
 * staggered at 9 per minute by BroadcastModel; reconnects were not limited at all.
 *
 * Every connection attempt reserves a slot.  At most MAX_CONNECTIONS_PER_WINDOW slots fall in any WINDOW_MS; an
 * attempt that would exceed that is given the earliest later slot and told how long to wait.  Nothing is refused,
 * only delayed, and the reservation is made at the moment of asking so concurrent callers never share a slot.
 */
public class ZelloConnectRateLimiter
{
    /**
     * One below Zello's limit, for margin against clock skew and connections the limiter can't see (Zello's own
     * desktop client on the same machine, another program, a second SDRTrunk).
     */
    public static final int MAX_CONNECTIONS_PER_WINDOW = 9;
    public static final long WINDOW_MS = 60_000;

    private static final ZelloConnectRateLimiter INSTANCE = new ZelloConnectRateLimiter(System::currentTimeMillis,
            MAX_CONNECTIONS_PER_WINDOW, WINDOW_MS);

    private final LongSupplier mClock;
    private final int mMaxPerWindow;
    private final long mWindowMs;
    private final Deque<Long> mReservations = new ArrayDeque<>();

    ZelloConnectRateLimiter(LongSupplier clock, int maxPerWindow, long windowMs)
    {
        mClock = clock;
        mMaxPerWindow = maxPerWindow;
        mWindowMs = windowMs;
    }

    public static ZelloConnectRateLimiter getInstance()
    {
        return INSTANCE;
    }

    /**
     * Reserves a connection slot.
     * @return milliseconds to wait before connecting: 0 to connect now
     */
    public synchronized long reserve()
    {
        long now = mClock.getAsLong();

        //Reservations are in time order; drop those that can no longer share a window with any new slot.
        while(!mReservations.isEmpty() && mReservations.peekFirst() <= now - mWindowMs)
        {
            mReservations.pollFirst();
        }

        long slot = now;

        if(mReservations.size() >= mMaxPerWindow)
        {
            //The slot that is mMaxPerWindow back from the end must have left the window.
            Long[] times = mReservations.toArray(new Long[0]);
            slot = Math.max(now, times[times.length - mMaxPerWindow] + mWindowMs);
        }

        mReservations.addLast(slot);
        return slot - now;
    }

    /**
     * Number of reservations at or after now minus the window - for logging.
     */
    public synchronized int getRecentCount()
    {
        long now = mClock.getAsLong();
        int count = 0;

        for(long time: mReservations)
        {
            if(time > now - mWindowMs)
            {
                count++;
            }
        }

        return count;
    }
}
