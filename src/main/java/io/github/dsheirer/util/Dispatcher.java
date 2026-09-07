/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.util;

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.heartbeat.HeartbeatManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Threaded scheduled processor for receiving elements from a separate producer thread and forwarding those buffers to a
 * registered listener on this consumer/dispatcher thread.  Internally uses a single-thread thread pool to effect a
 * timer-based interval for processing to avoid excessive context switching inherent in a blocking queue.  Sizes the
 * thread pool to a single thread to ensure Garbage Collector can efficiently clean objects created on the thread.
 *
 * The queue is bounded.  When the consumer cannot keep pace with the producer, the oldest queued elements are discarded
 * so that the queue cannot grow without limit.  This matters because producers here are USB transfer callback threads
 * that never block: without a bound, a consumer stall converts directly into unbounded heap growth, and because queued
 * elements are strongly reachable they are live objects that the garbage collector cannot reclaim.  Dropping the oldest
 * elements is the correct policy for real-time DSP, where the newest samples are the useful ones.
 */
public class Dispatcher<E> implements Listener<E>
{
    private final static Logger mLog = LoggerFactory.getLogger(Dispatcher.class);

    /**
     * Default maximum queue size, used by constructors that do not specify one.  Callers that know their element size
     * or production rate should specify a bound derived from those values instead of relying on this.
     */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 500;

    /**
     * Minimum interval between queue overflow log messages, so that a sustained overflow does not itself flood the log.
     */
    private static final long DROP_LOG_INTERVAL_MS = 10_000;

    /**
     * Queue occupancy, as a fraction of capacity, below which an overflow episode is considered recovered.
     */
    private static final double RECOVERY_THRESHOLD = 0.25;

    private final LinkedTransferQueue<E> mQueue = new LinkedTransferQueue<>();
    private Listener<E> mListener;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private String mThreadName;
    private ScheduledExecutorService mExecutorService;
    private ScheduledFuture<?> mScheduledFuture;
    private final long mInterval;
    private HeartbeatManager mHeartbeatManager;

    /**
     * Maximum number of elements the queue may hold before the oldest are discarded.
     */
    private final int mMaxQueueSize;

    /**
     * Running count of queued elements.  Maintained separately because LinkedTransferQueue.size() is O(n) and would
     * itself become a hotspot under exactly the backlog conditions this bound exists to handle.
     */
    private final AtomicInteger mQueueSize = new AtomicInteger();

    /**
     * Cumulative count of elements discarded due to queue overflow.
     */
    private final AtomicLong mDropCount = new AtomicLong();

    /**
     * Reused drain target.  Accessed only from the processor thread.  Reused rather than reallocated so that a
     * backlogged queue is not copied into a freshly allocated list on every processing interval.
     */
    private final List<E> mDrainList;

    //Overflow reporting state.  Accessed only from the processor thread.
    private long mLastDropLogTimestamp;
    private long mDropCountAtLastLog;
    private boolean mDropEpisodeActive;

    /**
     * Constructs an instance of a Dispatcher with integrated heartbeat support.
     * @param threadName to name the dispatcher thread
     * @param interval for processing each batch in milliseconds.
     * @param heartbeatManager to receive a heartbeat command at each processing interval.
     */
    public Dispatcher(String threadName, long interval, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval, DEFAULT_MAX_QUEUE_SIZE, heartbeatManager);
    }

    /**
     * Constructs an instance of a Dispatcher with integrated heartbeat support and an explicit queue bound.
     * @param threadName to name the dispatcher thread
     * @param interval for processing each batch in milliseconds.
     * @param maxQueueSize maximum queued elements before the oldest are discarded.
     * @param heartbeatManager to receive a heartbeat command at each processing interval.
     */
    public Dispatcher(String threadName, long interval, int maxQueueSize, HeartbeatManager heartbeatManager)
    {
        this(threadName, interval, maxQueueSize);
        mHeartbeatManager = heartbeatManager;
    }

    /**
     * Constructs an instance
     * @param threadName to name the dispatcher thread
     * @param interval for processing each batch in milliseconds.
     */
    public Dispatcher(String threadName, long interval)
    {
        this(threadName, interval, DEFAULT_MAX_QUEUE_SIZE);
    }

    /**
     * Constructs an instance with an explicit queue bound.
     * @param threadName to name the dispatcher thread
     * @param interval for processing each batch in milliseconds.
     * @param maxQueueSize maximum queued elements before the oldest are discarded.  Must be greater than zero.
     */
    public Dispatcher(String threadName, long interval, int maxQueueSize)
    {
        if(maxQueueSize < 1)
        {
            throw new IllegalArgumentException("Max queue size must be greater than zero");
        }

        mThreadName = threadName;
        mInterval = interval;
        mMaxQueueSize = maxQueueSize;
        mDrainList = new ArrayList<>(Math.min(maxQueueSize, 1024));
    }

    /**
     * Current number of elements awaiting processing.
     */
    public int getQueueSize()
    {
        return mQueueSize.get();
    }

    /**
     * Maximum number of elements this dispatcher will queue before discarding the oldest.
     */
    public int getMaxQueueSize()
    {
        return mMaxQueueSize;
    }

    /**
     * Cumulative count of elements discarded due to queue overflow since this dispatcher was constructed.
     */
    public long getDropCount()
    {
        return mDropCount.get();
    }

    /**
     * Sets the thread name.  If this dispatcher is already started, this has no effect.
     * @param threadName to use for this dispatcher.
     */
    public void setThreadName(String threadName)
    {
        mThreadName = threadName;
    }

    /**
     * Sets or changes the listener to receive buffers from this processor.
     * @param listener to receive buffers
     */
    public void setListener(Listener<E> listener)
    {
        mListener = listener;
    }

    /**
     * Primary input method for adding buffers to this processor.  Note: incoming buffers will be ignored if this
     * processor is in a stopped state.  You must invoke start() to allow incoming buffers and initiate buffer
     * processing.
     *
     * @param e to enqueue for distribution to a registered listener
     */
    public void receive(E e)
    {
        if(mRunning.get())
        {
            mQueue.add(e);

            if(mQueueSize.incrementAndGet() > mMaxQueueSize)
            {
                int dropped = 0;

                //Discard oldest elements until back within the bound.  Note: the processor thread drains concurrently,
                //so poll() can legitimately return null here - stop if it does.
                while(mQueueSize.get() > mMaxQueueSize)
                {
                    if(mQueue.poll() != null)
                    {
                        mQueueSize.decrementAndGet();
                        dropped++;
                    }
                    else
                    {
                        break;
                    }
                }

                if(dropped > 0)
                {
                    mDropCount.addAndGet(dropped);
                }
            }
        }
    }

    /**
     * Starts this buffer processor and allows queuing of incoming buffers.
     */
    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
            }

            if(mExecutorService != null)
            {
                mExecutorService.shutdown();
                mExecutorService = null;
            }

            mQueue.clear();
            mQueueSize.set(0);
            mExecutorService = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(mThreadName));

            Runnable r = (mHeartbeatManager != null ? new ProcessorWithHeartbeat() : new Processor());
            mScheduledFuture = mExecutorService.scheduleAtFixedRate(r, 0, mInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops this buffer processor and waits up to two seconds for the processing thread to terminate.
     */
    public void stop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
                mScheduledFuture = null;
                mQueue.clear();
                mQueueSize.set(0);
            }

            if(mExecutorService != null)
            {
                mExecutorService.shutdown();
                mExecutorService = null;
            }
        }
    }

    /**
     * Stops this buffer processor and flushes the queue to the listener
     */
    public void flushAndStop()
    {
        if(mRunning.compareAndSet(true, false))
        {
            if(mScheduledFuture != null)
            {
                //Note: this has to be false because downstream implementations may have acquired locks and they must
                //be able to release those locks or we'll get a deadlock situation.
                mScheduledFuture.cancel(false);
                mScheduledFuture = null;
            }

            if(mExecutorService != null)
            {
                mExecutorService.shutdown();
                mExecutorService = null;
            }

            List<E> elements = new ArrayList<>(Math.min(mMaxQueueSize, 1024));

            int drained = mQueue.drainTo(elements, mMaxQueueSize);

            if(drained > 0)
            {
                mQueueSize.addAndGet(-drained);
            }

            for(E element: elements)
            {
                if(mListener != null)
                {
                    try
                    {
                        mListener.receive(element);
                    }
                    catch(Throwable t)
                    {
                        mLog.error("Error while flusing and dispatching element [" + element.getClass() + "] to listener [" +
                                mListener.getClass() + "]", t);
                    }
                }
            }

            //Anything beyond the bound is discarded rather than flushed - this is a shutdown path.
            mQueue.clear();
            mQueueSize.set(0);
        }
    }

    /**
     * Indicates if this processor is currently running
     */
    public boolean isRunning()
    {
        return mRunning.get();
    }

    /**
     * Processes elements from the queue.  Note: this should only be invoked on the Processor thread.
     */
    private void process()
    {
        mDrainList.clear();

        int drained = mQueue.drainTo(mDrainList, mMaxQueueSize);

        if(drained > 0)
        {
            mQueueSize.addAndGet(-drained);
        }

        for(int x = 0; x < mDrainList.size(); x++)
        {
            E element = mDrainList.get(x);

            if(mRunning.get() && mListener != null)
            {
                try
                {
                    mListener.receive(element);
                }
                catch(Throwable t)
                {
                    mLog.error("Error while dispatching element [" + element.getClass() + "] to listener [" +
                            mListener.getClass() + "]", t);
                }
            }
        }

        //Release references to the dispatched batch so it can be collected before the next interval.
        mDrainList.clear();

        reportOverflow();
    }

    /**
     * Reports queue overflow, rate limited so that a sustained overflow does not flood the log.  Invoked from the
     * processor thread only.
     */
    private void reportOverflow()
    {
        long drops = mDropCount.get();

        if(drops > mDropCountAtLastLog)
        {
            long now = System.currentTimeMillis();

            if(!mDropEpisodeActive || (now - mLastDropLogTimestamp) >= DROP_LOG_INTERVAL_MS)
            {
                mLog.warn("Dispatcher [{}] queue overflow - discarded [{}] oldest elements ([{}] total) - consumer is " +
                        "not keeping pace with producer - queue bound is [{}] elements", mThreadName,
                        (drops - mDropCountAtLastLog), drops, mMaxQueueSize);
                mLastDropLogTimestamp = now;
                mDropCountAtLastLog = drops;
                mDropEpisodeActive = true;
            }
        }
        else if(mDropEpisodeActive && mQueueSize.get() < (mMaxQueueSize * RECOVERY_THRESHOLD))
        {
            mLog.info("Dispatcher [{}] queue recovered - [{}] elements discarded in total", mThreadName, drops);
            mDropEpisodeActive = false;
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener
     */
    class Processor implements Runnable
    {
        private final AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                process();
                mRunning.set(false);
            }
        }
    }

    /**
     * Processor to service the buffer queue and distribute the buffers to the registered listener.  Includes a
     * support for commanding a heart beat with each processing interval.
     */
    class ProcessorWithHeartbeat implements Runnable
    {
        private final AtomicBoolean mRunning = new AtomicBoolean();

        @Override
        public void run()
        {
            if(mRunning.compareAndSet(false, true))
            {
                process();

                try
                {
                    mHeartbeatManager.broadcast();
                }
                catch(Throwable t)
                {
                    mLog.error("Error broadcasting heartbeat during Dispatcher processing interval", t);
                }

                mRunning.set(false);
            }
        }
    }
}
