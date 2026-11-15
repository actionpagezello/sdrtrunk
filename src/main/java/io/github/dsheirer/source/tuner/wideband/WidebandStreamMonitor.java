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

import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ap-fork (edacs-12): watches the sample stream of a wide-band USB tuner (Airspy, HydraSDR) and reports samples that
 * the driver dropped before they reached the application.
 *
 * Both driver libraries count dropped samples and the fork ignored the count.  Dropped samples are the usual cause of
 * clipped or garbled audio on a 6 or 10 MHz tuner, and nothing in the log showed them.
 *
 * - Always: one WARN per minute per tuner, and only when samples were dropped in that minute.
 * - With the "Airspy / HydraSDR tuners" Diagnostics box ticked (DEBUG): stream start and stop, a once-a-minute stream
 *   line, and whatever the tuner controller passes to debug().
 *
 * This class only counts and logs.  It never changes how a tuner runs.  All loggers are named under LOGGER_PREFIX so
 * that one Diagnostics category switches both tuner types.
 */
public class WidebandStreamMonitor
{
    public static final String LOGGER_PREFIX = "io.github.dsheirer.source.tuner.wideband";
    static final long REPORT_INTERVAL_NANOS = 60_000_000_000L;

    private final String mTunerName;
    private final Logger mLog;
    private final LongSupplier mNanoClock;
    private boolean mStreaming;
    private double mNominalSampleRate;
    private long mIntervalStart;
    private long mLastBuffer;
    private long mBuffers;
    private long mSamples;
    private long mDropped;
    private long mLargestGapNanos;
    private long mTotalDropped;
    private long mLastCumulativeDropped;

    /**
     * Constructs an instance
     * @param tunerType short name without spaces, used in the logger name, e.g. Airspy or HydraSDR
     * @param bus usb bus of the tuner
     * @param portAddress usb port of the tuner
     */
    public WidebandStreamMonitor(String tunerType, int bus, String portAddress)
    {
        this(tunerType + " USB Bus:" + bus + " Port:" + portAddress, LoggerFactory.getLogger(LOGGER_PREFIX + "." +
            tunerType), System::nanoTime);
    }

    /**
     * Constructs an instance with its own clock.  Package-private for testing.
     */
    WidebandStreamMonitor(String tunerName, Logger log, LongSupplier nanoClock)
    {
        mTunerName = tunerName;
        mLog = log;
        mNanoClock = nanoClock;
    }

    /**
     * Indicates if the Diagnostics box for these tuners is ticked, so that callers can skip building a message.
     */
    public boolean isDebugEnabled()
    {
        return mLog.isDebugEnabled();
    }

    /**
     * Logs a DEBUG line for this tuner.
     */
    public void debug(String message)
    {
        if(mLog.isDebugEnabled())
        {
            mLog.debug("Tuner [" + mTunerName + "] " + message);
        }
    }

    /**
     * The sample stream is about to start.  Call this before asking the driver to start streaming.
     * @param nominalSampleRate in complex samples per second
     */
    public synchronized void streamStarted(double nominalSampleRate)
    {
        long now = mNanoClock.getAsLong();
        mStreaming = true;
        mNominalSampleRate = nominalSampleRate;
        mIntervalStart = now;
        mLastBuffer = now;
        mBuffers = 0;
        mSamples = 0;
        mDropped = 0;
        mLargestGapNanos = 0;
        mTotalDropped = 0;
        mLastCumulativeDropped = 0;
        debug("sample stream started at " + String.format("%.3f", nominalSampleRate / 1E6) + " MHz");
    }

    /**
     * The sample stream has stopped.  Call this after the driver has stopped streaming.  Reports what is left of the
     * current minute.
     */
    public synchronized void streamStopped()
    {
        if(mStreaming)
        {
            closeInterval(mNanoClock.getAsLong());
            mStreaming = false;
            debug("sample stream stopped - dropped samples since it started: " + mTotalDropped);
        }
    }

    /**
     * A buffer arrived from a driver that reports the samples dropped since the previous buffer (Airspy).
     * @param sampleCount complex samples in the buffer
     * @param droppedSinceLastBuffer samples the driver dropped between the previous buffer and this one
     */
    public synchronized void received(int sampleCount, long droppedSinceLastBuffer)
    {
        if(!mStreaming)
        {
            return;
        }

        long now = mNanoClock.getAsLong();
        long gap = now - mLastBuffer;

        if(mBuffers > 0 && gap > mLargestGapNanos)
        {
            mLargestGapNanos = gap;
        }

        mLastBuffer = now;
        mBuffers++;
        mSamples += sampleCount;

        if(droppedSinceLastBuffer > 0)
        {
            mDropped += droppedSinceLastBuffer;
            mTotalDropped += droppedSinceLastBuffer;
        }

        if(now - mIntervalStart >= REPORT_INTERVAL_NANOS)
        {
            closeInterval(now);
        }
    }

    /**
     * A buffer arrived from a driver that reports the samples dropped since the stream started (HydraSDR).
     * @param sampleCount complex samples in the buffer
     * @param droppedSinceStreamStart running total from the driver
     */
    public synchronized void receivedCumulative(int sampleCount, long droppedSinceStreamStart)
    {
        //A total that goes backwards means the driver restarted its count
        long delta = droppedSinceStreamStart >= mLastCumulativeDropped ?
            droppedSinceStreamStart - mLastCumulativeDropped : droppedSinceStreamStart;
        mLastCumulativeDropped = droppedSinceStreamStart;
        received(sampleCount, delta);
    }

    /**
     * Ends the current reporting interval, logs it and starts the next one.  Package-private for testing.
     * @param now nanosecond clock value
     * @return what was counted in the interval that ended
     */
    synchronized Report closeInterval(long now)
    {
        double seconds = (now - mIntervalStart) / 1E9;
        Report report = new Report(mBuffers, mSamples, mDropped, seconds, seconds > 0 ? mSamples / seconds : 0.0,
            mLargestGapNanos / 1E6, mTotalDropped);

        if(report.dropped() > 0)
        {
            mLog.warn(String.format("Tuner [%s] DROPPED %d samples in the last %.0f s (%.3f%% of its stream) - the " +
                "computer or the USB port is not keeping up with this tuner, so audio on its channels can be clipped " +
                "or garbled. Dropped since the stream started: %d", mTunerName, report.dropped(), report.seconds(),
                report.droppedPercent(), report.totalDropped()));
        }

        if(mLog.isDebugEnabled())
        {
            mLog.debug(String.format("Tuner [%s] stream last %.0f s: %d buffers, measured %.3f MHz against nominal " +
                "%.3f MHz, largest gap between buffers %.1f ms, dropped %d samples", mTunerName, report.seconds(),
                report.buffers(), report.measuredSampleRate() / 1E6, mNominalSampleRate / 1E6,
                report.largestGapMilliseconds(), report.dropped()));
        }

        mIntervalStart = now;
        mBuffers = 0;
        mSamples = 0;
        mDropped = 0;
        mLargestGapNanos = 0;
        return report;
    }

    /**
     * What was counted in one reporting interval.
     * @param buffers received
     * @param samples received
     * @param dropped samples the driver reported as dropped
     * @param seconds length of the interval
     * @param measuredSampleRate samples received per second
     * @param largestGapMilliseconds longest time between two buffers
     * @param totalDropped samples dropped since the stream started
     */
    public record Report(long buffers, long samples, long dropped, double seconds, double measuredSampleRate,
                         double largestGapMilliseconds, long totalDropped)
    {
        /**
         * Dropped samples as a percentage of the samples that should have arrived (received plus dropped).
         */
        public double droppedPercent()
        {
            long expected = samples + dropped;
            return expected > 0 ? 100.0 * dropped / expected : 0.0;
        }
    }
}
