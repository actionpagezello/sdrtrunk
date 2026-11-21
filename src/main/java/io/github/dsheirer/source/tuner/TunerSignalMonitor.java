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

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-12): watches a tuner's samples for overload - samples at or near the top of the tuner's range, where the
 * ADC (or the tuner's digital filter after it) clips and strong signals create false signals and a raised noise floor
 * across the band.
 *
 * The samples are already scaled so that the tuner's full scale is 1.0.  A sample is counted as at full scale when its
 * I or Q value reaches FULL_SCALE_THRESHOLD (-0.26 dBFS).  The peak sample level is also tracked, as headroom.
 *
 * Cost: one buffer is checked every CHECK_INTERVAL_MS (a quarter of a second), on the tuner's sample delivery thread,
 * which is a few hundred microseconds a second at 10 MSPS.  A gain check (setIntensive) checks every buffer.
 *
 * A summary is logged every LOG_INTERVAL_MS: a warning when at least REPORT_PERCENT of the checked samples were at full
 * scale, or the tuner itself reported an overload (RSP), otherwise nothing.
 */
public class TunerSignalMonitor
{
    private static final Logger LOG = LoggerFactory.getLogger(TunerSignalMonitor.class);
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.000");
    private static final DecimalFormat DB_FORMAT = new DecimalFormat("0.0");

    /**
     * I or Q magnitude counted as full scale: 0.97 is -0.26 dBFS.  An 8-bit RTL-SDR's top codes are 0.996.
     */
    public static final float FULL_SCALE_THRESHOLD = 0.97f;

    /**
     * Share of checked samples at full scale, in percent, at or above which the tuner is reported as overloaded.  At
     * 0.01% an occasional peak is ignored; sustained overload from a strong signal is far above it.
     */
    public static final double REPORT_PERCENT = 0.01;

    /**
     * Peak level, in dBFS, above which the meter shows little headroom left
     */
    public static final double LOW_HEADROOM_DBFS = -3.0;

    static final long CHECK_INTERVAL_MS = 250;
    static final long LOG_INTERVAL_MS = 5 * 60 * 1000;
    //Period covered by the meter readings
    static final long RECENT_MS = 3000;

    private final Supplier<String> mTunerName;
    //ap-16: describes the strongest signal for the overload warning, or returns null
    private volatile Supplier<String> mStrongestSignal = () -> null;
    //ap-16: keeps the spectrum measurement running for the strongest signal in the last seconds of an overloaded period
    private volatile Runnable mStrongestSignalWarmUp = () -> {};
    static final long STRONGEST_SIGNAL_WARM_UP_MS = 10_000;
    private volatile boolean mIntensive;
    private long mLastCheck;

    //Logging period
    private long mLogPeriodStart;
    private long mLogChecked;
    private long mLogClipped;
    private float mLogPeak;
    private int mLogOverloadEvents;

    //Recent readings for the meter: one slot per check, oldest overwritten
    private static final int RECENT_SLOTS = 64;
    private final long[] mSlotTime = new long[RECENT_SLOTS];
    private final long[] mSlotChecked = new long[RECENT_SLOTS];
    private final long[] mSlotClipped = new long[RECENT_SLOTS];
    private final float[] mSlotPeak = new float[RECENT_SLOTS];
    private int mSlot;
    private volatile long mLastOverloadEvent;

    //Measurement window for a gain check step
    private long mWindowChecked;
    private long mWindowClipped;
    private float mWindowPeak;
    private int mWindowOverloadEvents;

    /**
     * Constructs an instance
     * @param tunerName for log messages
     */
    public TunerSignalMonitor(Supplier<String> tunerName)
    {
        mTunerName = tunerName;
    }

    /**
     * AP-fork (ap-16): sets the description of the strongest signal added to the overload warning, so the transmitter
     * causing the overload can be found.
     * @param strongestSignal supplies the description, or null when unknown
     * @param warmUp called in the last seconds of an overloaded period so the spectrum measurement is running when
     * the warning is written
     */
    public void setStrongestSignal(Supplier<String> strongestSignal, Runnable warmUp)
    {
        mStrongestSignal = strongestSignal != null ? strongestSignal : () -> null;
        mStrongestSignalWarmUp = warmUp != null ? warmUp : () -> {};
    }

    /**
     * Receives a tuner buffer.  Called on the tuner's sample delivery thread.
     */
    public void receive(INativeBuffer buffer)
    {
        receive(buffer, System.currentTimeMillis());
    }

    void receive(INativeBuffer buffer, long now)
    {
        if(!mIntensive && now - mLastCheck < CHECK_INTERVAL_MS)
        {
            return;
        }

        mLastCheck = now;
        long checked = 0;
        long clipped = 0;
        float peak = 0;
        Iterator<InterleavedComplexSamples> iterator = buffer.iteratorInterleaved();

        while(iterator.hasNext())
        {
            float[] samples = iterator.next().samples();

            for(int x = 0; x < samples.length; x += 2)
            {
                float i = Math.abs(samples[x]);
                float q = Math.abs(samples[x + 1]);
                float max = Math.max(i, q);

                if(max > peak)
                {
                    peak = max;
                }

                if(max >= FULL_SCALE_THRESHOLD)
                {
                    clipped++;
                }
            }

            checked += samples.length / 2;
        }

        record(checked, clipped, peak, now);
    }

    synchronized void record(long checked, long clipped, float peak, long now)
    {
        if(mLogPeriodStart == 0)
        {
            mLogPeriodStart = now;
        }

        //ap-12: a gain check's samples are measured at settings that aren't the tuner's own - they stay out of the
        //5-minute log and the history
        if(!mIntensive)
        {
            mLogChecked += checked;
            mLogClipped += clipped;
            mLogPeak = Math.max(mLogPeak, peak);
            addHistory(checked, clipped, peak, 0, now);
        }

        mSlot = (mSlot + 1) % RECENT_SLOTS;
        mSlotTime[mSlot] = now;
        mSlotChecked[mSlot] = checked;
        mSlotClipped[mSlot] = clipped;
        mSlotPeak[mSlot] = peak;

        mWindowChecked += checked;
        mWindowClipped += clipped;
        mWindowPeak = Math.max(mWindowPeak, peak);

        if(now - mLogPeriodStart >= LOG_INTERVAL_MS)
        {
            logPeriod(now);
        }
        else if(now - mLogPeriodStart >= LOG_INTERVAL_MS - STRONGEST_SIGNAL_WARM_UP_MS && mLogChecked > 0 &&
            (isReportable(100.0 * mLogClipped / mLogChecked) || mLogOverloadEvents > 0))
        {
            try
            {
                mStrongestSignalWarmUp.run();
            }
            catch(RuntimeException e)
            {
                //The warning is written without the strongest signal
            }
        }
    }

    /**
     * Records an overload reported by the tuner itself (RSP power overload event)
     */
    public synchronized void overloadEvent()
    {
        long now = System.currentTimeMillis();
        mWindowOverloadEvents++;
        mLastOverloadEvent = now;

        if(!mIntensive)
        {
            mLogOverloadEvents++;
            addHistory(0, 0, 0, 1, now);
        }
    }

    //ap-12: history at the tuner's current gain setting, one bucket per minute, for the gain check
    static final long HISTORY_MS = 30 * 60 * 1000;
    private static final long BUCKET_MS = 60 * 1000;
    private final java.util.ArrayDeque<long[]> mHistory = new java.util.ArrayDeque<>();
    private long mHistoryStart;

    private void addHistory(long checked, long clipped, float peak, int events, long now)
    {
        if(mHistoryStart == 0)
        {
            mHistoryStart = now;
        }

        long bucket = now / BUCKET_MS;
        long[] last = mHistory.peekLast();

        if(last == null || last[0] != bucket)
        {
            last = new long[]{bucket, 0, 0, 0, 0};
            mHistory.addLast(last);
        }

        last[1] += checked;
        last[2] += clipped;
        //Peak kept in millionths of full scale
        last[3] = Math.max(last[3], (long)(peak * 1_000_000));
        last[4] += events;

        while(!mHistory.isEmpty() && mHistory.peekFirst()[0] < bucket - HISTORY_MS / BUCKET_MS)
        {
            mHistory.removeFirst();
        }
    }

    /**
     * ap-12: forgets the history, because the tuner's gain setting changed.  Called when the user or the gain check
     * changes the setting.
     */
    public synchronized void markSettingChange()
    {
        mHistory.clear();
        mHistoryStart = 0;
    }

    /**
     * ap-12: readings at the current gain setting over up to the last 30 minutes (gain check samples excluded)
     */
    public synchronized History getHistory()
    {
        return getHistory(System.currentTimeMillis());
    }

    synchronized History getHistory(long now)
    {
        long checked = 0;
        long clipped = 0;
        long peak = 0;
        int events = 0;
        long earliest = now / BUCKET_MS - HISTORY_MS / BUCKET_MS;

        for(long[] bucket : mHistory)
        {
            if(bucket[0] >= earliest)
            {
                checked += bucket[1];
                clipped += bucket[2];
                peak = Math.max(peak, bucket[3]);
                events += bucket[4];
            }
        }

        long covered = mHistoryStart == 0 ? 0 : Math.min(HISTORY_MS, now - mHistoryStart);
        Reading reading = new Reading(checked, checked > 0 ? 100.0 * clipped / checked : 0.0,
            checked > 0 ? toDb(peak / 1_000_000f) : Double.NaN, events);
        return new History(reading, covered);
    }

    /**
     * ap-12: readings at the current gain setting
     * @param reading over the period
     * @param coveredMs length of the period, up to 30 minutes
     */
    public record History(Reading reading, long coveredMs) {}

    private void logPeriod(long now)
    {
        double percent = mLogChecked > 0 ? 100.0 * mLogClipped / mLogChecked : 0.0;
        long minutes = Math.max(1, Math.round((now - mLogPeriodStart) / 60000.0));

        if(isReportable(percent) || mLogOverloadEvents > 0)
        {
            StringBuilder sb = new StringBuilder();
            sb.append("Tuner [").append(mTunerName.get()).append("] overload over the last ").append(minutes)
                .append(" minutes: ").append(PERCENT_FORMAT.format(percent)).append("% of samples at full scale, peak ")
                .append(DB_FORMAT.format(toDb(mLogPeak))).append(" dBFS");

            if(mLogOverloadEvents > 0)
            {
                sb.append(", ").append(mLogOverloadEvents).append(" overload events from the tuner");
            }

            //ap-16: peaks above 0 dBFS only occur on tuners with a real (not I/Q) ADC, Airspy and HydraSDR, when the
            //ADC itself clips
            if(mLogPeak > 1.0f)
            {
                sb.append(" (above 0 dBFS: the tuner's converter is clipping)");
            }

            String strongest = null;

            try
            {
                strongest = mStrongestSignal.get();
            }
            catch(RuntimeException e)
            {
                //Leave it out
            }

            if(strongest != null)
            {
                sb.append(", strongest signal ").append(strongest);
            }

            sb.append(" - reduce the gain (Gain Check in the tuner panel finds a setting)");
            LOG.warn(sb.toString());
        }
        else if(LOG.isDebugEnabled())
        {
            LOG.debug("Tuner [" + mTunerName.get() + "] signal level over the last " + minutes + " minutes: " +
                PERCENT_FORMAT.format(percent) + "% of samples at full scale, peak " +
                DB_FORMAT.format(toDb(mLogPeak)) + " dBFS");
        }

        mLogPeriodStart = now;
        mLogChecked = 0;
        mLogClipped = 0;
        mLogPeak = 0;
        mLogOverloadEvents = 0;
    }

    /**
     * Indicates if a share of samples at full scale is reported as overload
     * @param percent of samples at full scale
     */
    public static boolean isReportable(double percent)
    {
        return percent >= REPORT_PERCENT;
    }

    /**
     * Checks every buffer instead of one every quarter second, for a gain check.  Turn it off afterwards.
     */
    public void setIntensive(boolean intensive)
    {
        mIntensive = intensive;
    }

    /**
     * Indicates a gain check is running on this tuner
     */
    public boolean isIntensive()
    {
        return mIntensive;
    }

    /**
     * Starts a new measurement window (gain check step)
     */
    public synchronized void startWindow()
    {
        mWindowChecked = 0;
        mWindowClipped = 0;
        mWindowPeak = 0;
        mWindowOverloadEvents = 0;
    }

    /**
     * Readings since startWindow()
     */
    public synchronized Reading getWindowReading()
    {
        return new Reading(mWindowChecked, mWindowChecked > 0 ? 100.0 * mWindowClipped / mWindowChecked : 0.0,
            mWindowChecked > 0 ? toDb(mWindowPeak) : Double.NaN, mWindowOverloadEvents);
    }

    /**
     * Readings over the last few seconds, for the meter
     */
    public synchronized Reading getRecentReading()
    {
        return getRecentReading(System.currentTimeMillis());
    }

    synchronized Reading getRecentReading(long now)
    {
        long checked = 0;
        long clipped = 0;
        float peak = 0;

        for(int x = 0; x < RECENT_SLOTS; x++)
        {
            if(mSlotTime[x] > 0 && now - mSlotTime[x] <= RECENT_MS)
            {
                checked += mSlotChecked[x];
                clipped += mSlotClipped[x];
                peak = Math.max(peak, mSlotPeak[x]);
            }
        }

        int events = mLastOverloadEvent > 0 && now - mLastOverloadEvent <= RECENT_MS ? 1 : 0;
        return new Reading(checked, checked > 0 ? 100.0 * clipped / checked : 0.0,
            checked > 0 ? toDb(peak) : Double.NaN, events);
    }

    static double toDb(float magnitude)
    {
        return magnitude > 0 ? 20.0 * Math.log10(magnitude) : -120.0;
    }

    /**
     * Signal level readings
     * @param samplesChecked number of samples the readings come from (0 for none)
     * @param clippedPercent share of samples at full scale, in percent
     * @param peakDbfs highest sample level in dBFS, or NaN with no samples
     * @param overloadEvents overloads reported by the tuner itself
     */
    public record Reading(long samplesChecked, double clippedPercent, double peakDbfs, int overloadEvents)
    {
        /**
         * Indicates overload: samples at full scale at the reporting level, or an overload reported by the tuner
         */
        public boolean isOverloaded()
        {
            return isReportable(clippedPercent) || overloadEvents > 0;
        }

        /**
         * Indicates little headroom left, without overload
         */
        public boolean isLowHeadroom()
        {
            return !isOverloaded() && !Double.isNaN(peakDbfs) && peakDbfs >= LOW_HEADROOM_DBFS;
        }
    }
}
