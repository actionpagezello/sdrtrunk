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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.DoubleSupplier;

/**
 * AP-fork (ap-15.11.4): watches the USB transfers of a streaming tuner for a runaway - transfers completing far faster
 * than the sample rate allows - and keeps per-status counts for a periodic summary.
 *
 * Daly 2026-11-04 15:19: an RTL-2832 at 2.4 MSPS (73 transfers/s of 64 KiB) started completing about 17,500
 * transfers/s and kept that up for four hours, until SDRTrunk was restarted.  Its channels received nothing usable and
 * the tuner's buffer queue discarded about 268 million buffers.  Nothing was logged about the transfers themselves.
 *
 * record() is called once per completed transfer on the USB event thread and returns what the controller should do.
 * Not thread safe: one instance per transfer manager, used from the USB event thread only.
 */
public class UsbTransferMonitor
{
    /** A window counts as runaway when it holds more than this many times the expected transfers... */
    static final double RUNAWAY_FACTOR = 10.0;
    /** ...and at least this many, so a low sample rate can't trip it on jitter. */
    static final int RUNAWAY_MINIMUM_PER_SECOND = 500;
    /** Consecutive runaway windows before acting. */
    static final int RUNAWAY_WINDOWS = 3;
    /** After a restart, runaway windows are ignored for this long while streaming settles. */
    static final long RESTART_SETTLE_MS = 15_000;
    /** More than this many restarts within RESTART_LIMIT_WINDOW_MS stops the tuner instead. */
    static final int RESTART_LIMIT = 3;
    static final long RESTART_LIMIT_WINDOW_MS = 600_000;
    static final long SUMMARY_INTERVAL_MS = 60_000;
    private static final long WINDOW_MS = 1_000;

    public enum Action {NONE, LOG_SUMMARY, RESTART_STREAMING, STOP_TUNER}

    private final DoubleSupplier mExpectedTransfersPerSecond;
    private final int mTransferLength;
    private long mWindowStart = -1;
    private int mWindowCount;
    private int mConsecutiveRunawayWindows;
    private int mLastRunawayRate;
    private long mSettleUntil = 0;
    private final Deque<Long> mRestartTimes = new ArrayDeque<>();

    //Summary counts
    private long mSummaryStart = -1;
    private long mCompleted, mShort, mEmpty, mTimedOut, mStall, mError, mCancelled, mOther;

    /**
     * Constructs an instance
     * @param expectedTransfersPerSecond for the current sample rate (0 or less when unknown - no runaway detection)
     * @param transferLength full transfer buffer length in bytes
     */
    public UsbTransferMonitor(DoubleSupplier expectedTransfersPerSecond, int transferLength)
    {
        mExpectedTransfersPerSecond = expectedTransfersPerSecond;
        mTransferLength = transferLength;
    }

    /**
     * Records a completed transfer.
     * @param status transfer status category
     * @param actualLength bytes received
     * @param now milliseconds
     * @return action for the controller
     */
    public Action record(Status status, int actualLength, long now)
    {
        switch(status)
        {
            case COMPLETED -> mCompleted++;
            case TIMED_OUT -> mTimedOut++;
            case STALL -> mStall++;
            case ERROR -> mError++;
            case CANCELLED -> mCancelled++;
            default -> mOther++;
        }

        if(actualLength <= 0)
        {
            mEmpty++;
        }
        else if(actualLength < mTransferLength)
        {
            mShort++;
        }

        if(mSummaryStart < 0)
        {
            mSummaryStart = now;
        }

        if(mWindowStart < 0)
        {
            mWindowStart = now;
        }

        mWindowCount++;
        Action action = Action.NONE;

        if(now - mWindowStart >= WINDOW_MS)
        {
            int rate = (int)Math.round(mWindowCount * 1000.0 / (now - mWindowStart));
            double expected = mExpectedTransfersPerSecond.getAsDouble();
            boolean runaway = expected > 0 && rate > Math.max(expected * RUNAWAY_FACTOR, RUNAWAY_MINIMUM_PER_SECOND);

            if(runaway && now >= mSettleUntil)
            {
                mConsecutiveRunawayWindows++;
                mLastRunawayRate = rate;
            }
            else
            {
                mConsecutiveRunawayWindows = 0;
            }

            mWindowStart = now;
            mWindowCount = 0;

            if(mConsecutiveRunawayWindows >= RUNAWAY_WINDOWS)
            {
                mConsecutiveRunawayWindows = 0;

                while(!mRestartTimes.isEmpty() && mRestartTimes.peekFirst() < now - RESTART_LIMIT_WINDOW_MS)
                {
                    mRestartTimes.pollFirst();
                }

                if(mRestartTimes.size() >= RESTART_LIMIT)
                {
                    return Action.STOP_TUNER;
                }

                mRestartTimes.addLast(now);
                mSettleUntil = now + RESTART_SETTLE_MS;
                return Action.RESTART_STREAMING;
            }
        }

        if(now - mSummaryStart >= SUMMARY_INTERVAL_MS)
        {
            action = isAbnormal() ? Action.LOG_SUMMARY : Action.NONE;

            if(action == Action.NONE)
            {
                resetSummary(now);
            }
        }

        return action;
    }

    /**
     * Indicates the summary period had anything other than full-length completed transfers.
     */
    boolean isAbnormal()
    {
        return mTimedOut + mStall + mError + mCancelled + mOther + mShort + mEmpty > 0;
    }

    /**
     * Describes the summary period and starts a new one.
     */
    public String takeSummary(long now)
    {
        String summary = "completed " + mCompleted + " (short " + mShort + ", empty " + mEmpty + "), timed out " +
                mTimedOut + ", stall " + mStall + ", error " + mError + ", cancelled " + mCancelled + ", other " +
                mOther + " in the last " + Math.max(1, (now - mSummaryStart) / 1000) + " s";
        resetSummary(now);
        return summary;
    }

    /**
     * Transfers per second in the window that triggered the last runaway action.
     */
    public int getLastRunawayRate()
    {
        return mLastRunawayRate;
    }

    /**
     * Restarts in the current limit window.
     */
    public int getRecentRestartCount()
    {
        return mRestartTimes.size();
    }

    private void resetSummary(long now)
    {
        mSummaryStart = now;
        mCompleted = mShort = mEmpty = mTimedOut = mStall = mError = mCancelled = mOther = 0;
    }

    /**
     * Transfer status categories (mapped from LibUsb status codes by the caller).
     */
    public enum Status {COMPLETED, TIMED_OUT, STALL, ERROR, CANCELLED, OTHER}
}
