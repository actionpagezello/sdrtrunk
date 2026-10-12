/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioSegmentRecorder;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio streaming manager monitors audio segments through completion and creates temporary streaming recordings on
 * disk and enqueues the temporary recording for streaming.
 */
public class AudioStreamingManager implements Listener<AudioSegment>
{
    private final static Logger mLog = LoggerFactory.getLogger(AudioStreamingManager.class);
    private LinkedTransferQueue<AudioSegment> mNewAudioSegments = new LinkedTransferQueue<>();
    private List<AudioSegment> mAudioSegments = new ArrayList<>();
    private Listener<AudioRecording> mAudioRecordingListener;
    private BroadcastFormat mBroadcastFormat;
    private UserPreferences mUserPreferences;
    private ScheduledFuture<?> mAudioSegmentProcessorFuture;
    private int mNextRecordingNumber = 1;
    private BroadcastModel mBroadcastModel;

    // Real-time streaming state: maps audio segment to the real-time broadcasters (Zello) streaming it
    //AP-fork: per-broadcaster state instead of one shared buffer index. See forwardRealTimeAudio().
    private java.util.Map<AudioSegment, List<RealTimeStream>> mRealTimeStreams =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * AP-fork: when each segment was first seen by forwardRealTimeAudio(), so a stream that starts on a later pass can
     * report how late it started.
     */
    private java.util.Map<AudioSegment, Long> mFirstForwardTime = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * AP-fork: most audio a stream that starts late is given from before its start, in milliseconds.  The Zello
     * broadcaster holds at most 30 frames (~1.8 s) until the server assigns the stream id and evicts older ones, then
     * sends them as an 8-frame burst plus a paced drain.  Capping the backlog below that keeps a late start on the same
     * path a normal call start already uses for its CTCSS-confirmation flush (up to ~0.5 s), rather than leaning on the
     * eviction or on how quickly the stream id arrives.
     */
    private static final long MAX_LATE_START_BACKLOG_MS = 1500;

    /**
     * AP-fork: one real-time broadcaster streaming one segment, and the next audio buffer of that segment it has yet to
     * receive.  Each broadcaster keeps its own position because they no longer necessarily start on the same pass.
     */
    private static class RealTimeStream
    {
        private final IRealTimeAudioBroadcaster mBroadcaster;
        private final long mLateMs;
        private int mNextBuffer = 0;

        RealTimeStream(IRealTimeAudioBroadcaster broadcaster, long lateMs)
        {
            mBroadcaster = broadcaster;
            mLateMs = lateMs;
        }
    }

    /**
     * Constructs an instance
     * @param listener to receive completed audio recordings
     * @param broadcastFormat for temporary recordings
     * @param userPreferences to manage recording directories
     */
    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat, UserPreferences userPreferences)
    {
        mAudioRecordingListener = listener;
        mBroadcastFormat = broadcastFormat;
        mUserPreferences = userPreferences;

        // If the listener is a BroadcastModel, store it for real-time broadcaster lookups
        if(listener instanceof BroadcastModel bm)
        {
            mBroadcastModel = bm;
        }
    }

    /**
     * Sets the broadcast model to enable real-time audio routing to Zello and other
     * real-time broadcasters.
     * @param broadcastModel the broadcast model
     */
    public void setBroadcastModel(BroadcastModel broadcastModel)
    {
        mBroadcastModel = broadcastModel;
    }

    /**
     * Primary receive method
     */
    @Override
    public void receive(AudioSegment audioSegment)
    {
        mNewAudioSegments.add(audioSegment);
    }

    /**
     * Starts the scheduled audio segment processor
     */
    public void start()
    {
        if(mAudioSegmentProcessorFuture == null)
        {
            mAudioSegmentProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioSegmentProcessor(),
                0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the scheduled audio segment processor
     */
    public void stop()
    {
        if(mAudioSegmentProcessorFuture != null)
        {
            mAudioSegmentProcessorFuture.cancel(true);
            mAudioSegmentProcessorFuture = null;
        }

        for(AudioSegment audioSegment: mNewAudioSegments)
        {
            audioSegment.decrementConsumerCount();
        }

        mNewAudioSegments.clear();

        for(AudioSegment audioSegment: mAudioSegments)
        {
            stopRealTimeStreams(audioSegment);
            audioSegment.decrementConsumerCount();
        }

        mAudioSegments.clear();
        mRealTimeStreams.clear();
        mFirstForwardTime.clear();
    }

    /**
     * Main processing method to process audio segments
     */
    private void processAudioSegments()
    {
        mNewAudioSegments.drainTo(mAudioSegments);

        Iterator<AudioSegment> it = mAudioSegments.iterator();
        AudioSegment audioSegment;
        while(it.hasNext())
        {
            audioSegment = it.next();

            if(audioSegment.isDuplicate() && mUserPreferences.getCallManagementPreference().isDuplicateStreamingSuppressionEnabled())
            {
                it.remove();
                logCallSegment(audioSegment, "DUPLICATE - suppressed, not streamed");
                stopRealTimeStreams(audioSegment);
                audioSegment.decrementConsumerCount();
            }
            else
            {
                // Forward new audio buffers to any active real-time broadcasters
                forwardRealTimeAudio(audioSegment);

                if(audioSegment.completeProperty().get())
                {
                    it.remove();
                    logCallSegment(audioSegment, audioSegment.hasBroadcastChannels() ? "complete" :
                            "complete - no stream configured for this call");

                if(mAudioRecordingListener != null && audioSegment.hasBroadcastChannels())
                {
                    IdentifierCollection identifiers =
                            new IdentifierCollection(audioSegment.getIdentifierCollection().getIdentifiers());

                    if(identifiers.getToIdentifier() instanceof PatchGroupIdentifier patchGroupIdentifier)
                    {
                        if(mUserPreferences.getCallManagementPreference()
                                .getPatchGroupStreamingOption() == PatchGroupStreamingOption.TALKGROUPS)
                        {
                            //Decompose the patch group into the individual (patched) talkgroups and process the audio
                            //segment for each patched talkgroup.
                            PatchGroup patchGroup = patchGroupIdentifier.getValue();

                            List<Identifier> ids = new ArrayList<>();
                            ids.addAll(patchGroup.getPatchedTalkgroupIdentifiers());
                            ids.addAll(patchGroup.getPatchedRadioIdentifiers());

                            //If there are no patched radios/talkgroups, override user preference and stream as a patch group
                            if(ids.isEmpty() || audioSegment.getAliasList() == null)
                            {
                                processAudioSegment(audioSegment, identifiers, audioSegment.getBroadcastChannels());
                            }
                            else
                            {
                                AliasList aliasList = audioSegment.getAliasList();

                                for(Identifier identifier: ids)
                                {
                                    List<Alias> aliases = aliasList.getAliases(identifier);
                                    Set<BroadcastChannel> broadcastChannels = new HashSet<>();
                                    for(Alias alias: aliases)
                                    {
                                        broadcastChannels.addAll(alias.getBroadcastChannels());
                                    }

                                    if(!broadcastChannels.isEmpty())
                                    {
                                        MutableIdentifierCollection decomposedIdentifiers =
                                                new MutableIdentifierCollection(identifiers.getIdentifiers());
                                        //Remove patch group TO identifier & replace with the patched talkgroup/radio
                                        decomposedIdentifiers.remove(Role.TO);
                                        decomposedIdentifiers.update(identifier);
                                        processAudioSegment(audioSegment, decomposedIdentifiers, broadcastChannels);
                                    }
                                }
                            }
                        }
                        else
                        {
                            processAudioSegment(audioSegment, identifiers, audioSegment.getBroadcastChannels());
                        }
                    }
                    else
                    {
                        processAudioSegment(audioSegment, identifiers, audioSegment.getBroadcastChannels());
                    }
                }

                    audioSegment.decrementConsumerCount();
                    stopRealTimeStreams(audioSegment);
                }
            }
        }
    }

    /**
     * Starts or continues real-time audio forwarding for an audio segment.
     * Finds real-time broadcasters (like Zello) that match the segment's broadcast channels
     * and forwards new audio buffers to them incrementally.
     *
     * AP-fork: every pass offers the segment to each of its real-time broadcasters that is not yet streaming it, not
     * only on the first pass that found one ready.  A broadcaster that becomes ready later starts then and receives the
     * segment from its first buffer, or the last MAX_LATE_START_BACKLOG_MS of it if it is later than that; the Zello
     * broadcaster paces a backlog out (pending frames, 8-frame burst, 55 ms drain), the same path the CTCSS-confirmation
     * flush has always used at call start.
     *
     * Previously the broadcaster list was built once, on the first pass where ANY of them was ready, and never
     * revisited - so a broadcaster still waiting on the server's stop acknowledgement for the previous call missed the
     * whole of the next one.  Somerville 2026-10-12 18:38:19: a 40 s Brookline Fire call went to its Work stream but
     * not its F&F stream, whose acknowledgement arrived 108 ms after the call was routed.  Across 19.7 h of the
     * ap-15.9.18 call line, 33 calls of 2 s or more (275 s of audio) were lost this way, every one of them on a
     * segment where another stream of the same call did go live.
     */
    private void forwardRealTimeAudio(AudioSegment audioSegment)
    {
        if(mBroadcastModel == null || !audioSegment.hasBroadcastChannels())
        {
            return;
        }

        long now = System.currentTimeMillis();
        long firstSeen = mFirstForwardTime.computeIfAbsent(audioSegment, segment -> now);
        List<RealTimeStream> streams = mRealTimeStreams.computeIfAbsent(audioSegment,
                segment -> new java.util.concurrent.CopyOnWriteArrayList<>());

        // Start real-time streams on any broadcaster of this segment that is ready and not already streaming it
        for(BroadcastChannel broadcastChannel : audioSegment.getBroadcastChannels())
        {
            AbstractAudioBroadcaster<?> broadcaster = mBroadcastModel.getBroadcaster(broadcastChannel.getChannelName());

            if(broadcaster instanceof IRealTimeAudioBroadcaster rtb && !isStreaming(streams, rtb) && rtb.isRealTimeReady())
            {
                IdentifierCollection identifiers = audioSegment.getIdentifierCollection() != null
                        ? new IdentifierCollection(audioSegment.getIdentifierCollection().getIdentifiers())
                        : null;
                rtb.startRealTimeStream(identifiers);
                long lateMs = now - firstSeen;
                RealTimeStream stream = new RealTimeStream(rtb, lateMs);

                if(lateMs > 0)
                {
                    stream.mNextBuffer = getLateStartIndex(audioSegment);
                    mLog.debug("Real-time stream [{}] started {}ms after the call reached it - it was not ready " +
                            "earlier - sending the call from buffer {} of {}", broadcastChannel.getChannelName(), lateMs,
                            stream.mNextBuffer, audioSegment.getAudioBuffers().size());
                }

                streams.add(stream);
            }
        }

        // Forward any new audio buffers, each broadcaster from its own position
        if(!streams.isEmpty())
        {
            int currentSize = audioSegment.getAudioBuffers().size();

            for(RealTimeStream stream : streams)
            {
                for(int i = stream.mNextBuffer; i < currentSize; i++)
                {
                    float[] buffer = audioSegment.getAudioBuffer(i);

                    if(buffer != null)
                    {
                        stream.mBroadcaster.receiveRealTimeAudio(buffer);
                    }
                }

                stream.mNextBuffer = currentSize;
            }
        }
    }

    /**
     * AP-fork: index of the first audio buffer to send a stream that starts late - the start of the call, or the
     * buffer that leaves no more than MAX_LATE_START_BACKLOG_MS of audio behind it.  8 kHz audio, 8 samples per ms.
     */
    private static int getLateStartIndex(AudioSegment audioSegment)
    {
        long budget = MAX_LATE_START_BACKLOG_MS * 8;
        int index = audioSegment.getAudioBuffers().size();

        while(index > 0)
        {
            float[] buffer = audioSegment.getAudioBuffer(index - 1);
            int length = buffer != null ? buffer.length : 0;

            if(length > budget)
            {
                break;
            }

            budget -= length;
            index--;
        }

        return index;
    }

    /**
     * AP-fork: true when the broadcaster is already streaming this segment.
     */
    private static boolean isStreaming(List<RealTimeStream> streams, IRealTimeAudioBroadcaster broadcaster)
    {
        for(RealTimeStream stream : streams)
        {
            if(stream.mBroadcaster == broadcaster)
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Stops any active real-time streams for the given audio segment.
     */
    private void stopRealTimeStreams(AudioSegment audioSegment)
    {
        List<RealTimeStream> streams = mRealTimeStreams.remove(audioSegment);
        mFirstForwardTime.remove(audioSegment);

        if(streams != null)
        {
            for(RealTimeStream stream : streams)
            {
                try
                {
                    stream.mBroadcaster.stopRealTimeStream();
                }
                catch(Exception e)
                {
                    mLog.error("Error stopping real-time stream", e);
                }
            }
        }
    }

    /**
     * AP-fork: one line per call as it leaves the streaming manager - which channel it came from, when it started (the
     * same start time ThinLine files the call under), how long it ran, and what happened to it on each stream it was
     * routed to.  Measurement only.
     *
     * Two problems had no answer in the logs.  A log time could not be matched to a ThinLine recording, so a window the
     * TDMA detector refused could not be checked by ear.  And when a feed went quiet nothing distinguished a silent
     * channel from one still receiving whose audio reached no stream - the Daly RSP1B dropout of 2026-10-10 was found
     * only through the tuner warning, not through anything the calls themselves logged.
     *
     * Per stream: LIVE = a real-time stream (Zello) was started for this call, LIVE (late Nms) = started N ms after the
     * call reached it because it was not ready sooner, sent from the start of the call (or its last 1.5 s); FILE = handed
     * to a recording based broadcaster (ThinLine and similar) at completion; NOT READY = a real-time broadcaster that
     * was not ready at any point while the call lasted, so it was not streamed; MISSING = no broadcaster with that name
     * exists.
     *
     * Must be called before stopRealTimeStreams(), which discards the record of which streams were live.
     *
     * @param audioSegment the call
     * @param outcome what happened to it here
     */
    private void logCallSegment(AudioSegment audioSegment, String outcome)
    {
        try
        {
            IdentifierCollection ids = audioSegment.getIdentifierCollection();
            Identifier channel = ids != null ? ids.getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY) : null;
            Identifier to = ids != null ? ids.getToIdentifier() : null;

            List<RealTimeStream> live = mRealTimeStreams.get(audioSegment);
            StringBuilder streams = new StringBuilder();

            for(BroadcastChannel broadcastChannel : audioSegment.getBroadcastChannels())
            {
                String name = broadcastChannel.getChannelName();
                AbstractAudioBroadcaster<?> broadcaster = mBroadcastModel != null ? mBroadcastModel.getBroadcaster(name) : null;
                String state;

                if(broadcaster == null)
                {
                    state = "MISSING";
                }
                else if(broadcaster instanceof IRealTimeAudioBroadcaster rtb)
                {
                    RealTimeStream stream = null;

                    if(live != null)
                    {
                        for(RealTimeStream candidate : live)
                        {
                            if(candidate.mBroadcaster == rtb)
                            {
                                stream = candidate;
                            }
                        }
                    }

                    state = stream == null ? "NOT READY" :
                            (stream.mLateMs > 0 ? "LIVE (late " + stream.mLateMs + "ms)" : "LIVE");
                }
                else
                {
                    state = "FILE";
                }

                if(streams.length() > 0)
                {
                    streams.append(", ");
                }

                streams.append(name).append("=").append(state);
            }

            long start = audioSegment.getStartTimestamp();

            mLog.info("Call [{}] to [{}] started {} ran {}ms - {} - streams [{}]",
                    channel != null ? channel : "unknown channel", to != null ? to : "none",
                    start > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start)) : "unknown",
                    audioSegment.getDuration(), outcome, streams);
        }
        catch(Exception e)
        {
            //Measurement only - never let it disturb streaming
            mLog.debug("Unable to log call segment", e);
        }
    }

    /**
     * Processes an audio segment for streaming by creating a temporary MP3 recording and submitting the recording
     * to the specific broadcast channel(s).
     * @param audioSegment to process for streaming
     * @param identifierCollection to use for the streamed audio recording.
     * @param broadcastChannels to receive the audio recording
     */
    private void processAudioSegment(AudioSegment audioSegment, IdentifierCollection identifierCollection,
                                     Set<BroadcastChannel> broadcastChannels)
    {
        Path path = getTemporaryRecordingPath();
        long length = 0;

        for(float[] audioBuffer: audioSegment.getAudioBuffers())
        {
            length += audioBuffer.length;
        }

        length /= 8; //Sample rate is 8000 samples per second, or 8 samples per millisecond.

        try
        {
            AudioSegmentRecorder.record(audioSegment, path, RecordFormat.MP3, mUserPreferences, identifierCollection);

            AudioRecording audioRecording = new AudioRecording(path, broadcastChannels, identifierCollection,
                    audioSegment.getStartTimestamp(), length);
            mAudioRecordingListener.receive(audioRecording);
        }
        catch(IOException ioe)
        {
            mLog.error("Error recording temporary stream MP3");
        }
    }

    /**
     * Creates a temporary streaming recording file path
     */
    private Path getTemporaryRecordingPath()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(BroadcastModel.TEMPORARY_STREAM_FILE_SUFFIX);

        //Check for integer overflow and readjust negative value to 0
        if(mNextRecordingNumber < 0)
        {
            mNextRecordingNumber = 1;
        }

        int recordingNumber = mNextRecordingNumber++;

        sb.append(recordingNumber).append("_");
        sb.append(TimeStamp.getLongTimeStamp("_"));
        sb.append(mBroadcastFormat.getFileExtension());

        Path temporaryRecordingPath = mUserPreferences.getDirectoryPreference().getDirectoryStreaming().resolve(sb.toString());

        return temporaryRecordingPath;
    }

    /**
     * Scheduled runnable to process audio segments.
     */
    public class AudioSegmentProcessor implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Throwable t)
            {
                mLog.error("Error processing audio segments for streaming", t);
            }
        }
    }
}
