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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.IRealTimeAudioBroadcaster;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.util.ThreadPool;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-time audio broadcaster for Zello Work channels via WebSocket.
 *
 * Implements IRealTimeAudioBroadcaster to receive 8kHz mono float audio buffers
 * in real-time. Audio is resampled to 16kHz, Opus-encoded, and streamed via
 * the Zello Channel API WebSocket protocol.
 *
 * Each audio segment maps to one Zello push-to-talk voice message:
 * - startRealTimeStream() -> start_stream command
 * - receiveRealTimeAudio() -> accumulate, resample, Opus encode, send packets
 * - stopRealTimeStream() -> stop_stream command
 */
public class ZelloBroadcaster extends AbstractAudioBroadcaster<ZelloConfiguration>
    implements IRealTimeAudioBroadcaster
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloBroadcaster.class);

    private static final int ZELLO_SAMPLE_RATE = 16000;
    private static final int ZELLO_CHANNELS = 1;
    private static final int ZELLO_FRAME_SIZE_MS = 60;
    private static final int ZELLO_FRAME_SIZE_SAMPLES = ZELLO_SAMPLE_RATE * ZELLO_FRAME_SIZE_MS / 1000; // 960
    private static final int OPUS_BITRATE = 28000;

    // codec_header: {sample_rate_hz(16LE), frames_per_packet(8), frame_size_ms(8)}
    private static final byte[] CODEC_HEADER = {(byte)0x80, (byte)0x3E, 0x01, 0x3C};
    private static final String CODEC_HEADER_B64 = Base64.getEncoder().encodeToString(CODEC_HEADER);

    private static final long RECONNECT_INTERVAL_MS = 15000;
    private static final long KICKED_BACKOFF_MS = 60000;
    private static final int MAX_KICKED_RETRIES = 5;

    private final HttpClient mHttpClient;
    private final Gson mGson = new Gson();
    private final AliasModel mAliasModel;

    private WebSocket mWebSocket;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mChannelOnline = new AtomicBoolean(false);
    private final AtomicBoolean mKicked = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean mStopped = new AtomicBoolean(false);
    private final AtomicInteger mSequence = new AtomicInteger(1);
    private final AtomicInteger mKickedCount = new AtomicInteger(0);
    private ScheduledFuture<?> mReconnectFuture;

    private final AtomicBoolean mStreamActive = new AtomicBoolean(false);
    private final AtomicLong mCurrentStreamId = new AtomicLong(-1);
    private final LinkedTransferQueue<float[]> mAudioQueue = new LinkedTransferQueue<>();
    private ScheduledFuture<?> mEncoderFuture;

    private OpusEncoder mOpusEncoder;
    private short[] mResampleBuffer = new short[ZELLO_FRAME_SIZE_SAMPLES];
    private int mResampleBufferPos = 0;
    private byte[] mOpusOutputBuffer = new byte[1275];
    private final AtomicInteger mStreamedCount = new AtomicInteger(0);
    private short mPreviousSample = 0;

    public ZelloBroadcaster(ZelloConfiguration configuration, InputAudioFormat inputAudioFormat,
                            MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(configuration);
        mAliasModel = aliasModel;
        mHttpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();
    }

    /** Returns the configured channel name for log identification */
    private String ch()
    {
        ZelloConfiguration config = getBroadcastConfiguration();
        return config != null && config.getChannel() != null ? "[" + config.getChannel() + "] " : "";
    }

    @Override
    public void start()
    {
        mStopped.set(false);
        setBroadcastState(BroadcastState.CONNECTING);
        try
        {
            initOpusEncoder();
            connectWebSocket();
        }
        catch(Exception e)
        {
            mLog.error("{}Error starting Zello broadcaster", ch(), e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            scheduleReconnect();
        }
    }

    @Override
    public void stop()
    {
        mStopped.set(true);
        if(mStreamActive.get()) stopRealTimeStream();
        if(mReconnectFuture != null) { mReconnectFuture.cancel(true); mReconnectFuture = null; }
        mKicked.set(false);
        mKickedCount.set(0);
        mReconnecting.set(false);
        disconnectWebSocket();
        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public void dispose() { stop(); }

    @Override
    public int getAudioQueueSize() { return mAudioQueue.size(); }

    /** Standard recording receive — discarded since we use real-time streaming */
    @Override
    public void receive(AudioRecording audioRecording)
    {
        if(audioRecording != null) audioRecording.removePendingReplay();
    }

    // ========================================================================
    // IRealTimeAudioBroadcaster
    // ========================================================================

    @Override
    public boolean isRealTimeReady()
    {
        return mConnected.get() && mChannelOnline.get() && !mStreamActive.get();
    }

    @Override
    public synchronized void startRealTimeStream(IdentifierCollection identifiers)
    {
        if(!mConnected.get() || !mChannelOnline.get())
        {
            mLog.warn("{}Cannot start Zello stream - not connected", ch());
            return;
        }
        if(mStreamActive.get())
        {
            stopRealTimeStream();
        }

        mStreamActive.set(true);
        mCurrentStreamId.set(-1);
        mResampleBufferPos = 0;
        mPreviousSample = 0;
        mAudioQueue.clear();

        sendStartStream();

        if(mEncoderFuture == null || mEncoderFuture.isDone())
        {
            mEncoderFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
                this::processAudioQueue, 10, 10, TimeUnit.MILLISECONDS);
        }

        mLog.info("{}Zello stream started", ch());
    }

    @Override
    public void receiveRealTimeAudio(float[] audioBuffer)
    {
        if(mStreamActive.get()) mAudioQueue.offer(audioBuffer);
    }

    @Override
    public synchronized void stopRealTimeStream()
    {
        if(!mStreamActive.get()) return;

        mStreamActive.set(false);

        // Cancel the encoder future and wait for it to finish to avoid
        // concurrent access to mResampleBuffer and the Opus encoder
        if(mEncoderFuture != null)
        {
            mEncoderFuture.cancel(false);
            try { Thread.sleep(15); } // Allow in-flight execution to complete
            catch(InterruptedException ignored) { Thread.currentThread().interrupt(); }
            mEncoderFuture = null;
        }

        // Now safe to drain remaining audio and flush
        try
        {
            processAudioQueue();
            if(mResampleBufferPos > 0) flushResampleBuffer();
        }
        catch(Exception e)
        {
            mLog.debug("{}Error flushing audio on stream stop: {}", ch(), e.getMessage());
        }

        long streamId = mCurrentStreamId.get();
        if(streamId > 0)
        {
            sendStopStream(streamId);
            mStreamedCount.incrementAndGet();
            mKickedCount.set(0); // Successful stream proves connection is healthy
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
        }

        mCurrentStreamId.set(-1);
        mResampleBufferPos = 0;
        mAudioQueue.clear();
        mLog.info("{}Zello stream stopped", ch());
    }

    // ========================================================================
    // Audio Processing
    // ========================================================================

    private synchronized void processAudioQueue()
    {
        try
        {
            float[] buffer;
            while((buffer = mAudioQueue.poll()) != null)
            {
                processAudioBuffer(buffer);
            }
        }
        catch(Exception e)
        {
            mLog.error("Error processing audio queue", e);
        }
    }

    /** Convert float 8kHz -> short 16kHz (2x upsample with linear interpolation), accumulate, encode when frame full */
    private void processAudioBuffer(float[] audio8k)
    {
        for(int i = 0; i < audio8k.length; i++)
        {
            short currentSample = (short)(audio8k[i] * 32767.0f);

            // 2x upsample with linear interpolation:
            // Insert midpoint between previous and current sample, then current sample
            short midpoint = (short)((mPreviousSample + currentSample) / 2);

            if(mResampleBufferPos < ZELLO_FRAME_SIZE_SAMPLES)
            {
                mResampleBuffer[mResampleBufferPos++] = midpoint;
            }
            if(mResampleBufferPos >= ZELLO_FRAME_SIZE_SAMPLES)
            {
                encodeAndSendFrame();
                mResampleBufferPos = 0;
            }

            if(mResampleBufferPos < ZELLO_FRAME_SIZE_SAMPLES)
            {
                mResampleBuffer[mResampleBufferPos++] = currentSample;
            }
            if(mResampleBufferPos >= ZELLO_FRAME_SIZE_SAMPLES)
            {
                encodeAndSendFrame();
                mResampleBufferPos = 0;
            }

            mPreviousSample = currentSample;
        }
    }

    private void encodeAndSendFrame()
    {
        long streamId = mCurrentStreamId.get();
        if(streamId <= 0 || mOpusEncoder == null) return;

        try
        {
            int encoded = mOpusEncoder.encode(mResampleBuffer, 0, ZELLO_FRAME_SIZE_SAMPLES,
                mOpusOutputBuffer, 0, mOpusOutputBuffer.length);

            if(encoded > 0)
            {
                byte[] opusFrame = new byte[encoded];
                System.arraycopy(mOpusOutputBuffer, 0, opusFrame, 0, encoded);
                sendAudioPacket(streamId, opusFrame);
            }
        }
        catch(Exception e)
        {
            mLog.debug("Opus encoding error: {}", e.getMessage());
        }
    }

    private void flushResampleBuffer()
    {
        if(mResampleBufferPos <= 0 || mResampleBufferPos > ZELLO_FRAME_SIZE_SAMPLES) return;

        for(int i = mResampleBufferPos; i < ZELLO_FRAME_SIZE_SAMPLES; i++)
            mResampleBuffer[i] = 0;
        encodeAndSendFrame();
        mResampleBufferPos = 0;
    }

    private void initOpusEncoder() throws Exception
    {
        mOpusEncoder = new OpusEncoder(ZELLO_SAMPLE_RATE, ZELLO_CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP);
        mOpusEncoder.setBitrate(OPUS_BITRATE);
        mOpusEncoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        mOpusEncoder.setComplexity(8);
        mLog.debug("{}Opus encoder initialized: {}Hz, {}ch, {}kbps, {}ms frames",
            ch(), ZELLO_SAMPLE_RATE, ZELLO_CHANNELS, OPUS_BITRATE / 1000, ZELLO_FRAME_SIZE_MS);
    }

    // ========================================================================
    // WebSocket
    // ========================================================================

    private void connectWebSocket()
    {
        if(!mReconnecting.compareAndSet(false, true))
        {
            return; // Another reconnect is already in progress
        }

        // Don't reconnect if we've been stopped
        if(mStopped.get())
        {
            mReconnecting.set(false);
            return;
        }

        // Clean up any existing dead connection
        if(mWebSocket != null)
        {
            try { mWebSocket.abort(); } catch(Exception e) { /* ignore */ }
            mWebSocket = null;
        }
        mConnected.set(false);
        mChannelOnline.set(false);

        String wsUrl = getBroadcastConfiguration().getWebSocketUrl();
        if(wsUrl == null)
        {
            mLog.error("Zello WebSocket URL is null");
            setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
            mReconnecting.set(false);
            return;
        }

        mLog.debug("{}Connecting to Zello Work: {}", ch(), wsUrl);
        try
        {
            mHttpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new ZelloWebSocketListener())
                .thenAccept(ws -> {
                    mWebSocket = ws;
                    mReconnecting.set(false);
                    sendLogon();
                })
                .exceptionally(ex -> {
                    mLog.error("{}WebSocket connection failed: {}", ch(), ex.getMessage());
                    setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                    mReconnecting.set(false);
                    scheduleReconnect();
                    return null;
                });
        }
        catch(Exception e)
        {
            mLog.error("Error creating WebSocket connection", e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            mReconnecting.set(false);
            scheduleReconnect();
        }
    }

    private void disconnectWebSocket()
    {
        mConnected.set(false);
        mChannelOnline.set(false);
        if(mWebSocket != null)
        {
            try { mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down"); }
            catch(Exception e) { /* ignore */ }
            mWebSocket = null;
        }
    }

    private void scheduleReconnect()
    {
        // Don't reconnect if we've been stopped
        if(mStopped.get())
        {
            return;
        }

        if(mKicked.get())
        {
            int kickCount = mKickedCount.get();
            if(kickCount >= MAX_KICKED_RETRIES)
            {
                mLog.error("{}Zello kicked {} times - stopping reconnect attempts. Check channel permissions.", ch(), kickCount);
                setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
                return;
            }
            long backoff = KICKED_BACKOFF_MS * (1L << Math.min(kickCount, 4)); // exponential: 60s, 120s, 240s...
            mLog.warn("{}Zello kicked - backing off {}s ({}/{})", ch(), backoff / 1000, kickCount + 1, MAX_KICKED_RETRIES);
            scheduleReconnectWithDelay(backoff);
        }
        else
        {
            scheduleReconnectWithDelay(RECONNECT_INTERVAL_MS);
        }
    }

    private void scheduleReconnectWithDelay(long delayMs)
    {
        // Cancel any existing reconnect to prevent overlapping timers
        if(mReconnectFuture != null && !mReconnectFuture.isDone())
        {
            return; // A reconnect is already pending
        }

        mReconnectFuture = ThreadPool.SCHEDULED.schedule(() -> {
            if(!mConnected.get() && !mStopped.get())
            {
                mLog.debug("{}Zello reconnecting...", ch());
                connectWebSocket();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    // ========================================================================
    // Zello Protocol
    // ========================================================================

    private void sendLogon()
    {
        if(mWebSocket == null) return;
        ZelloConfiguration config = getBroadcastConfiguration();
        JsonObject logon = new JsonObject();
        logon.addProperty("command", "logon");
        logon.addProperty("seq", mSequence.getAndIncrement());
        com.google.gson.JsonArray channels = new com.google.gson.JsonArray();
        channels.add(config.getChannel());
        logon.add("channels", channels);
        logon.addProperty("username", config.getUsername());
        logon.addProperty("password", config.getPassword());
        logon.addProperty("platform_name", "Gateway");
        mWebSocket.sendText(mGson.toJson(logon), true);
    }

    private void sendStartStream()
    {
        if(mWebSocket == null) return;
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "start_stream");
        cmd.addProperty("seq", mSequence.getAndIncrement());
        cmd.addProperty("type", "audio");
        cmd.addProperty("codec", "opus");
        cmd.addProperty("codec_header", CODEC_HEADER_B64);
        cmd.addProperty("packet_duration", ZELLO_FRAME_SIZE_MS);
        mWebSocket.sendText(mGson.toJson(cmd), true);
    }

    private void sendStopStream(long streamId)
    {
        if(mWebSocket == null) return;
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "stop_stream");
        cmd.addProperty("seq", mSequence.getAndIncrement());
        cmd.addProperty("stream_id", streamId);
        mWebSocket.sendText(mGson.toJson(cmd), true);
    }

    private void sendAudioPacket(long streamId, byte[] opusData)
    {
        if(mWebSocket == null) return;
        ByteBuffer packet = ByteBuffer.allocate(1 + 4 + 4 + opusData.length);
        packet.order(ByteOrder.BIG_ENDIAN);
        packet.put((byte)0x01);
        packet.putInt((int)streamId);
        packet.putInt(0);
        packet.put(opusData);
        packet.flip();
        mWebSocket.sendBinary(packet, true);
    }

    // ========================================================================
    // WebSocket Listener
    // ========================================================================

    private class ZelloWebSocketListener implements WebSocket.Listener
    {
        private StringBuilder mTextBuffer = new StringBuilder();

        @Override public void onOpen(WebSocket ws) { mLog.debug("{}WebSocket opened", ch()); ws.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
        {
            mTextBuffer.append(data);
            if(last) { handleTextMessage(mTextBuffer.toString()); mTextBuffer.setLength(0); }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last)
        {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer msg)
        {
            ws.sendPong(msg);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason)
        {
            mLog.info("{}Zello disconnected (code={} {})", ch(), code, reason);
            mConnected.set(false);
            mChannelOnline.set(false);
            if(mStreamActive.get()) { mStreamActive.set(false); mCurrentStreamId.set(-1); }

            // If kicked error already handled the reconnect, don't double-schedule
            if(mKicked.get())
            {
                return null;
            }

            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error)
        {
            mLog.error("{}Zello WebSocket error: {}", ch(), error.getMessage());
            mConnected.set(false);
            mChannelOnline.set(false);

            // If kicked handler already scheduled reconnect, don't double-schedule
            if(!mKicked.get())
            {
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                scheduleReconnect();
            }
        }

        private void handleTextMessage(String message)
        {
            try
            {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();

                if(json.has("refresh_token") ||
                    (json.has("success") && json.get("success").getAsBoolean() && !json.has("stream_id")))
                {
                    if(!mConnected.get())
                    {
                        mLog.debug("{}Zello logon accepted", ch());
                        mConnected.set(true);
                        // Reset kicked flag so next kick can be detected, but keep the
                        // kickedCount so exponential backoff continues to escalate.
                        // Count only resets when a stream succeeds or on manual stop/restart.
                        mKicked.set(false);
                    }
                    // else: refresh_token while already connected — ignore silently
                }
                else if(json.has("error") && !json.has("command"))
                {
                    mLog.error("{}Zello logon failed: {}", ch(), message);
                    setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
                    return;
                }

                if(json.has("command"))
                {
                    String command = json.get("command").getAsString();
                    if("on_channel_status".equals(command))
                    {
                        String status = json.has("status") ? json.get("status").getAsString() : "";
                        if("online".equals(status))
                        {
                            // getAndSet(true) returns the old value; only log/set state on first transition
                            if(!mChannelOnline.getAndSet(true))
                            {
                                setBroadcastState(BroadcastState.CONNECTED);
                                mLog.info("{}Zello connected", ch());
                            }
                        }
                        else
                        {
                            mChannelOnline.set(false);
                        }
                    }
                    else if("on_error".equals(command))
                    {
                        String error = json.has("error") ? json.get("error").getAsString() : "";
                        mLog.error("{}Zello error: {}", ch(), message);

                        if("kicked".equals(error))
                        {
                            mKicked.set(true);
                            mKickedCount.incrementAndGet();
                            mConnected.set(false);
                            mChannelOnline.set(false);
                            // Close the WebSocket ourselves to prevent onClose from also scheduling
                            if(mWebSocket != null)
                            {
                                try { mWebSocket.abort(); } catch(Exception e) { /* ignore */ }
                                mWebSocket = null;
                            }
                            scheduleReconnect();
                            return; // Don't process further messages on this connection
                        }
                    }
                }

                if(json.has("stream_id") && json.has("success"))
                {
                    if(json.get("success").getAsBoolean())
                    {
                        long streamId = json.get("stream_id").getAsLong();
                        mCurrentStreamId.set(streamId);
                        mLog.debug("{}Zello stream_id={}", ch(), streamId);
                    }
                    else
                    {
                        mLog.error("{}Zello start_stream failed: {}", ch(), message);
                        mCurrentStreamId.set(-2);
                        mStreamActive.set(false);
                    }
                }
            }
            catch(Exception e)
            {
                mLog.error("Error parsing Zello message: {}", message, e);
            }
        }
    }
}
