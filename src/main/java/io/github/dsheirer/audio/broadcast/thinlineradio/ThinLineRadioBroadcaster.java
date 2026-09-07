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

package io.github.dsheirer.audio.broadcast.thinlineradio;

import com.google.common.net.HttpHeaders;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.rdioscanner.FormField;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerBuilder;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.gui.playlist.radioreference.RadioReferenceDecoder;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.ConfigurationLongIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audio broadcaster to push completed audio recordings to the ThinLine Radio call upload API.
 *
 * Uses the same multipart form API as Rdio Scanner.
 */
public class ThinLineRadioBroadcaster extends AbstractAudioBroadcaster<ThinLineRadioConfiguration>
{
    private final static Logger mLog = LoggerFactory.getLogger(ThinLineRadioBroadcaster.class);

    private static final String ENCODING_TYPE_MP3 = "mp3";
    private static final String MULTIPART_TYPE = "multipart";
    private static final String DEFAULT_SUBTYPE = "form-data";
    private static final String MULTIPART_FORM_DATA = MULTIPART_TYPE + "/" + DEFAULT_SUBTYPE;
    private Queue<AudioRecording> mAudioRecordingQueue = new LinkedTransferQueue<>();
    private ScheduledFuture<?> mAudioRecordingProcessorFuture;
    private HttpClient mHttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();
    private long mLastConnectionAttempt;
    private long mConnectionAttemptInterval = 5000; //Every 5 seconds
    private AliasModel mAliasModel;

    /**
     * Constructs an instance of the broadcaster
     * @param config to use
     * @param aliasModel for access to aliases
     */
    public ThinLineRadioBroadcaster(ThinLineRadioConfiguration config, InputAudioFormat inputAudioFormat,
                                    MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(config);
        mAliasModel = aliasModel;
    }

    /**
     * Starts the audio recording processor thread
     */
    @Override
    public void start()
    {
        setBroadcastState(BroadcastState.CONNECTING);
        String response = testConnection(getBroadcastConfiguration());
        mLastConnectionAttempt = System.currentTimeMillis();

        if(response != null && response.toLowerCase().startsWith("incomplete call data: no talkgroup"))
        {
            mLog.info("ThinLine Radio connected to [{}]", getBroadcastConfiguration().getHost());
            setBroadcastState(BroadcastState.CONNECTED);
        }
        else
        {
            mLog.error("Error connecting to ThinLine Radio server on startup [" + response + "]");
            setBroadcastState(BroadcastState.ERROR);
        }

        if(mAudioRecordingProcessorFuture == null)
        {
            mAudioRecordingProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioRecordingProcessor(),
                0, 500, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the audio recording processor thread
     */
    @Override
    public void stop()
    {
        if(mAudioRecordingProcessorFuture != null)
        {
            mAudioRecordingProcessorFuture.cancel(true);
            mAudioRecordingProcessorFuture = null;
            dispose();
            setBroadcastState(BroadcastState.DISCONNECTED);
        }
    }

    /**
     * Prepares for disposal
     */
    @Override
    public void dispose()
    {
        AudioRecording audioRecording = mAudioRecordingQueue.poll();

        while(audioRecording != null)
        {
            audioRecording.removePendingReplay();
            audioRecording = mAudioRecordingQueue.poll();
        }
    }

    /**
     * Indicates if this broadcaster has a successful connection to the remote server.
     */
    private boolean connected()
    {
        if(getBroadcastState() != BroadcastState.CONNECTED &&
            (System.currentTimeMillis() - mLastConnectionAttempt > mConnectionAttemptInterval))
        {
            BroadcastState previousState = getBroadcastState();
            setBroadcastState(BroadcastState.CONNECTING);

            mLog.debug("ThinLine Radio connection test to [{}] - previous state [{}] queue depth [{}]",
                getBroadcastConfiguration().getHost(), previousState, mAudioRecordingQueue.size());

            String response = testConnection(getBroadcastConfiguration());
            mLastConnectionAttempt = System.currentTimeMillis();

            if(response != null && response.toLowerCase().startsWith("incomplete call data: no talkgroup"))
            {
                if(previousState != BroadcastState.CONNECTED)
                {
                    mLog.info("ThinLine Radio reconnected to [{}] after state [{}]",
                        getBroadcastConfiguration().getHost(), previousState);
                }

                setBroadcastState(BroadcastState.CONNECTED);
            }
            else
            {
                //Previously silent - a failing feed produced no log output at all between uploads.
                mLog.warn("ThinLine Radio connection test failed for [{}] - response [{}] - retrying in {} ms",
                    getBroadcastConfiguration().getHost(), response, mConnectionAttemptInterval);
                setBroadcastState(BroadcastState.ERROR);
            }
        }

        return getBroadcastState() == BroadcastState.CONNECTED;
    }

    @Override
    public int getAudioQueueSize()
    {
        return mAudioRecordingQueue.size();
    }

    @Override
    public void receive(AudioRecording audioRecording)
    {
        mAudioRecordingQueue.offer(audioRecording);

        if(mLog.isDebugEnabled())
        {
            mLog.debug("ThinLine queued recording - talkgroup [{}] {}s audio - queue depth now [{}] state [{}]",
                getTo(audioRecording), String.format("%.1f", audioRecording.getRecordingLength() / 1E3f),
                mAudioRecordingQueue.size(), getBroadcastState());
        }

        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));
    }

    private boolean isValid(AudioRecording audioRecording)
    {
        return audioRecording != null && System.currentTimeMillis() - audioRecording.getStartTime() <=
            getBroadcastConfiguration().getMaximumRecordingAge();
    }

    private void processRecordingQueue()
    {
        while(connected() && !mAudioRecordingQueue.isEmpty())
        {
            final AudioRecording audioRecording = mAudioRecordingQueue.poll();
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_QUEUE_CHANGE));

            if(isValid(audioRecording) && audioRecording.getRecordingLength() > 0)
            {
                float durationSeconds = (float)(audioRecording.getRecordingLength() / 1E3f);
                long timestampSeconds = (int)(audioRecording.getStartTime() / 1E3);
                String talkgroup = getTo(audioRecording);
                String radioId = getFrom(audioRecording);
                String talkerAlias = getTalkerAlias(audioRecording);
                Long frequency = getFrequency(audioRecording);
                String patches = getPatches(audioRecording);
                String talkgroupLabel = getTalkgroupLabel(audioRecording);
                String talkgroupGroup = getTalkgroupGroup(audioRecording);
                String systemLabel = getSystemLabel(audioRecording);
                String path = audioRecording.getPath().toString();
                String audioName = path.substring(path.substring(0, path.lastIndexOf("_")).lastIndexOf("_") + 1);

                try
                {
                    byte[] audioBytes = null;

                    try
                    {
                        audioBytes = Files.readAllBytes(audioRecording.getPath());
                    }
                    catch(IOException e)
                    {
                        mLog.error("ThinLine Radio API - audio recording file not found - ignoring upload");
                    }

                    if(audioBytes != null)
                    {
                        RdioScannerBuilder bodyBuilder = new RdioScannerBuilder();
                        bodyBuilder.addPart(FormField.KEY, getBroadcastConfiguration().getApiKey())
                            .addPart(FormField.SYSTEM, getBroadcastConfiguration().getSystemID())
                            .addAudioName(audioName)
                            .addFile(audioBytes)
                            .addPart(FormField.DATE_TIME, timestampSeconds)
                            .addPart(FormField.TALKGROUP_ID, talkgroup)
                            .addPart(FormField.SOURCE, radioId)
                            .addPart(FormField.FREQUENCY, frequency)
                            .addPart(FormField.TALKER_ALIAS, talkerAlias)
                            .addPart(FormField.TALKGROUP_LABEL, talkgroupLabel)
                            .addPart(FormField.TALKGROUP_GROUP, talkgroupGroup)
                            .addPart(FormField.SYSTEM_LABEL, systemLabel)
                            .addPart(FormField.PATCHES, patches);

                        HttpRequest fileRequest = HttpRequest.newBuilder()
                            .uri(URI.create(getBroadcastConfiguration().getHost()))
                            .header(HttpHeaders.CONTENT_TYPE, MULTIPART_FORM_DATA + "; boundary=" + bodyBuilder.getBoundary())
                            .header(HttpHeaders.USER_AGENT, "sdrtrunk")
                            .POST(bodyBuilder.build())
                            .build();

                        final long uploadStartTime = System.currentTimeMillis();
                        final int uploadByteCount = audioBytes.length;

                        mLog.debug("ThinLine upload starting - talkgroup [{}] radio [{}] {} bytes {}s audio -> [{}]",
                            talkgroup, radioId, uploadByteCount, String.format("%.1f", durationSeconds),
                            getBroadcastConfiguration().getHost());

                        mHttpClient.sendAsync(fileRequest, HttpResponse.BodyHandlers.ofString())
                            .whenComplete((fileResponse, throwable1) -> {
                                long elapsed = System.currentTimeMillis() - uploadStartTime;

                                //A non-null throwable means the request never produced a response, so fileResponse
                                //is null here and must not be dereferenced.
                                if(throwable1 != null)
                                {
                                    Throwable cause = (throwable1 instanceof CompletionException &&
                                        throwable1.getCause() != null) ? throwable1.getCause() : throwable1;

                                    setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                                    mLog.error("ThinLine Radio API upload failed - talkgroup [" + talkgroup +
                                        "] no response from [" + getBroadcastConfiguration().getHost() +
                                        "] after " + elapsed + "ms - " + cause.getClass().getSimpleName() +
                                        ": " + cause.getMessage());

                                    incrementErrorAudioCount();
                                    broadcast(new BroadcastEvent(ThinLineRadioBroadcaster.this,
                                        BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                                }
                                else if(fileResponse.statusCode() != 200)
                                {
                                    setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                                    mLog.error("ThinLine Radio API upload rejected - talkgroup [" + talkgroup +
                                        "] HTTP [" + fileResponse.statusCode() + "] after " + elapsed +
                                        "ms - response [" + fileResponse.body() + "]");

                                    incrementErrorAudioCount();
                                    broadcast(new BroadcastEvent(ThinLineRadioBroadcaster.this,
                                        BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                                }
                                else
                                {
                                    String fileResponseString = fileResponse.body();

                                    if(fileResponseString.contains("Call imported successfully."))
                                    {
                                        mLog.debug("ThinLine upload accepted - talkgroup [{}] {} bytes in {} ms",
                                            talkgroup, uploadByteCount, elapsed);
                                        incrementStreamedAudioCount();
                                        broadcast(new BroadcastEvent(ThinLineRadioBroadcaster.this,
                                            BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
                                        audioRecording.removePendingReplay();
                                    }
                                    else if(fileResponseString.contains("duplicate call rejected"))
                                    {
                                        mLog.debug("ThinLine duplicate rejected by server - talkgroup [{}] ({} ms)",
                                            talkgroup, elapsed);
                                        audioRecording.removePendingReplay();
                                    }
                                    else
                                    {
                                        setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                                        mLog.error("ThinLine Radio API unexpected response - talkgroup [" +
                                            talkgroup + "] HTTP [" + fileResponse.statusCode() + "] after " +
                                            elapsed + "ms - response [" + fileResponse.body() + "]");
                                    }
                                }
                            });
                    }
                    else
                    {
                        mLog.error("ThinLine Radio API - upload file not found [" +
                            audioRecording.getPath().toString() + "]");
                        incrementErrorAudioCount();
                        broadcast(new BroadcastEvent(ThinLineRadioBroadcaster.this,
                            BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                        audioRecording.removePendingReplay();
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Unknown Error", e);
                    setBroadcastState(BroadcastState.ERROR);
                    incrementErrorAudioCount();
                    broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_ERROR_COUNT_CHANGE));
                    audioRecording.removePendingReplay();
                }
            }
        }

        AudioRecording audioRecording = mAudioRecordingQueue.peek();

        while(audioRecording != null)
        {
            if(isValid(audioRecording))
            {
                return;
            }
            else
            {
                //Previously silent - recordings were discarded without any indication.
                mLog.debug("ThinLine aged off recording - talkgroup [{}] older than {} ms, discarded without " +
                        "upload - queue depth [{}]", getTo(audioRecording),
                    getBroadcastConfiguration().getMaximumRecordingAge(), mAudioRecordingQueue.size());

                mAudioRecordingQueue.poll();
                audioRecording.removePendingReplay();
                incrementAgedOffAudioCount();
                broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_AGED_OFF_COUNT_CHANGE));
                audioRecording = mAudioRecordingQueue.peek();
            }
        }
    }

    private static Long getFrequency(AudioRecording audioRecording)
    {
        Identifier identifier = audioRecording.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION,
            Form.CHANNEL_FREQUENCY, Role.ANY);

        if(identifier instanceof ConfigurationLongIdentifier)
        {
            Long value = ((ConfigurationLongIdentifier)identifier).getValue();

            if(value != null)
            {
                return value;
            }
        }

        return Long.valueOf(0);
    }

    private static String getFrom(AudioRecording audioRecording)
    {
        for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(Role.FROM))
        {
            if(identifier instanceof RadioIdentifier)
            {
                return ((RadioIdentifier)identifier).getValue().toString();
            }
        }

        return "0";
    }

    private static String getTalkerAlias(AudioRecording audioRecording)
    {
        for(Identifier identifier: audioRecording.getIdentifierCollection().getIdentifiers(Role.FROM))
        {
            if(identifier instanceof TalkerAliasIdentifier)
            {
                TalkerAliasIdentifier talkerID = (TalkerAliasIdentifier)identifier;

                if(talkerID.isValid())
                {
                    return talkerID.getValue();
                }
            }
        }

        return "";
    }

    private String getTo(AudioRecording audioRecording)
    {
        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        if(identifier != null)
        {
            AliasList aliasList = mAliasModel.getAliasList(audioRecording.getIdentifierCollection());

            if(aliasList != null)
            {
                List<Alias> aliases = aliasList.getAliases(identifier);
                Optional<Alias> streamAs = aliases.stream().filter(alias -> alias.getStreamTalkgroupAlias() != null).findFirst();

                if(streamAs.isPresent())
                {
                    return String.valueOf(streamAs.get().getStreamTalkgroupAlias().getValue());
                }
            }

            if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
            {
                return patchGroupIdentifier.getValue().getPatchGroup().getValue().toString();
            }
            else if(identifier instanceof TalkgroupIdentifier talkgroupIdentifier)
            {
                return String.valueOf(RadioReferenceDecoder.convertToRadioReferenceTalkgroup(talkgroupIdentifier.getValue(),
                    talkgroupIdentifier.getProtocol()));
            }
            else if(identifier instanceof RadioIdentifier radioIdentifier)
            {
                return radioIdentifier.getValue().toString();
            }
        }

        return "0";
    }

    private String getTalkgroupLabel(AudioRecording audioRecording)
    {
        AliasList aliasList = mAliasModel.getAliasList(audioRecording.getIdentifierCollection());
        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        StringBuilder sb = new StringBuilder();
        if(identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);
            if(!aliases.isEmpty())
            {
                sb.append(aliases.get(0));
            }
        }

        return sb.toString();
    }

    private String getTalkgroupGroup(AudioRecording audioRecording)
    {
        AliasList aliasList = mAliasModel.getAliasList(audioRecording.getIdentifierCollection());
        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        StringBuilder sb = new StringBuilder();
        if(identifier != null)
        {
            List<Alias> aliases = aliasList.getAliases(identifier);
            if(!aliases.isEmpty())
            {
                sb.append(aliases.get(0).getGroup());
            }
        }

        return sb.toString();
    }

    private String getSystemLabel(AudioRecording audioRecording)
    {
        List<Identifier> systems = audioRecording.getIdentifierCollection().getIdentifiers(Form.SYSTEM);

        StringBuilder sb = new StringBuilder();
        if(!systems.isEmpty())
        {
            sb.append(systems.get(0));
        }

        return sb.toString();
    }

    public static String getPatches(AudioRecording audioRecording)
    {
        Identifier identifier = audioRecording.getIdentifierCollection().getToIdentifier();

        if(identifier instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            PatchGroup patchGroup = patchGroupIdentifier.getValue();

            StringBuilder sb = new StringBuilder();
            sb.append("[");
            sb.append(patchGroup.getPatchGroup().getValue().toString());

            for(TalkgroupIdentifier patched: patchGroup.getPatchedTalkgroupIdentifiers())
            {
                sb.append(",").append(patched.getValue());
            }

            for(RadioIdentifier patched: patchGroup.getPatchedRadioIdentifiers())
            {
                sb.append(",").append(patched.getValue());
            }

            sb.append("]");
            return sb.toString();
        }

        return "[]";
    }

    /**
     * Tests the connection and configuration against the ThinLine Radio API
     */
    public static String testConnection(ThinLineRadioConfiguration configuration)
    {
        HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

        RdioScannerBuilder bodyBuilder = new RdioScannerBuilder();
        bodyBuilder.addPart(FormField.KEY, configuration.getApiKey())
            .addPart(FormField.SYSTEM, configuration.getSystemID())
            .addPart(FormField.TEST, 1);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(configuration.getHost()))
            .header(HttpHeaders.CONTENT_TYPE, MULTIPART_FORM_DATA + "; boundary=" + bodyBuilder.getBoundary())
            .header(HttpHeaders.USER_AGENT, "sdrtrunk")
            .header(HttpHeaders.ACCEPT, "*/*")
            .POST(bodyBuilder.build())
            .build();

        HttpResponse.BodyHandler<String> responseHandler = HttpResponse.BodyHandlers.ofString();

        try
        {
            HttpResponse<String> response = httpClient.send(request, responseHandler);
            String responseBody = response.body();
            return (responseBody != null ? responseBody : "(no response)") + " Status Code:" + response.statusCode();
        }
        catch(Exception e)
        {
            return e.getLocalizedMessage();
        }
    }

    public class AudioRecordingProcessor implements Runnable
    {
        @Override
        public void run()
        {
            processRecordingQueue();
        }
    }
}
