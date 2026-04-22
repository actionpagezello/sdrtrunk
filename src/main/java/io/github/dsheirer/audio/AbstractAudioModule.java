/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.audio;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base audio module implementation.
 */
public abstract class AbstractAudioModule extends Module implements IAudioSegmentProvider, IdentifierUpdateListener
{
    private final static Logger mLog = LoggerFactory.getLogger(AbstractAudioModule.class);
    public static final long DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH = 60 * 8000; // 1 minute @ 8kHz
    public static final int DEFAULT_TIMESLOT = 0;
    public static final int DEFAULT_AUDIO_HANGTIME_MS = 0;
    private final int mMaxSegmentAudioSampleLength;
    private Listener<AudioSegment> mAudioSegmentListener;
    protected MutableIdentifierCollection mIdentifierCollection;
    private Broadcaster<IdentifierUpdateNotification> mIdentifierUpdateNotificationBroadcaster = new Broadcaster<>();
    private AliasList mAliasList;
    private AudioSegment mAudioSegment;
    private int mAudioSampleCount = 0;
    private boolean mRecordAudioOverride;
    private volatile boolean mMuted;
    private int mTimeslot;
    private int mAudioHangtimeMs = DEFAULT_AUDIO_HANGTIME_MS;
    private volatile ScheduledFuture<?> mPendingClose;

    /**
     * Constructs an abstract audio module
     *
     * @param aliasList for aliasing identifiers
     * @param maxSegmentAudioSampleLength in milliseconds
     */
    public AbstractAudioModule(AliasList aliasList, int timeslot, long maxSegmentAudioSampleLength)
    {
        mAliasList = aliasList;
        mMaxSegmentAudioSampleLength = (int)(maxSegmentAudioSampleLength * 8); //Convert milliseconds to samples
        mTimeslot = timeslot;
        mIdentifierCollection = new MutableIdentifierCollection(getTimeslot());
        mIdentifierUpdateNotificationBroadcaster.addListener(mIdentifierCollection);
    }

    /**
     * Constructs an abstract audio module with a default maximum audio segment length and a default timeslot 0.
     */
    public AbstractAudioModule(AliasList aliasList)
    {
        this(aliasList, DEFAULT_TIMESLOT, DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH);
    }

    /**
     * Timeslot for this audio module
     */
    protected int getTimeslot()
    {
        return mTimeslot;
    }

    /**
     * Closes the current audio segment.  If a hangtime is configured, the close is delayed
     * to allow any remaining audio buffers to be flushed.  If new audio arrives during the
     * hangtime period, the pending close is cancelled.
     */
    protected void closeAudioSegment()
    {
        if(mAudioHangtimeMs > 0)
        {
            synchronized(this)
            {
                if(mAudioSegment != null && mPendingClose == null)
                {
                    mPendingClose = ThreadPool.SCHEDULED.schedule(() -> {
                        closeAudioSegmentNow();
                    }, mAudioHangtimeMs, TimeUnit.MILLISECONDS);
                }
            }
        }
        else
        {
            closeAudioSegmentNow();
        }
    }

    /**
     * Immediately closes the current audio segment without delay.
     */
    private void closeAudioSegmentNow()
    {
        synchronized(this)
        {
            cancelPendingClose();

            if(mAudioSegment != null)
            {
                mAudioSegment.completeProperty().set(true);
                mIdentifierUpdateNotificationBroadcaster.removeListener(mAudioSegment);
                mAudioSegment.decrementConsumerCount();
                mAudioSegment = null;
            }
        }
    }

    /**
     * Cancels any pending delayed close.  Called when new audio arrives during hangtime.
     */
    private void cancelPendingClose()
    {
        if(mPendingClose != null)
        {
            mPendingClose.cancel(false);
            mPendingClose = null;
        }
    }

    /**
     * Sets the audio hangtime in milliseconds.  When greater than zero, audio segment
     * close is delayed by this amount to allow remaining audio buffers to flush.
     * If new audio arrives during the hangtime, the pending close is cancelled.
     *
     * @param hangtimeMs delay in milliseconds (0 = immediate close)
     */
    public void setAudioHangtimeMs(int hangtimeMs)
    {
        mAudioHangtimeMs = Math.max(0, hangtimeMs);
    }

    /**
     * Current audio hangtime in milliseconds.
     */
    public int getAudioHangtimeMs()
    {
        return mAudioHangtimeMs;
    }

    @Override
    public void stop()
    {
        closeAudioSegmentNow();
    }

    /**
     * Gets the current audio segment, or creates a new audio segment as necessary and broadcasts it to any registered
     * listener(s).
     */
    public AudioSegment getAudioSegment()
    {
        synchronized(this)
        {
            if(mAudioSegment == null)
            {
                mAudioSegment = new AudioSegment(mAliasList, getTimeslot());
                mAudioSegment.incrementConsumerCount();
                mAudioSegment.addIdentifiers(mIdentifierCollection.getIdentifiers());
                mIdentifierUpdateNotificationBroadcaster.addListener(mAudioSegment);

                if(mRecordAudioOverride)
                {
                    mAudioSegment.recordAudioProperty().set(true);
                }

                if(mMuted)
                {
                    mAudioSegment.monitorPriorityProperty().set(Priority.DO_NOT_MONITOR);
                }

                if(mAudioSegmentListener != null)
                {
                    mAudioSegment.incrementConsumerCount();
                    mAudioSegmentListener.receive(mAudioSegment);
                }

                mAudioSampleCount = 0;
            }

            return mAudioSegment;
        }
    }

    public void addAudio(float[] audioBuffer)
    {
        // Cancel any pending hangtime close — new audio means the call continues
        synchronized(this)
        {
            cancelPendingClose();
        }

        AudioSegment audioSegment = getAudioSegment();

        //If the current segment exceeds the max samples length, close it so that a new segment gets generated
        //and then link the segments together.  Use immediate close — segment rollover should not be delayed.
        if(mAudioSampleCount >= mMaxSegmentAudioSampleLength)
        {
            AudioSegment previous = getAudioSegment();
            closeAudioSegmentNow();
            audioSegment = getAudioSegment();
            audioSegment.linkTo(previous);
        }

        try
        {
            audioSegment.addAudio(audioBuffer);
            mAudioSampleCount += audioBuffer.length;
        }
        catch(Exception e)
        {
            closeAudioSegment();
        }
    }

    /**
     * Sets all audio segments as recordable when the argument is true.  Otherwise, defers to the aliased identifiers
     * from the identifier collection to determine whether to record the audio or not.
     * @param recordAudio set to true to mark all audio as recordable.
     */
    public void setRecordAudio(boolean recordAudio)
    {
        mRecordAudioOverride = recordAudio;

        if(mRecordAudioOverride)
        {
            synchronized(this)
            {
                if(mAudioSegment != null)
                {
                    mAudioSegment.recordAudioProperty().set(true);
                }
            }
        }
    }

    /**
     * Sets the mute state for this audio module.  When muted, audio segments produced by this module
     * will have their monitor priority set to DO_NOT_MONITOR, causing the audio playback manager to
     * skip playback.
     *
     * When muting, the current audio segment is force-closed (completed) so that any segment already
     * playing in the audio channel is immediately stopped.  New segments created afterward will inherit
     * the DO_NOT_MONITOR priority.
     *
     * When unmuting, the mute flag is cleared so that the next audio segment will use the normal priority.
     *
     * @param muted true to mute, false to unmute
     */
    public void setMuted(boolean muted)
    {
        mMuted = muted;

        if(muted)
        {
            synchronized(this)
            {
                //Mark the current segment as DO_NOT_MONITOR so AudioPlaybackManager drops it
                //on its next processing cycle, then close it so no more audio is added.
                if(mAudioSegment != null)
                {
                    mAudioSegment.monitorPriorityProperty().set(Priority.DO_NOT_MONITOR);
                }
            }
        }

        //Close the current segment in both mute and unmute cases (immediately, no hangtime).
        //On mute: stops the currently playing audio immediately.
        //On unmute: discards the DO_NOT_MONITOR segment so the next addAudio() creates
        //a fresh segment with normal priority that AudioPlaybackManager will play.
        closeAudioSegmentNow();
    }

    /**
     * Indicates if this audio module is currently muted.
     */
    public boolean isMuted()
    {
        return mMuted;
    }

    /**
     * Receive updated identifiers from decoder state(s).
     */
    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mIdentifierUpdateNotificationBroadcaster;
    }

    /**
     * Identifier collection containing the current set of identifiers received from the decoder state(s).
     */
    public MutableIdentifierCollection getIdentifierCollection()
    {
        return mIdentifierCollection;
    }

    /**
     * Registers an audio segment listener to receive the output from this audio module.
     */
    @Override
    public void setAudioSegmentListener(Listener<AudioSegment> listener)
    {
        mAudioSegmentListener = listener;
    }

    /**
     * Unregisters the audio segment listener from receiving audio segments from this module.
     */
    @Override
    public void removeAudioSegmentListener()
    {
        mAudioSegmentListener = null;
    }
}
