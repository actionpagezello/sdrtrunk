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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dsheirer.audio.broadcast.AbstractAudioBroadcaster;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.IRealTimeAudioBroadcaster;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared Zello real-time broadcaster base for Work and Consumer implementations.
 */
public abstract class AbstractZelloBroadcaster<T extends BroadcastConfiguration> extends AbstractAudioBroadcaster<T>
    implements IRealTimeAudioBroadcaster
{
    private static final long RECONNECT_INTERVAL_MS = 15000;
    private static final long RECONNECT_JITTER_MS = 5000;
    private static final long KICKED_BACKOFF_MS = 60000;
    private static final int MAX_KICKED_RETRIES = 5;

    private static final long KEEPALIVE_INTERVAL_MS = 30000;
    private static final int KEEPALIVE_MISSED_ACK_THRESHOLD = 3;

    private static final int MAX_GHOST_STREAMS_BEFORE_RECONNECT = 3;
    private static final long CONNECTION_TIMEOUT_MS = 45000;
    private static final long ENCODER_DRAIN_MS = 15;

    protected final Logger mLog = LoggerFactory.getLogger(getClass());

    private final HttpClient mHttpClient;
    private final Gson mGson = new Gson();

    private WebSocket mWebSocket;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mChannelOnline = new AtomicBoolean(false);
    private final AtomicBoolean mKicked = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicBoolean mStopped = new AtomicBoolean(false);
    private final AtomicInteger mSequence = new AtomicInteger(1);
    private final AtomicInteger mKickedCount = new AtomicInteger(0);
    private ScheduledFuture<?> mReconnectFuture;
    private ScheduledFuture<?> mKeepaliveFuture;
    private ScheduledFuture<?> mConnectionTimeoutFuture;
    private volatile boolean mKeepaliveAwaitingAck = false;
    private volatile int mKeepaliveMissedAcks = 0;

    private final AtomicInteger mSessionEpoch = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, String> mPendingCommands = new ConcurrentHashMap<>();

    private final AtomicBoolean mStreamActive = new AtomicBoolean(false);
    private final AtomicLong mCurrentStreamId = new AtomicLong(-1);
    private volatile long mLastStreamStopTime = 0;
    private volatile long mStreamGuardUntilTime = 0;
    private volatile long mPauseUntilTime = 0;
    private volatile int mStreamSessionEpoch = -1;
    private final LinkedTransferQueue<float[]> mAudioQueue = new LinkedTransferQueue<>();
    private ScheduledFuture<?> mEncoderFuture;
    private ScheduledFuture<?> mRelaxationFuture;
    private ScheduledFuture<?> mStreamGuardFuture;
    private ScheduledFuture<?> mPauseFuture;
    private volatile long mLastAudioReceivedTime = 0;
    private volatile int mConsecutiveGhostStreams = 0;
    private volatile boolean mPendingStreamStart = false;

    private OpusEncoder mOpusEncoder;
    private short[] mResampleBuffer = new short[ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES];
    private int mResampleBufferPos = 0;
    private byte[] mOpusOutputBuffer = new byte[1275];
    private short mPreviousSample = 0;

    protected AbstractZelloBroadcaster(T configuration)
    {
        super(configuration);
        mHttpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();
    }

    protected ZelloChannelConfiguration zelloConfig()
    {
        return (ZelloChannelConfiguration)getBroadcastConfiguration();
    }

    protected String ch()
    {
        ZelloChannelConfiguration config = zelloConfig();
        return config != null && config.getChannel() != null ? "[" + config.getChannel() + "] " : "";
    }

    protected abstract String connectTargetLabel();

    protected abstract void onBeforeConnect();

    protected abstract void populateLogon(JsonObject logon);

    protected abstract void sendKeepalive() throws Exception;

    protected abstract boolean isKeepaliveAckCommand(String command);

    protected abstract void onInboundPing(WebSocket ws, ByteBuffer msg);

    protected abstract CompletionStage<?> onInboundPong(WebSocket ws, ByteBuffer msg);

    protected abstract boolean handleLogonExtras(JsonObject json);

    protected abstract boolean tryHandleAuthError(String errorMsg, int bridgeCode);

    protected WebSocket getWebSocket()
    {
        return mWebSocket;
    }

    protected Gson getGson()
    {
        return mGson;
    }

    protected int nextSequence()
    {
        return mSequence.getAndIncrement();
    }

    protected void trackPendingCommand(int seq, String command)
    {
        mPendingCommands.put(seq, command);
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
            mLog.error("{}Error starting {} broadcaster", ch(), connectTargetLabel(), e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            scheduleReconnect();
        }
    }

    @Override
    public void stop()
    {
        mStopped.set(true);

        stopKeepalive();

        if(mRelaxationFuture != null)
        {
            mRelaxationFuture.cancel(false);
            mRelaxationFuture = null;
        }

        if(mStreamGuardFuture != null)
        {
            mStreamGuardFuture.cancel(false);
            mStreamGuardFuture = null;
        }

        if(mPauseFuture != null)
        {
            mPauseFuture.cancel(false);
            mPauseFuture = null;
        }

        if(mReconnectFuture != null)
        {
            mReconnectFuture.cancel(true);
            mReconnectFuture = null;
        }

        if(mStreamActive.get())
        {
            doStopRealTimeStream();
        }

        if(mEncoderFuture != null)
        {
            mEncoderFuture.cancel(false);
            mEncoderFuture = null;
        }

        mPauseUntilTime = 0;
        mStreamGuardUntilTime = 0;
        mKicked.set(false);
        mKickedCount.set(0);
        mReconnecting.set(false);
        disconnectWebSocket();
        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public void dispose()
    {
        stop();
    }

    @Override
    public int getAudioQueueSize()
    {
        return mAudioQueue.size();
    }

    @Override
    public void receive(AudioRecording audioRecording)
    {
        if(audioRecording != null)
        {
            audioRecording.removePendingReplay();
        }
    }

    @Override
    public boolean isRealTimeReady()
    {
        long now = System.currentTimeMillis();
        boolean guardPending = mStreamGuardFuture != null && !mStreamGuardFuture.isDone();
        boolean pausePending = mPauseUntilTime > now;
        return mConnected.get()
            && mChannelOnline.get()
            && !mStreamActive.get()
            && !guardPending
            && mStreamGuardUntilTime <= now
            && !pausePending;
    }

    @Override
    public synchronized void startRealTimeStream(IdentifierCollection identifiers)
    {
        if(!mConnected.get() || !mChannelOnline.get())
        {
            mLog.warn("{}Cannot start Zello stream - not connected", ch());
            return;
        }

        if(mRelaxationFuture != null)
        {
            mRelaxationFuture.cancel(false);
            mRelaxationFuture = null;

            if(mStreamActive.get())
            {
                mLog.debug("{}Relaxation hold-over: continuing existing stream", ch());
                return;
            }
        }

        if(mStreamActive.get())
        {
            mPendingStreamStart = true;
            doStopRealTimeStream();
            return;
        }

        scheduleStreamStart();
    }

    private synchronized void scheduleStreamStart()
    {
        if(mStreamGuardFuture != null)
        {
            mStreamGuardFuture.cancel(false);
            mStreamGuardFuture = null;
        }

        long now = System.currentTimeMillis();
        long guardRemaining = Math.max(0, mStreamGuardUntilTime - now);
        long pauseRemaining = Math.max(0, mPauseUntilTime - now);
        long waitMs = Math.max(guardRemaining, pauseRemaining);

        if(waitMs > 0)
        {
            mStreamGuardFuture = ThreadPool.SCHEDULED.schedule(() ->
            {
                synchronized(AbstractZelloBroadcaster.this)
                {
                    beginStreamInternal();
                    mStreamGuardFuture = null;
                }
            }, waitMs, TimeUnit.MILLISECONDS);
        }
        else
        {
            beginStreamInternal();
        }
    }

    private synchronized void beginStreamInternal()
    {
        if(!mConnected.get() || !mChannelOnline.get())
        {
            return;
        }

        long now = System.currentTimeMillis();
        if(mStreamGuardUntilTime > now || mPauseUntilTime > now)
        {
            scheduleStreamStart();
            return;
        }

        int epoch = mSessionEpoch.get();
        mStreamActive.set(true);
        mStreamSessionEpoch = epoch;
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
        if(mStreamActive.get() && audioBuffer != null)
        {
            mLastAudioReceivedTime = System.currentTimeMillis();
            mAudioQueue.offer(audioBuffer);
        }
    }

    @Override
    public synchronized void stopRealTimeStream()
    {
        if(!mStreamActive.get())
        {
            return;
        }

        int relaxMs = zelloConfig().getRelaxationTimeMs();
        if(relaxMs > 0)
        {
            if(mRelaxationFuture != null)
            {
                mRelaxationFuture.cancel(false);
            }

            mRelaxationFuture = ThreadPool.SCHEDULED.schedule(() ->
            {
                synchronized(this)
                {
                    doStopRealTimeStream();
                }
            }, relaxMs, TimeUnit.MILLISECONDS);

            return;
        }

        doStopRealTimeStream();
    }

    private synchronized void doStopRealTimeStream()
    {
        if(!mStreamActive.get())
        {
            return;
        }

        mStreamActive.set(false);

        if(mRelaxationFuture != null)
        {
            mRelaxationFuture.cancel(false);
            mRelaxationFuture = null;
        }

        if(mEncoderFuture != null)
        {
            mEncoderFuture.cancel(false);
            mEncoderFuture = null;
        }

        if(mPauseFuture != null)
        {
            mPauseFuture.cancel(false);
            mPauseFuture = null;
        }

        if(mStopped.get())
        {
            try
            {
                Thread.sleep(ENCODER_DRAIN_MS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            finishStopRealTimeStream();
        }
        else
        {
            ThreadPool.SCHEDULED.schedule(this::finishStopRealTimeStream, ENCODER_DRAIN_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void finishStopRealTimeStream()
    {
        try
        {
            processAudioQueue();
            if(mResampleBufferPos > 0)
            {
                flushResampleBuffer();
            }
        }
        catch(Exception e)
        {
            mLog.debug("{}Error flushing audio on stream stop: {}", ch(), e.getMessage());
        }

        long streamId = mCurrentStreamId.get();
        if(streamId > 0)
        {
            sendStopStream(streamId);
            incrementStreamedAudioCount();
            mKickedCount.set(0);
            mConsecutiveGhostStreams = 0;
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
        }
        else if(streamId == -1 && mConnected.get())
        {
            mConsecutiveGhostStreams++;
            mLog.warn("{}Zello ghost stream detected — server did not return stream_id ({}/{})",
                ch(), mConsecutiveGhostStreams, MAX_GHOST_STREAMS_BEFORE_RECONNECT);

            if(mConsecutiveGhostStreams >= MAX_GHOST_STREAMS_BEFORE_RECONNECT)
            {
                mLog.error("{}Zello session appears dead — {} consecutive ghost streams. Forcing reconnect.",
                    ch(), mConsecutiveGhostStreams);
                mConsecutiveGhostStreams = 0;
                mCurrentStreamId.set(-1);
                mResampleBufferPos = 0;
                mAudioQueue.clear();
                mLastStreamStopTime = System.currentTimeMillis();
                mPauseUntilTime = 0;
                mStreamGuardUntilTime = 0;
                mPendingStreamStart = false;
                mLog.info("{}Zello stream stopped", ch());
                disconnectWebSocket();
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                scheduleReconnect();
                return;
            }
        }

        mCurrentStreamId.set(-1);
        mResampleBufferPos = 0;
        mAudioQueue.clear();

        scheduleStreamCooldown(0);

        mLog.info("{}Zello stream stopped", ch());
    }

    /**
     * Applies configured pause/guard after a stream ends or a transient start_stream failure.
     */
    private synchronized void scheduleStreamCooldown(int additionalPauseMs)
    {
        if(mPauseFuture != null)
        {
            mPauseFuture.cancel(false);
            mPauseFuture = null;
        }

        int pauseMs = Math.max(zelloConfig().getPauseTimeMs(), additionalPauseMs);
        int guardMs = Math.max(0, zelloConfig().getStreamGuardMs());
        long now = System.currentTimeMillis();
        if(pauseMs > 0)
        {
            mPauseUntilTime = now + pauseMs;
            mPauseFuture = ThreadPool.SCHEDULED.schedule(() ->
            {
                long stopTime = System.currentTimeMillis();
                mLastStreamStopTime = stopTime;
                mPauseUntilTime = 0;
                mStreamGuardUntilTime = guardMs > 0 ? stopTime + guardMs : 0;
                maybeSchedulePendingStreamStart();
            }, pauseMs, TimeUnit.MILLISECONDS);
        }
        else
        {
            mPauseUntilTime = 0;
            mLastStreamStopTime = now;
            mStreamGuardUntilTime = guardMs > 0 ? now + guardMs : 0;
            maybeSchedulePendingStreamStart();
        }
    }

    /**
     * Handles a rejected start_stream without treating it as a ghost stream (no stream_id assigned).
     */
    private synchronized void handleStartStreamFailure(String error, int seq, String originCmd)
    {
        if(mEncoderFuture != null)
        {
            mEncoderFuture.cancel(false);
            mEncoderFuture = null;
        }

        int bridgeCode = ZelloProtocolUtil.mapBridgeErrorCode(error);
        String command = originCmd != null ? originCmd : "start_stream";

        if(ZelloProtocolUtil.isTransientStreamError(error))
        {
            mLog.warn("{}Zello start_stream failed (transient): error=\"{}\" [{}] seq={} command={}",
                ch(), error, bridgeCode, seq, command);
        }
        else
        {
            mLog.error("{}Zello start_stream failed: error=\"{}\" [{}] seq={} command={}",
                ch(), error, bridgeCode, seq, command);
        }

        setLastErrorDetail("[" + bridgeCode + "] " + error);
        mCurrentStreamId.set(-2);
        mStreamActive.set(false);
        mAudioQueue.clear();
        mResampleBufferPos = 0;

        if(ZelloProtocolUtil.isTransientStreamError(error))
        {
            scheduleStreamCooldown(ZelloProtocolUtil.getStreamRetryBackoffMs(error));
        }
    }

    private void maybeSchedulePendingStreamStart()
    {
        if(mPendingStreamStart)
        {
            mPendingStreamStart = false;
            synchronized(this)
            {
                scheduleStreamStart();
            }
        }
    }

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
        catch(Exception | AssertionError e)
        {
            mLog.debug("{}Error processing audio queue (non-fatal): {}", ch(), e.getMessage());
        }
    }

    private void processAudioBuffer(float[] audio8k)
    {
        for(int i = 0; i < audio8k.length; i++)
        {
            short currentSample = (short)(audio8k[i] * 32767.0f);
            short midpoint = (short)((mPreviousSample + currentSample) / 2);

            if(mResampleBufferPos < ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES)
            {
                mResampleBuffer[mResampleBufferPos++] = midpoint;
            }

            if(mResampleBufferPos >= ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES)
            {
                encodeAndSendFrame();
                mResampleBufferPos = 0;
            }

            if(mResampleBufferPos < ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES)
            {
                mResampleBuffer[mResampleBufferPos++] = currentSample;
            }

            if(mResampleBufferPos >= ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES)
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
        if(streamId <= 0 || mOpusEncoder == null)
        {
            return;
        }

        if(mStreamSessionEpoch != mSessionEpoch.get())
        {
            mLog.debug("{}Dropping audio frame — session epoch changed (stream={}, current={})",
                ch(), mStreamSessionEpoch, mSessionEpoch.get());
            mStreamActive.set(false);
            return;
        }

        try
        {
            int encoded = mOpusEncoder.encode(mResampleBuffer, 0, ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES,
                mOpusOutputBuffer, 0, mOpusOutputBuffer.length);

            if(encoded > 0)
            {
                byte[] opusFrame = new byte[encoded];
                System.arraycopy(mOpusOutputBuffer, 0, opusFrame, 0, encoded);
                sendAudioPacket(streamId, opusFrame);
            }
        }
        catch(Exception | AssertionError e)
        {
            mLog.debug("{}Opus encoding error (non-fatal): {}", ch(), e.getMessage());

            try
            {
                initOpusEncoder();
                mLog.debug("{}Opus encoder re-initialized after error", ch());
            }
            catch(Exception reinitEx)
            {
                mLog.warn("{}Failed to re-initialize Opus encoder: {}", ch(), reinitEx.getMessage());
                mOpusEncoder = null;
            }
        }
    }

    private void flushResampleBuffer()
    {
        try
        {
            if(mResampleBufferPos <= 0 || mResampleBufferPos > ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES)
            {
                mResampleBufferPos = 0;
                return;
            }

            for(int i = mResampleBufferPos; i < ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES; i++)
            {
                mResampleBuffer[i] = 0;
            }

            encodeAndSendFrame();
        }
        catch(Exception | AssertionError e)
        {
            mLog.debug("{}Opus flush error (non-fatal): {}", ch(), e.getMessage());
        }
        finally
        {
            mResampleBufferPos = 0;
        }
    }

    private void initOpusEncoder() throws Exception
    {
        mOpusEncoder = new OpusEncoder(ZelloProtocolUtil.ZELLO_SAMPLE_RATE, ZelloProtocolUtil.ZELLO_CHANNELS,
            OpusApplication.OPUS_APPLICATION_VOIP);
        mOpusEncoder.setBitrate(ZelloProtocolUtil.OPUS_BITRATE);
        mOpusEncoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
        mOpusEncoder.setComplexity(8);
        mLog.debug("{}Opus encoder initialized: {}Hz, {}ch, {}kbps, {}ms frames",
            ch(), ZelloProtocolUtil.ZELLO_SAMPLE_RATE, ZelloProtocolUtil.ZELLO_CHANNELS,
            ZelloProtocolUtil.OPUS_BITRATE / 1000, ZelloProtocolUtil.ZELLO_FRAME_SIZE_MS);
    }

    private void connectWebSocket()
    {
        if(!mReconnecting.compareAndSet(false, true))
        {
            return;
        }

        if(mStopped.get())
        {
            mReconnecting.set(false);
            return;
        }

        onBeforeConnect();

        if(mWebSocket != null)
        {
            try
            {
                mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnecting");
            }
            catch(Exception e)
            {
                // ignore
            }
            mWebSocket = null;
        }

        mConnected.set(false);
        mChannelOnline.set(false);
        mPendingCommands.clear();
        mConsecutiveGhostStreams = 0;

        String wsUrl = zelloConfig().getWebSocketUrl();
        if(wsUrl == null)
        {
            mLog.error("{}Zello WebSocket URL is null", ch());
            setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
            mReconnecting.set(false);
            return;
        }

        mLog.debug("{}Connecting to {}: {}", ch(), connectTargetLabel(), wsUrl);

        try
        {
            mHttpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new ZelloWebSocketListener())
                .thenAccept(ws ->
                {
                    mWebSocket = ws;
                    mSessionEpoch.incrementAndGet();
                    mReconnecting.set(false);
                    setLastErrorDetail(null);
                    sendLogon();
                    startConnectionTimeout();
                })
                .exceptionally(ex ->
                {
                    mLog.error("{}WebSocket connection failed: {}", ch(), ex.getMessage());
                    setLastErrorDetail("WebSocket handshake failed");
                    setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                    mReconnecting.set(false);
                    scheduleReconnect();
                    return null;
                });
        }
        catch(Exception e)
        {
            mLog.error("{}Error creating WebSocket connection", ch(), e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            mReconnecting.set(false);
            scheduleReconnect();
        }
    }

    private void disconnectWebSocket()
    {
        mConnected.set(false);
        mChannelOnline.set(false);
        cancelConnectionTimeout();

        if(mWebSocket != null)
        {
            try
            {
                mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down");
            }
            catch(Exception e)
            {
                // ignore
            }
            mWebSocket = null;
        }
    }

    private void startConnectionTimeout()
    {
        cancelConnectionTimeout();
        final int epoch = mSessionEpoch.get();
        mConnectionTimeoutFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            if(epoch == mSessionEpoch.get() && !mChannelOnline.get() && !mStopped.get())
            {
                mLog.warn("{}Zello connection timeout — no channel status after {}s. Forcing reconnect.",
                    ch(), CONNECTION_TIMEOUT_MS / 1000);
                setLastErrorDetail("Connection timeout (" + CONNECTION_TIMEOUT_MS / 1000 + "s)");
                disconnectWebSocket();
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                scheduleReconnect();
            }
        }, CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void cancelConnectionTimeout()
    {
        if(mConnectionTimeoutFuture != null && !mConnectionTimeoutFuture.isDone())
        {
            mConnectionTimeoutFuture.cancel(false);
        }
        mConnectionTimeoutFuture = null;
    }

    private void scheduleReconnect()
    {
        if(mStopped.get())
        {
            return;
        }

        if(mKicked.get())
        {
            int kickCount = mKickedCount.get();
            if(kickCount >= MAX_KICKED_RETRIES)
            {
                mLog.error("{}Zello kicked {} times - stopping reconnect attempts. Check channel permissions.",
                    ch(), kickCount);
                setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
                return;
            }

            long backoff = KICKED_BACKOFF_MS * (1L << Math.min(kickCount, 4));
            mLog.warn("{}Zello kicked - backing off {}s ({}/{})",
                ch(), backoff / 1000, kickCount + 1, MAX_KICKED_RETRIES);
            scheduleReconnectWithDelay(backoff);
        }
        else
        {
            long jitter = ThreadLocalRandom.current().nextLong(RECONNECT_JITTER_MS);
            long delay = RECONNECT_INTERVAL_MS + jitter;
            mLog.debug("{}Scheduling reconnect in {}ms (base {}ms + jitter {}ms)",
                ch(), delay, RECONNECT_INTERVAL_MS, jitter);
            scheduleReconnectWithDelay(delay);
        }
    }

    private void scheduleReconnectWithDelay(long delayMs)
    {
        if(mReconnectFuture != null && !mReconnectFuture.isDone())
        {
            return;
        }

        mReconnectFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            if(!mConnected.get() && !mStopped.get())
            {
                mLog.debug("{}Zello reconnecting...", ch());
                connectWebSocket();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void startKeepalive()
    {
        stopKeepalive();
        mKeepaliveAwaitingAck = false;
        mKeepaliveMissedAcks = 0;
        mKeepaliveFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
            this::keepaliveTick, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopKeepalive()
    {
        if(mKeepaliveFuture != null)
        {
            mKeepaliveFuture.cancel(false);
            mKeepaliveFuture = null;
        }
    }

    private void keepaliveTick()
    {
        try
        {
            if(mWebSocket == null || !mConnected.get())
            {
                return;
            }

            if(mKeepaliveAwaitingAck)
            {
                mKeepaliveMissedAcks++;
                mLog.debug("{}Keepalive ack missed ({}/{})",
                    ch(), mKeepaliveMissedAcks, KEEPALIVE_MISSED_ACK_THRESHOLD);
            }

            if(mKeepaliveMissedAcks >= KEEPALIVE_MISSED_ACK_THRESHOLD)
            {
                mLog.warn("{}Keepalive timeout — {} consecutive missed acks, reconnecting",
                    ch(), mKeepaliveMissedAcks);
                stopKeepalive();
                mConnected.set(false);
                mChannelOnline.set(false);
                mStreamActive.set(false);
                mCurrentStreamId.set(-1);

                if(mWebSocket != null)
                {
                    try
                    {
                        mWebSocket.abort();
                    }
                    catch(Exception e)
                    {
                        // ignore
                    }
                    mWebSocket = null;
                }

                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                setLastErrorDetail("Keepalive timeout — connection dead");
                scheduleReconnect();
                return;
            }

            mKeepaliveAwaitingAck = true;
            sendKeepalive();
        }
        catch(Exception e)
        {
            mLog.warn("{}Keepalive tick failed (non-fatal): {}", ch(), e.getMessage());
            mKeepaliveMissedAcks++;
        }
    }

    protected void handleKeepaliveAck()
    {
        mKeepaliveAwaitingAck = false;
        mKeepaliveMissedAcks = 0;
    }

    protected void sendLogon()
    {
        if(mWebSocket == null)
        {
            return;
        }

        JsonObject logon = new JsonObject();
        logon.addProperty("command", "logon");
        int seq = nextSequence();
        logon.addProperty("seq", seq);
        trackPendingCommand(seq, "logon");
        JsonArray channels = new JsonArray();
        channels.add(zelloConfig().getChannel());
        logon.add("channels", channels);
        populateLogon(logon);
        mWebSocket.sendText(mGson.toJson(logon), true);
    }

    protected void sendStartStream()
    {
        if(mWebSocket == null)
        {
            return;
        }

        if(mStreamSessionEpoch != mSessionEpoch.get())
        {
            mLog.warn("{}Aborting start_stream — session epoch changed during setup", ch());
            mStreamActive.set(false);
            return;
        }

        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "start_stream");
        int seq = nextSequence();
        cmd.addProperty("seq", seq);
        trackPendingCommand(seq, "start_stream");
        cmd.addProperty("channel", zelloConfig().getChannel());
        cmd.addProperty("type", "audio");
        cmd.addProperty("codec", "opus");
        cmd.addProperty("codec_header", ZelloProtocolUtil.CODEC_HEADER_B64);
        cmd.addProperty("packet_duration", ZelloProtocolUtil.ZELLO_FRAME_SIZE_MS);
        mWebSocket.sendText(mGson.toJson(cmd), true);
    }

    protected void sendStopStream(long streamId)
    {
        if(mWebSocket == null)
        {
            return;
        }

        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "stop_stream");
        int seq = nextSequence();
        cmd.addProperty("seq", seq);
        trackPendingCommand(seq, "stop_stream(id=" + streamId + ")");
        cmd.addProperty("stream_id", streamId);
        cmd.addProperty("channel", zelloConfig().getChannel());
        mWebSocket.sendText(mGson.toJson(cmd), true);
    }

    protected void sendAudioPacket(long streamId, byte[] opusData)
    {
        if(mWebSocket == null)
        {
            return;
        }

        ByteBuffer packet = ByteBuffer.allocate(1 + 4 + 4 + opusData.length);
        packet.order(ByteOrder.BIG_ENDIAN);
        packet.put((byte)0x01);
        packet.putInt((int)streamId);
        packet.putInt(0);
        packet.put(opusData);
        packet.flip();
        mWebSocket.sendBinary(packet, true);
    }

    protected boolean isStopped()
    {
        return mStopped.get();
    }

    /**
     * Test support — sets connection flags without opening a WebSocket.
     */
    protected void setConnectionStateForTesting(boolean connected, boolean channelOnline)
    {
        mConnected.set(connected);
        mChannelOnline.set(channelOnline);
    }

    /**
     * Test support — sets non-blocking pause/guard deadlines.
     */
    protected void setTimingStateForTesting(long pauseUntil, long guardUntil)
    {
        mPauseUntilTime = pauseUntil;
        mStreamGuardUntilTime = guardUntil;
    }

    /**
     * Test support — sets stream-active flag.
     */
    protected void setStreamActiveForTesting(boolean active)
    {
        mStreamActive.set(active);
    }

    /**
     * Test support — reads ghost-stream counter.
     */
    protected int getConsecutiveGhostStreamsForTesting()
    {
        return mConsecutiveGhostStreams;
    }

    /**
     * Test support — bumps session epoch as if a reconnect occurred.
     */
    protected int incrementSessionEpochForTesting()
    {
        return mSessionEpoch.incrementAndGet();
    }

    /**
     * Test support — captures stream epoch at start.
     */
    protected void setStreamSessionEpochForTesting(int epoch)
    {
        mStreamSessionEpoch = epoch;
    }

    protected int getSessionEpochForTesting()
    {
        return mSessionEpoch.get();
    }

    protected int getStreamSessionEpochForTesting()
    {
        return mStreamSessionEpoch;
    }

    protected class ZelloWebSocketListener implements WebSocket.Listener
    {
        private final StringBuilder mTextBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws)
        {
            mLog.debug("{}WebSocket opened", ch());
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
        {
            mTextBuffer.append(data);
            if(last)
            {
                handleTextMessage(mTextBuffer.toString());
                mTextBuffer.setLength(0);
            }
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
            onInboundPing(ws, msg);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer msg)
        {
            return onInboundPong(ws, msg);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason)
        {
            mLog.info("{}Zello disconnected (code={} {})", ch(), code, reason);
            stopKeepalive();
            mConnected.set(false);
            mChannelOnline.set(false);
            mStreamActive.set(false);
            mCurrentStreamId.set(-1);

            if(mKicked.get())
            {
                return null;
            }

            if(getBroadcastState() == BroadcastState.CONFIGURATION_ERROR)
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
            stopKeepalive();
            mConnected.set(false);
            mChannelOnline.set(false);
            mStreamActive.set(false);
            mCurrentStreamId.set(-1);

            if(!mKicked.get() && getBroadcastState() != BroadcastState.CONFIGURATION_ERROR)
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

                if(handleLogonExtras(json))
                {
                    if(!mConnected.get())
                    {
                        mConnected.set(true);
                        mKicked.set(false);
                    }
                }
                else if(json.has("success") && json.get("success").getAsBoolean() && !json.has("stream_id"))
                {
                    if(!mConnected.get())
                    {
                        mLog.debug("{}Zello logon accepted", ch());
                        mConnected.set(true);
                        mKicked.set(false);
                    }
                }
                else if(json.has("error") && !json.has("command"))
                {
                    String errorMsg = json.get("error").getAsString();
                    int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                    String originCmd = seq > 0 ? mPendingCommands.remove(seq) : null;
                    int bridgeCode = ZelloProtocolUtil.mapBridgeErrorCode(errorMsg);

                    if(ZelloProtocolUtil.isTransientStreamError(errorMsg))
                    {
                        if("start_stream".equals(originCmd))
                        {
                            handleStartStreamFailure(errorMsg, seq, originCmd);
                            return;
                        }

                        mLog.debug("{}Zello [{}]: error=\"{}\" seq={} command={}",
                            ch(), bridgeCode, errorMsg, seq, originCmd != null ? originCmd : "unknown");
                        setLastErrorDetail("[" + bridgeCode + "] " + errorMsg +
                            (originCmd != null ? " — " + originCmd : ""));
                        mStreamActive.set(false);
                        mCurrentStreamId.set(-1);
                        mLastStreamStopTime = System.currentTimeMillis();
                        return;
                    }

                    if(tryHandleAuthError(errorMsg, bridgeCode))
                    {
                        return;
                    }

                    mLog.error("{}Zello [{}]: error=\"{}\" seq={} command={}",
                        ch(), bridgeCode, errorMsg, seq, originCmd != null ? originCmd : "unknown");
                    setLastErrorDetail("[" + bridgeCode + "] " + errorMsg);
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
                            if(!mChannelOnline.getAndSet(true))
                            {
                                cancelConnectionTimeout();
                                setBroadcastState(BroadcastState.CONNECTED);
                                startKeepalive();
                                mLog.info("{}Zello connected", ch());
                            }
                        }
                        else
                        {
                            if(mChannelOnline.getAndSet(false))
                            {
                                mLog.warn("{}Zello channel went offline (status={}), reconnecting", ch(), status);
                                stopKeepalive();
                                mConnected.set(false);
                                mStreamActive.set(false);
                                mCurrentStreamId.set(-1);
                                if(mWebSocket != null)
                                {
                                    try
                                    {
                                        mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "channel offline");
                                    }
                                    catch(Exception e)
                                    {
                                        // ignore
                                    }
                                    mWebSocket = null;
                                }
                                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                                setLastErrorDetail("Channel offline (status=" + status + ")");
                                scheduleReconnect();
                            }
                        }
                    }
                    else if("on_stream_stop".equals(command))
                    {
                        long stoppedId = json.has("stream_id") ? json.get("stream_id").getAsLong() : -1;
                        if(stoppedId > 0 && stoppedId == mCurrentStreamId.get())
                        {
                            mLog.info("{}Zello server stopped our stream (id={})", ch(), stoppedId);
                            setLastErrorDetail("[3007] server stopped stream (id=" + stoppedId + ")");
                            mStreamActive.set(false);
                            mCurrentStreamId.set(-1);
                            mLastStreamStopTime = System.currentTimeMillis();
                        }
                        else
                        {
                            mLog.debug("{}Zello on_stream_stop for stream_id={} (not ours: {})",
                                ch(), stoppedId, mCurrentStreamId.get());
                        }
                    }
                    else if("on_error".equals(command))
                    {
                        String error = json.has("error") ? json.get("error").getAsString() : "";
                        mLog.error("{}Zello [{}]: {}", ch(), ZelloProtocolUtil.mapBridgeErrorCode(error), message);

                        if("kicked".equals(error))
                        {
                            setLastErrorDetail("[3009] kicked");
                            mKicked.set(true);
                            mKickedCount.incrementAndGet();
                            mConnected.set(false);
                            mChannelOnline.set(false);
                            if(mWebSocket != null)
                            {
                                try
                                {
                                    mWebSocket.abort();
                                }
                                catch(Exception e)
                                {
                                    // ignore
                                }
                                mWebSocket = null;
                            }
                            scheduleReconnect();
                            return;
                        }
                    }
                }

                if(json.has("seq") && json.has("success") && json.get("success").getAsBoolean())
                {
                    int ackSeq = json.get("seq").getAsInt();
                    String ackCmd = mPendingCommands.remove(ackSeq);
                    if(isKeepaliveAckCommand(ackCmd))
                    {
                        handleKeepaliveAck();
                    }
                }

                if(json.has("stream_id") && json.has("success"))
                {
                    if(json.get("success").getAsBoolean())
                    {
                        long streamId = json.get("stream_id").getAsLong();
                        mCurrentStreamId.set(streamId);
                        mConsecutiveGhostStreams = 0;
                        setLastErrorDetail(null);
                        mLog.debug("{}Zello stream_id={}", ch(), streamId);
                    }
                    else
                    {
                        int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                        String originCmd = seq > 0 ? mPendingCommands.remove(seq) : null;
                        String error = json.has("error") ? json.get("error").getAsString() : "unknown";
                        handleStartStreamFailure(error, seq, originCmd);
                    }
                }
            }
            catch(Exception e)
            {
                mLog.error("{}Error parsing Zello message: {}", ch(), message, e);
            }
        }
    }
}
