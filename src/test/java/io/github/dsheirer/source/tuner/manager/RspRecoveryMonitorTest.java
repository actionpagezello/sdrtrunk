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

package io.github.dsheirer.source.tuner.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (edacs-14): RSP recovery decisions, with the SDRplay API, the tuner model and the channels replaced by
 * fakes.  The scenario is Daly 2026-11-12: RSP1B SER#24050F5260 removed while carrying its channels, a second RSP1B
 * and an RTL still running.
 */
public class RspRecoveryMonitorTest
{
    private static final String SERIAL = "24050F5260";
    private static final String ID = "RSP1B SER#24050F5260";
    private FakeHost mHost;
    private FakeScheduler mScheduler;
    private RspRecoveryMonitor<String> mMonitor;

    @BeforeEach
    public void setUp()
    {
        mHost = new FakeHost();
        mScheduler = new FakeScheduler();
        mMonitor = new RspRecoveryMonitor<>(mHost, mScheduler);
        mHost.mRunning.addAll(List.of("Salem Police", "Salem Fire", "Hudson Fire", "Andover Police", "Lawrence EMS"));
    }

    /**
     * Removes the RSP: the three channels it carried stop, the two on other tuners keep running.
     */
    private void removeRsp()
    {
        RspRecoveryMonitor<String>.Removal removal = mMonitor.removing(SERIAL, ID);
        mHost.mRunning.removeAll(List.of("Salem Police", "Salem Fire", "Hudson Fire"));
        removal.complete();
    }

    @Test
    public void nothingIsScheduledUntilAnRspIsRemoved()
    {
        assertEquals(0, mMonitor.getPendingCount());
        assertNull(mScheduler.mRepeating);
    }

    @Test
    public void removalStartsTheChecks()
    {
        removeRsp();

        assertEquals(1, mMonitor.getPendingCount());
        assertNotNull(mScheduler.mRepeating);
        assertEquals(RspRecoveryMonitor.FIRST_CHECK_SECONDS, mScheduler.mFirstDelay);
        assertEquals(RspRecoveryMonitor.CHECK_INTERVAL_SECONDS, mScheduler.mInterval);
    }

    @Test
    public void keepsCheckingWhileTheDeviceIsNotListed()
    {
        removeRsp();

        for(int x = 0; x < 50; x++)
        {
            mScheduler.mRepeating.run();
        }

        assertEquals(1, mMonitor.getPendingCount());
        assertTrue(mHost.mAttached.isEmpty(), "A device that isn't listed must not be attached");
        assertTrue(mHost.mStarted.isEmpty());
        assertFalse(mScheduler.mCancelled);
    }

    @Test
    public void restartsOnlyTheChannelsTheRemovalStopped()
    {
        removeRsp();
        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();

        assertEquals(List.of(SERIAL), mHost.mAttached);
        assertEquals(0, mMonitor.getPendingCount());
        assertTrue(mHost.mStarted.isEmpty(), "Channels start after the delay, not at once");

        mScheduler.runLater();

        assertEquals(List.of("Salem Police", "Salem Fire", "Hudson Fire"), mHost.mStarted);
        assertTrue(mHost.mRunning.containsAll(List.of("Andover Police", "Lawrence EMS")));
        assertEquals(RspRecoveryMonitor.CHANNEL_RESTART_DELAY_SECONDS, mScheduler.mLaterDelays.get(0));
    }

    @Test
    public void checksEndOnceTheDeviceIsBack()
    {
        removeRsp();
        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();
        mScheduler.mRepeating.run();

        assertTrue(mScheduler.mCancelled, "With nothing left to recover the repeating check is cancelled");
        assertEquals(1, mHost.mAttached.size(), "The device is attached once");
    }

    @Test
    public void listedButNotStartingIsRetried()
    {
        removeRsp();
        mHost.mListed.add(SERIAL);
        mHost.mAttachSucceeds = false;
        mScheduler.mRepeating.run();
        mScheduler.mRepeating.run();

        assertEquals(1, mMonitor.getPendingCount());
        assertEquals(2, mHost.mAttached.size());
        assertTrue(mHost.mStarted.isEmpty());

        mHost.mAttachSucceeds = true;
        mScheduler.mRepeating.run();
        mScheduler.runLater();

        assertEquals(0, mMonitor.getPendingCount());
        assertEquals(3, mHost.mStarted.size());
    }

    @Test
    public void anotherDeviceInTheListIsNotMistakenForTheRemovedOne()
    {
        removeRsp();
        mHost.mListed.add("24040A4B60");
        mScheduler.mRepeating.run();

        assertEquals(1, mMonitor.getPendingCount());
        assertTrue(mHost.mAttached.isEmpty());
    }

    @Test
    public void aChannelThatFailsToStartIsTriedOnceMore()
    {
        removeRsp();
        mHost.mListed.add(SERIAL);
        mHost.mFailOnce.add("Salem Fire");
        mScheduler.mRepeating.run();
        mScheduler.runLater();

        assertEquals(List.of("Salem Police", "Hudson Fire"), mHost.mStarted);

        mScheduler.runLater();

        assertEquals(List.of("Salem Police", "Hudson Fire", "Salem Fire"), mHost.mStarted);
        assertEquals(RspRecoveryMonitor.CHANNEL_RETRY_DELAY_SECONDS, mScheduler.mLaterDelays.get(1));
        assertTrue(mScheduler.mLater.isEmpty(), "A second failure is not retried again");
    }

    @Test
    public void aChannelStartedByHandMeanwhileIsLeftAlone()
    {
        removeRsp();
        mHost.mRunning.add("Hudson Fire");
        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();
        mScheduler.runLater();

        assertEquals(List.of("Salem Police", "Salem Fire"), mHost.mStarted);
    }

    @Test
    public void twoRemovedDevicesAreRecoveredSeparately()
    {
        removeRsp();
        RspRecoveryMonitor<String>.Removal second = mMonitor.removing("24040A4B60", "RSP1B SER#24040A4B60");
        mHost.mRunning.remove("Andover Police");
        second.complete();

        assertEquals(2, mMonitor.getPendingCount());

        mHost.mListed.add("24040A4B60");
        mScheduler.mRepeating.run();
        mScheduler.runLater();

        assertEquals(1, mMonitor.getPendingCount());
        assertEquals(List.of("Andover Police"), mHost.mStarted);
    }

    @Test
    public void removedAgainBeforeItReturnedKeepsItsChannels()
    {
        removeRsp();
        //Same device reported removed again with nothing running on it - the first set of channels must survive
        mMonitor.removing(SERIAL, ID).complete();

        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();
        mScheduler.runLater();

        assertEquals(3, mHost.mStarted.size());
    }

    @Test
    public void anApiErrorDoesNotEndTheChecks()
    {
        removeRsp();
        mHost.mListThrows = true;
        mScheduler.mRepeating.run();

        assertEquals(1, mMonitor.getPendingCount());

        mHost.mListThrows = false;
        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();

        assertEquals(0, mMonitor.getPendingCount());
    }

    @Test
    public void stopEndsRecovery()
    {
        removeRsp();
        mMonitor.stop();

        assertEquals(0, mMonitor.getPendingCount());
        assertTrue(mScheduler.mCancelled);

        mHost.mListed.add(SERIAL);
        mScheduler.mRepeating.run();

        assertTrue(mHost.mAttached.isEmpty(), "Nothing is attached during shutdown");

        mMonitor.removing(SERIAL, ID).complete();

        assertEquals(0, mMonitor.getPendingCount(), "A removal during shutdown is ignored");
    }

    private static class FakeHost implements RspRecoveryMonitor.Host<String>
    {
        private final Set<String> mRunning = new LinkedHashSet<>();
        private final List<String> mListed = new ArrayList<>();
        private final List<String> mAttached = new ArrayList<>();
        private final List<String> mStarted = new ArrayList<>();
        private final Set<String> mFailOnce = new LinkedHashSet<>();
        private boolean mAttachSucceeds = true;
        private boolean mListThrows;

        @Override
        public List<String> getAvailableSerialNumbers()
        {
            if(mListThrows)
            {
                throw new IllegalStateException("sdrplay_api_GetDevices failed");
            }

            return new ArrayList<>(mListed);
        }

        @Override
        public boolean attach(String serialNumber)
        {
            mAttached.add(serialNumber);
            return mAttachSucceeds;
        }

        @Override
        public Collection<String> getRunningChannels()
        {
            return new ArrayList<>(mRunning);
        }

        @Override
        public boolean start(String channel) throws Exception
        {
            if(mRunning.contains(channel))
            {
                return false;
            }

            if(mFailOnce.remove(channel))
            {
                throw new Exception("No Tuner Available");
            }

            mRunning.add(channel);
            mStarted.add(channel);
            return true;
        }

        @Override
        public String getName(String channel)
        {
            return channel;
        }
    }

    private static class FakeScheduler implements RspRecoveryMonitor.Scheduler
    {
        private Runnable mRepeating;
        private long mFirstDelay;
        private long mInterval;
        private boolean mCancelled;
        private final List<Runnable> mLater = new ArrayList<>();
        private final List<Long> mLaterDelays = new ArrayList<>();

        @Override
        public ScheduledFuture<?> repeat(Runnable task, long firstDelaySeconds, long intervalSeconds)
        {
            mRepeating = task;
            mFirstDelay = firstDelaySeconds;
            mInterval = intervalSeconds;
            mCancelled = false;

            return new ScheduledFuture<Object>()
            {
                @Override public long getDelay(TimeUnit unit) { return 0; }
                @Override public int compareTo(Delayed o) { return 0; }
                @Override public boolean cancel(boolean mayInterruptIfRunning) { mCancelled = true; return true; }
                @Override public boolean isCancelled() { return mCancelled; }
                @Override public boolean isDone() { return mCancelled; }
                @Override public Object get() { return null; }
                @Override public Object get(long timeout, TimeUnit unit) { return null; }
            };
        }

        @Override
        public void later(Runnable task, long delaySeconds)
        {
            mLater.add(task);
            mLaterDelays.add(delaySeconds);
        }

        /**
         * Runs the tasks that are waiting now; tasks they schedule wait for the next call.
         */
        private void runLater()
        {
            List<Runnable> tasks = new ArrayList<>(mLater);
            mLater.clear();

            for(Runnable task: tasks)
            {
                task.run();
            }
        }
    }
}
