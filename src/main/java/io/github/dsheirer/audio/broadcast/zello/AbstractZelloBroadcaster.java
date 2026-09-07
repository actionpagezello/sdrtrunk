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
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private static final long MAX_RECONNECT_INTERVAL_MS = 120000; // 2-minute cap
    private static final long KICKED_BACKOFF_MS = 60000;
    private static final int MAX_KICKED_RETRIES = 5;
    private static final long WATCHDOG_INTERVAL_MS = 60000; // 1-minute watchdog check

    private static final long KEEPALIVE_INTERVAL_MS = 30000;
    private static final int KEEPALIVE_MISSED_ACK_THRESHOLD = 3;

    private static final int MAX_GHOST_STREAMS_BEFORE_RECONNECT = 3;
    private static final int MAX_CONSECUTIVE_3008_BEFORE_RECONNECT = 3;
    private static final long PENDING_STOP_TIMEOUT_MS = 500;
    private static final long CONNECTION_TIMEOUT_MS = 45000;
    private static final long ENCODER_DRAIN_MS = 15;

    /**
     * Cap on Opus frames buffered while waiting for the server to return a stream_id.
     * 30 frames = ~1.8s at 60ms/frame — large enough that the CTCSS confirmation flush
     * (~250-500ms of audio arriving faster than real time) plus normal server latency
     * never evicts the start of a call.
     */
    private static final int MAX_PENDING_OPUS_FRAMES = 30;

    /**
     * Number of pending frames sent immediately when the stream_id arrives. Log analysis
     * (ap-15.6 soak, 2026-07-30..08-01) showed the server killed streams with
     * "audio data sent too fast" only after unpaced bursts of 13+ frames; bursts of 12 or
     * fewer were accepted tens of thousands of times without a single kill. 8 covers the
     * common CTCSS-confirmation backlog (3-9 frames) instantly with a wide safety margin.
     */
    private static final int FLUSH_BURST_FRAMES = 8;

    /**
     * Interval for draining the remaining pending frames, slightly faster than the 60ms
     * real-time frame rate so the backlog catches up gradually without triggering the
     * server's "audio data sent too fast" [3008] stream kill.
     */
    private static final long FLUSH_FRAME_INTERVAL_MS = 55;

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
    private final AtomicInteger mReconnectAttempts = new AtomicInteger(0);
    private ScheduledFuture<?> mReconnectFuture;
    private ScheduledFuture<?> mKeepaliveFuture;
    private ScheduledFuture<?> mConnectionTimeoutFuture;
    private ScheduledFuture<?> mWatchdogFuture;
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
    private final ConcurrentLinkedQueue<byte[]> mPendingOpusFrames = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean mFlushDrainActive = new AtomicBoolean(false);
    private ScheduledFuture<?> mFlushDrainFuture;
    private final AtomicLong mPendingStopStreamId = new AtomicLong(-1);
    private volatile long mLastKnownStreamId = -1;
    private volatile int mConsecutive3008Errors = 0;
    private ScheduledFuture<?> mPendingStopTimeoutFuture;

    private OpusEncoder mOpusEncoder;
    private short[] mResampleBuffer = new short[ZelloProtocolUtil.ZELLO_FRAME_SIZE_SAMPLES];
    private int mResampleBufferPos = 0;
    private byte[] mOpusOutputBuffer = new byte[1275];
    private short mPreviousSample = 0;

    // ========================================================================
    // Shared WebSocket Pool support
    // ========================================================================
    private volatile boolean mPooledMode = false;
    private volatile ZelloSharedConnection mPooledConnection;

    protected AbstractZelloBroadcaster(T configuration)
    {
        super(configuration);
        mHttpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();
    }

    /**
     * Returns true if this broadcaster is operating in pooled mode (sharing a WebSocket).
     */
    protected boolean isPooled()
    {
        return mPooledMode && mPooledConnection != null;
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

    /**
     * Sets error detail for connection, auth, or channel-offline problems. These change or reflect session health.
     */
    protected void setConnectionErrorDetail(String detail)
    {
        setLastErrorDetail(detail);
    }

    /**
     * Updates error detail for stream-level API responses. Clears the error column while CONNECTED so status and
     * error text do not contradict each other (e.g. channel busy during an otherwise healthy session).
     */
    protected void updateStreamErrorDetail(String detail)
    {
        if(getBroadcastState() == BroadcastState.CONNECTED)
        {
            setLastErrorDetail(null);
        }
        else if(detail != null)
        {
            setLastErrorDetail(detail);
        }
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
        startWatchdog();
        setBroadcastState(BroadcastState.CONNECTING);

        try
        {
            initOpusEncoder();

            if(zelloConfig().isUseSharedPool())
            {
                startPooled();
            }
            else
            {
                connectWebSocket();
            }
        }
        catch(Exception e)
        {
            mLog.error("{}Error starting {} broadcaster", ch(), connectTargetLabel(), e);
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            if(!mPooledMode)
            {
                scheduleReconnect();
            }
        }
    }

    /**
     * Starts this broadcaster in pooled mode — acquires a shared WebSocket from the pool.
     */
    private void startPooled()
    {
        ZelloChannelConfiguration config = zelloConfig();
        String wsUrl = config.getWebSocketUrl();
        String networkName = config.getNetworkName();
        String username = config.getUsername();
        String password = getBroadcastConfiguration().getPassword();
        String channel = config.getChannel();

        if(wsUrl == null || channel == null)
        {
            mLog.error("{}Cannot start pooled — missing WebSocket URL or channel", ch());
            setBroadcastState(BroadcastState.CONFIGURATION_ERROR);
            return;
        }

        mPooledMode = true;
        mPooledConnection = ZelloConnectionPool.getInstance()
            .acquire(wsUrl, networkName, username, password, channel, this);
        mLog.info("{}Started in pooled mode", ch());
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

        stopWatchdog();

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
        mPendingStopStreamId.set(-1);
        cancelPendingStopTimeout();
        mConsecutive3008Errors = 0;
        mKicked.set(false);
        mKickedCount.set(0);
        mReconnecting.set(false);

        if(mPooledMode && mPooledConnection != null)
        {
            ZelloChannelConfiguration config = zelloConfig();
            ZelloConnectionPool.getInstance().release(
                config.getWebSocketUrl(), config.getUsername(), config.getChannel());
            mPooledConnection = null;
            mPooledMode = false;
        }
        else
        {
            disconnectWebSocket();
        }

        setBroadcastState(BroadcastState.DISCONNECTED);
    }

    @Override
    public void dispose()
    {
        stop();

        //Release the HttpClient's selector and worker threads.  BroadcastModel destroys and recreates broadcasters on
        //reconnect, and each broadcaster builds its own client, so without this the threads accumulate across the life
        //of the application.
        try
        {
            mHttpClient.close();
        }
        catch(Throwable t)
        {
            mLog.debug("{}Error closing Zello HTTP client during dispose - ignoring", ch(), t);
        }
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

        boolean connected;
        boolean channelOnline;
        if(mPooledMode)
        {
            connected = mPooledConnection != null && mPooledConnection.isConnected();
            channelOnline = mChannelOnline.get();
        }
        else
        {
            connected = mConnected.get();
            channelOnline = mChannelOnline.get();
        }

        return connected
            && channelOnline
            && !mStreamActive.get()
            && !guardPending
            && mStreamGuardUntilTime <= now
            && !pausePending
            && mPendingStopStreamId.get() <= 0;
    }

    @Override
    public synchronized void startRealTimeStream(IdentifierCollection identifiers)
    {
        boolean connected = mPooledMode
            ? (mPooledConnection != null && mPooledConnection.isConnected())
            : mConnected.get();

        if(!connected || !mChannelOnline.get())
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
        boolean connected = mPooledMode
            ? (mPooledConnection != null && mPooledConnection.isConnected())
            : mConnected.get();

        if(!connected || !mChannelOnline.get())
        {
            return;
        }

        long now = System.currentTimeMillis();
        if(mStreamGuardUntilTime > now || mPauseUntilTime > now)
        {
            scheduleStreamStart();
            return;
        }

        if(mPendingStopStreamId.get() > 0)
        {
            mLog.debug("{}Deferring stream start — pending stop not yet confirmed", ch());
            mPendingStreamStart = true;
            return;
        }

        int epoch = mSessionEpoch.get();
        mStreamActive.set(true);
        mStreamSessionEpoch = epoch;
        mCurrentStreamId.set(-1);
        mResampleBufferPos = 0;
        mPreviousSample = 0;
        mAudioQueue.clear();
        cancelFlushDrain();
        mPendingOpusFrames.clear();

        sendStartStream();

        if(mEncoderFuture == null || mEncoderFuture.isDone())
        {
            mEncoderFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
                this::processAudioQueue, 10, 10, TimeUnit.MILLISECONDS);
        }

        if(getBroadcastState() == BroadcastState.CONNECTED)
        {
            setLastErrorDetail(null);
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
            // Send any undrained pending frames as a final bounded burst so short calls do not
            // lose their tail audio when the paced drain has not caught up. Bounded to
            // FLUSH_BURST_FRAMES — a burst size the server reliably accepts.
            cancelFlushDrain();
            int tailSent = 0;
            byte[] tailFrame;
            while(tailSent < FLUSH_BURST_FRAMES && (tailFrame = mPendingOpusFrames.poll()) != null)
            {
                sendAudioPacket(streamId, tailFrame);
                tailSent++;
            }

            if(!mPendingOpusFrames.isEmpty())
            {
                mLog.debug("{}Discarding {} undrained pending frames at stream stop",
                    ch(), mPendingOpusFrames.size());
            }

            sendStopStream(streamId);
            mPendingStopStreamId.set(streamId);
            schedulePendingStopTimeout();
            incrementStreamedAudioCount();
            mKickedCount.set(0);
            mConsecutiveGhostStreams = 0;
            mConsecutive3008Errors = 0;
            broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE));
        }
        else if(streamId == -1 && (mConnected.get() || (mPooledMode && mPooledConnection != null && mPooledConnection.isConnected())))
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
                mPendingStopStreamId.set(-1);
                cancelPendingStopTimeout();
                mResampleBufferPos = 0;
                mAudioQueue.clear();
                cancelFlushDrain();
                mPendingOpusFrames.clear();
                mLastStreamStopTime = System.currentTimeMillis();
                mPauseUntilTime = 0;
                mStreamGuardUntilTime = 0;
                mPendingStreamStart = false;
                mLog.info("{}Zello stream stopped", ch());

                if(!mPooledMode)
                {
                    disconnectWebSocket();
                    scheduleReconnect();
                }

                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                return;
            }
        }

        mCurrentStreamId.set(-1);
        mResampleBufferPos = 0;
        mAudioQueue.clear();
        cancelFlushDrain();
        mPendingOpusFrames.clear();

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
                if(getBroadcastState() == BroadcastState.CONNECTED)
                {
                    setLastErrorDetail(null);
                }
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
     * Schedules a safety timeout for the pending on_stream_stop acknowledgment. If the server does not
     * confirm the stop within PENDING_STOP_TIMEOUT_MS, the pending stop is cleared to avoid permanently
     * blocking new streams.
     */
    private void schedulePendingStopTimeout()
    {
        if(mPendingStopTimeoutFuture != null)
        {
            mPendingStopTimeoutFuture.cancel(false);
        }

        mPendingStopTimeoutFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            long pending = mPendingStopStreamId.getAndSet(-1);
            if(pending > 0)
            {
                // DEBUG, not WARN: the server never sends on_stream_stop for client-initiated
                // stops, so this timeout fires on essentially every stream stop by design
                // (ap-15.6 soak logs: ~53,000 of these per 3 days at WARN).
                mLog.debug("{}Zello on_stream_stop timeout — clearing pending stop for stream_id={}", ch(), pending);
                maybeSchedulePendingStreamStart();
            }
        }, PENDING_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels any pending stop timeout.
     */
    private void cancelPendingStopTimeout()
    {
        if(mPendingStopTimeoutFuture != null)
        {
            mPendingStopTimeoutFuture.cancel(false);
            mPendingStopTimeoutFuture = null;
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

        updateStreamErrorDetail("[" + bridgeCode + "] " + error);
        mCurrentStreamId.set(-2);
        mStreamActive.set(false);
        mAudioQueue.clear();
        mResampleBufferPos = 0;

        // Change 2 & 3: On "channel busy" (3008), send stop_stream for last known stream and
        // reconnect WebSocket after repeated failures
        if("channel busy".equals(error))
        {
            mConsecutive3008Errors++;

            // Send stop_stream for the last known stream to clear server-side orphan
            long pendingId = mPendingStopStreamId.get();
            long stopTarget = pendingId > 0 ? pendingId : mLastKnownStreamId;
            if(stopTarget > 0)
            {
                mLog.warn("{}Zello 3008 ({}/{}) — sending stop_stream for last known id={} before retry",
                    ch(), mConsecutive3008Errors, MAX_CONSECUTIVE_3008_BEFORE_RECONNECT, stopTarget);
                sendStopStream(stopTarget);
                mPendingStopStreamId.set(stopTarget);
                schedulePendingStopTimeout();
            }
            else
            {
                mLog.warn("{}Zello 3008 ({}/{}) — no last known stream_id to stop",
                    ch(), mConsecutive3008Errors, MAX_CONSECUTIVE_3008_BEFORE_RECONNECT);
            }

            // After repeated 3008s, close and reopen the WebSocket to clear stuck server state
            if(mConsecutive3008Errors >= MAX_CONSECUTIVE_3008_BEFORE_RECONNECT)
            {
                mLog.error("{}Zello {} consecutive 3008 errors — reconnecting WebSocket to clear stuck stream",
                    ch(), mConsecutive3008Errors);
                mConsecutive3008Errors = 0;
                mPendingStopStreamId.set(-1);
                cancelPendingStopTimeout();
                mLastKnownStreamId = -1;

                if(!mPooledMode)
                {
                    disconnectWebSocket();
                    scheduleReconnect();
                }

                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                updateStreamErrorDetail("[3008] channel busy — WebSocket reset after " +
                    MAX_CONSECUTIVE_3008_BEFORE_RECONNECT + " consecutive failures");
                return;
            }
        }
        else
        {
            mConsecutive3008Errors = 0;
        }

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

    /**
     * Soft-clips a float audio sample to prevent hard clipping distortion when converting to short.
     * Uses tanh compression for samples exceeding [-1.0, 1.0] range, which can happen when the
     * graphic equalizer boosts frequency bands above the NonClippingGain ceiling.
     */
    private static short softClipToShort(float sample)
    {
        if(sample > 1.0f || sample < -1.0f)
        {
            sample = (float)Math.tanh(sample);
        }
        return (short)(sample * 32767.0f);
    }

    private void processAudioBuffer(float[] audio8k)
    {
        for(int i = 0; i < audio8k.length; i++)
        {
            short currentSample = softClipToShort(audio8k[i]);
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
        if(mOpusEncoder == null)
        {
            return;
        }

        long streamId = mCurrentStreamId.get();

        if(streamId <= 0 && !mStreamActive.get())
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

                if(streamId > 0 && !mFlushDrainActive.get() && mPendingOpusFrames.isEmpty())
                {
                    sendAudioPacket(streamId, opusFrame);
                }
                else
                {
                    // Buffer frame until stream_id arrives from server, or behind an
                    // in-progress paced drain so frame ordering is preserved
                    mPendingOpusFrames.offer(opusFrame);

                    // Cap the buffer to prevent unbounded growth
                    while(mPendingOpusFrames.size() > MAX_PENDING_OPUS_FRAMES)
                    {
                        mPendingOpusFrames.poll();
                    }

                    // Stream is open but a backlog exists — make sure a paced drain is running
                    if(streamId > 0)
                    {
                        ensureFlushDrainScheduled(streamId);
                    }
                }
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

    /**
     * Sends Opus frames buffered while waiting for the server's stream_id: an immediate burst
     * of FLUSH_BURST_FRAMES for low perceived latency, then the remainder paced at
     * FLUSH_FRAME_INTERVAL_MS per frame. Previously the entire backlog (up to ~900ms of audio)
     * was sent in one unpaced burst, which intermittently triggered the server's
     * "audio data sent too fast" [3008] stream kill.
     *
     * @param streamId the newly opened stream to send to
     */
    private void flushPendingFramesPaced(long streamId)
    {
        int burst = 0;
        byte[] frame;

        while(burst < FLUSH_BURST_FRAMES && (frame = mPendingOpusFrames.poll()) != null)
        {
            sendAudioPacket(streamId, frame);
            burst++;
        }

        if(!mPendingOpusFrames.isEmpty())
        {
            int remaining = mPendingOpusFrames.size();
            ensureFlushDrainScheduled(streamId);
            mLog.debug("{}Zello stream_id={}, sent {} burst frames, draining {} more at {}ms intervals",
                ch(), streamId, burst, remaining, FLUSH_FRAME_INTERVAL_MS);
        }
        else if(burst > 0)
        {
            mLog.debug("{}Zello stream_id={}, flushed {} pending frames", ch(), streamId, burst);
        }
        else
        {
            mLog.debug("{}Zello stream_id={}", ch(), streamId);
        }
    }

    /**
     * Starts the paced pending-frame drain if one is not already running.
     */
    private synchronized void ensureFlushDrainScheduled(long streamId)
    {
        if(mFlushDrainActive.getAndSet(true))
        {
            return;
        }

        scheduleFlushDrainTick(streamId);
    }

    /**
     * Schedules the next paced drain tick. Each tick sends one pending frame and reschedules
     * until the backlog is empty. The drain self-cancels if the stream it was started for is
     * no longer the active stream.
     */
    private synchronized void scheduleFlushDrainTick(long streamId)
    {
        mFlushDrainFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            try
            {
                if(mCurrentStreamId.get() != streamId || !mStreamActive.get())
                {
                    cancelFlushDrain();
                    return;
                }

                byte[] frame = mPendingOpusFrames.poll();

                if(frame != null)
                {
                    sendAudioPacket(streamId, frame);
                }

                if(!mPendingOpusFrames.isEmpty())
                {
                    scheduleFlushDrainTick(streamId);
                }
                else
                {
                    mFlushDrainActive.set(false);
                }
            }
            catch(Exception e)
            {
                mLog.debug("{}Pending frame drain error (non-fatal): {}", ch(), e.getMessage());
                mFlushDrainActive.set(false);
            }
        }, FLUSH_FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels any in-progress paced drain and clears the drain flag. Called when a stream
     * starts or stops so a stale drain can never block direct sends for a new stream.
     */
    private synchronized void cancelFlushDrain()
    {
        if(mFlushDrainFuture != null)
        {
            mFlushDrainFuture.cancel(false);
            mFlushDrainFuture = null;
        }

        mFlushDrainActive.set(false);
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
        mConsecutive3008Errors = 0;
        mPendingStopStreamId.set(-1);
        cancelPendingStopTimeout();
        mLastKnownStreamId = -1;

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
                    setConnectionErrorDetail("WebSocket handshake failed");
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
                setConnectionErrorDetail("Connection timeout (" + CONNECTION_TIMEOUT_MS / 1000 + "s)");
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
            int attempt = mReconnectAttempts.getAndIncrement();
            long base = Math.min(RECONNECT_INTERVAL_MS * (1L << Math.min(attempt, 3)),
                MAX_RECONNECT_INTERVAL_MS);
            long jitter = ThreadLocalRandom.current().nextLong(RECONNECT_JITTER_MS);
            long delay = base + jitter;
            mLog.warn("{}Scheduling reconnect in {}ms (base {}ms + jitter {}ms, attempt {})",
                ch(), delay, base, jitter, attempt + 1);
            scheduleReconnectWithDelay(delay);
        }
    }

    private void scheduleReconnectWithDelay(long delayMs)
    {
        if(mReconnectFuture != null && !mReconnectFuture.isDone())
        {
            mLog.debug("{}Cancelling pending reconnect — replacing with {}ms delay", ch(), delayMs);
            mReconnectFuture.cancel(false);
        }

        mReconnectFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            if(!mConnected.get() && !mStopped.get())
            {
                mLog.debug("{}Zello reconnecting...", ch());
                connectWebSocket();
            }
            else if(mConnected.get())
            {
                mLog.debug("{}Reconnect skipped — already connected", ch());
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

    /**
     * Starts a periodic watchdog that detects channels stuck in TEMPORARY_BROADCAST_ERROR
     * with no pending reconnect future. This catches race conditions where onError and onClose
     * interleave and both miss scheduling a reconnect.
     */
    private void startWatchdog()
    {
        stopWatchdog();
        mWatchdogFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(
            this::watchdogTick, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopWatchdog()
    {
        if(mWatchdogFuture != null)
        {
            mWatchdogFuture.cancel(false);
            mWatchdogFuture = null;
        }
    }

    private void watchdogTick()
    {
        try
        {
            if(mStopped.get() || mKicked.get() || mPooledMode)
            {
                return;
            }

            boolean noReconnectPending = (mReconnectFuture == null || mReconnectFuture.isDone());
            boolean notConnected = !mConnected.get();

            if(notConnected && noReconnectPending)
            {
                mLog.warn("{}Watchdog: channel disconnected with no reconnect pending (state={}) — forcing reconnect",
                    ch(), getBroadcastState());
                scheduleReconnect();
            }
        }
        catch(Exception e)
        {
            mLog.warn("{}Watchdog tick failed: {}", ch(), e.getMessage());
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
                mPendingStopStreamId.set(-1);
                cancelPendingStopTimeout();

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
                setConnectionErrorDetail("Keepalive timeout — connection dead");
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
        if(mPooledMode)
        {
            if(mPooledConnection == null || !mPooledConnection.isConnected())
            {
                return;
            }
        }
        else if(mWebSocket == null)
        {
            return;
        }

        if(!mPooledMode && mStreamSessionEpoch != mSessionEpoch.get())
        {
            mLog.warn("{}Aborting start_stream — session epoch changed during setup", ch());
            mStreamActive.set(false);
            return;
        }

        String channelName = zelloConfig().getChannel();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "start_stream");

        int seq;
        if(mPooledMode)
        {
            seq = mPooledConnection.nextSequenceFor(channelName, "start_stream");
        }
        else
        {
            seq = nextSequence();
            trackPendingCommand(seq, "start_stream");
        }

        cmd.addProperty("seq", seq);
        cmd.addProperty("channel", channelName);
        cmd.addProperty("type", "audio");
        cmd.addProperty("codec", "opus");
        cmd.addProperty("codec_header", ZelloProtocolUtil.CODEC_HEADER_B64);
        cmd.addProperty("packet_duration", ZelloProtocolUtil.ZELLO_FRAME_SIZE_MS);

        String text = mGson.toJson(cmd);
        if(mPooledMode)
        {
            mPooledConnection.sendText(text);
        }
        else
        {
            mWebSocket.sendText(text, true);
        }
    }

    protected void sendStopStream(long streamId)
    {
        if(mPooledMode)
        {
            if(mPooledConnection == null)
            {
                return;
            }
        }
        else if(mWebSocket == null)
        {
            return;
        }

        String channelName = zelloConfig().getChannel();
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "stop_stream");

        int seq;
        if(mPooledMode)
        {
            seq = mPooledConnection.nextSequenceFor(channelName, "stop_stream");
            mPooledConnection.untrackStreamId(streamId);
        }
        else
        {
            seq = nextSequence();
            trackPendingCommand(seq, "stop_stream(id=" + streamId + ")");
        }

        cmd.addProperty("seq", seq);
        cmd.addProperty("stream_id", streamId);
        cmd.addProperty("channel", channelName);

        String text = mGson.toJson(cmd);
        if(mPooledMode)
        {
            mPooledConnection.sendText(text);
        }
        else
        {
            mWebSocket.sendText(text, true);
        }
    }

    protected void sendAudioPacket(long streamId, byte[] opusData)
    {
        if(mPooledMode)
        {
            if(mPooledConnection == null)
            {
                return;
            }
        }
        else if(mWebSocket == null)
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

        if(mPooledMode)
        {
            mPooledConnection.sendBinary(packet);
        }
        else
        {
            mWebSocket.sendBinary(packet, true);
        }
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
     * Test support — sets broadcast state without dispatching to the JavaFX thread.
     */
    protected void setBroadcastStateForTesting(BroadcastState state)
    {
        mBroadcastState.setValue(state);
    }

    /**
     * Test support — sets last error detail without dispatching to the JavaFX thread.
     */
    protected void setLastErrorDetailForTesting(String detail)
    {
        mLastErrorDetail.setValue(detail);
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

    // ========================================================================
    // Pool callbacks — called by ZelloSharedConnection when in pooled mode
    // ========================================================================

    /**
     * Called by the pool when our channel comes online.
     */
    public void onPoolChannelOnline()
    {
        if(!mChannelOnline.getAndSet(true))
        {
            setBroadcastState(BroadcastState.CONNECTED);
            mLog.info("{}Zello connected (pooled)", ch());
        }
    }

    /**
     * Called by the pool when our channel goes offline.
     */
    public void onPoolChannelOffline(String status)
    {
        if(mChannelOnline.getAndSet(false))
        {
            mLog.warn("{}Zello channel went offline (pooled, status={})", ch(), status);
            mStreamActive.set(false);
            mCurrentStreamId.set(-1);
            mPendingStopStreamId.set(-1);
            cancelPendingStopTimeout();
            setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            setConnectionErrorDetail("Channel offline (status=" + status + ")");
        }
    }

    /**
     * Called by the pool when the shared WebSocket disconnects.
     */
    public void onPoolDisconnected()
    {
        mChannelOnline.set(false);
        mStreamActive.set(false);
        mCurrentStreamId.set(-1);
        mPendingStopStreamId.set(-1);
        cancelPendingStopTimeout();
        setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
    }

    /**
     * Called by the pool when a connection error occurs.
     */
    public void onPoolConnectionError(String error)
    {
        mLog.error("{}Zello pool connection error: {}", ch(), error);
        setConnectionErrorDetail("Pool: " + error);
        setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
    }

    /**
     * Called by the pool to deliver a routed message to this broadcaster.
     * Handles start_stream responses, on_stream_stop, and errors.
     */
    public void handlePooledMessage(JsonObject json)
    {
        try
        {
            // start_stream success response
            if(json.has("stream_id") && json.has("success"))
            {
                if(json.get("success").getAsBoolean())
                {
                    long streamId = json.get("stream_id").getAsLong();
                    mCurrentStreamId.set(streamId);
                    mLastKnownStreamId = streamId;
                    mConsecutiveGhostStreams = 0;
                    mConsecutive3008Errors = 0;
                    setLastErrorDetail(null);

                    // Flush pending Opus frames — burst then paced drain
                    flushPendingFramesPaced(streamId);
                }
                else
                {
                    String error = json.has("error") ? json.get("error").getAsString() : "unknown";
                    int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                    handleStartStreamFailure(error, seq, "start_stream");
                }
                return;
            }

            // on_stream_stop
            if(json.has("command") && "on_stream_stop".equals(json.get("command").getAsString()))
            {
                long stoppedId = json.has("stream_id") ? json.get("stream_id").getAsLong() : -1;
                long currentId = mCurrentStreamId.get();
                long pendingStopId = mPendingStopStreamId.get();

                if(stoppedId > 0 && stoppedId == currentId)
                {
                    mLog.info("{}Zello server stopped our stream (pooled, id={})", ch(), stoppedId);
                    updateStreamErrorDetail("[3007] server stopped stream (id=" + stoppedId + ")");
                    mStreamActive.set(false);
                    mCurrentStreamId.set(-1);
                    mLastStreamStopTime = System.currentTimeMillis();
                }
                else if(stoppedId > 0 && stoppedId == pendingStopId)
                {
                    mPendingStopStreamId.set(-1);
                    cancelPendingStopTimeout();
                    mLog.info("{}Zello on_stream_stop confirmed (pooled, id={})", ch(), stoppedId);
                    maybeSchedulePendingStreamStart();
                }
                return;
            }

            // Error response
            if(json.has("error"))
            {
                String error = json.get("error").getAsString();
                int bridgeCode = ZelloProtocolUtil.mapBridgeErrorCode(error);
                mLog.warn("{}Zello pooled error [{}]: {}", ch(), bridgeCode, error);
                updateStreamErrorDetail("[" + bridgeCode + "] " + error);

                // "audio data sent too fast" kills the stream server-side asynchronously
                // (no seq) — reset local stream state with backoff so the next call starts
                // cleanly instead of sending audio into a dead stream. Other transient errors
                // are seq-matched responses handled by the start/stop recovery paths above.
                if("audio data sent too fast".equals(error))
                {
                    mStreamActive.set(false);
                    mCurrentStreamId.set(-1);
                    mPendingStopStreamId.set(-1);
                    cancelPendingStopTimeout();
                    cancelFlushDrain();
                    mPendingOpusFrames.clear();
                    mLastStreamStopTime = System.currentTimeMillis();
                    scheduleStreamCooldown(ZelloProtocolUtil.getStreamRetryBackoffMs(error));
                }
            }
        }
        catch(Exception e)
        {
            mLog.error("{}Error handling pooled message", ch(), e);
        }
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
            mPendingStopStreamId.set(-1);
            cancelPendingStopTimeout();

            if(mKicked.get())
            {
                return null;
            }

            if(getBroadcastState() == BroadcastState.CONFIGURATION_ERROR)
            {
                return null;
            }

            try
            {
                setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
            }
            catch(Exception e)
            {
                mLog.warn("{}setBroadcastState threw in onClose — proceeding to reconnect: {}",
                    ch(), e.getMessage());
            }

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
            mPendingStopStreamId.set(-1);
            cancelPendingStopTimeout();

            if(!mKicked.get() && getBroadcastState() != BroadcastState.CONFIGURATION_ERROR)
            {
                try
                {
                    setBroadcastState(BroadcastState.TEMPORARY_BROADCAST_ERROR);
                }
                catch(Exception e)
                {
                    mLog.warn("{}setBroadcastState threw in onError — proceeding to reconnect: {}",
                        ch(), e.getMessage());
                }

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
                        mReconnectAttempts.set(0);
                    }
                }
                else if(json.has("success") && json.get("success").getAsBoolean() && !json.has("stream_id"))
                {
                    if(!mConnected.get())
                    {
                        mLog.debug("{}Zello logon accepted", ch());
                        mConnected.set(true);
                        mKicked.set(false);
                        mReconnectAttempts.set(0);
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
                        updateStreamErrorDetail("[" + bridgeCode + "] " + errorMsg +
                            (originCmd != null ? " — " + originCmd : ""));
                        mStreamActive.set(false);
                        mCurrentStreamId.set(-1);
                        mPendingStopStreamId.set(-1);
                        cancelPendingStopTimeout();
                        cancelFlushDrain();
                        mPendingOpusFrames.clear();
                        mLastStreamStopTime = System.currentTimeMillis();

                        // Apply per-error backoff (e.g. "audio data sent too fast") before the
                        // next stream start so we do not immediately re-trigger the condition
                        scheduleStreamCooldown(ZelloProtocolUtil.getStreamRetryBackoffMs(errorMsg));
                        return;
                    }

                    if(tryHandleAuthError(errorMsg, bridgeCode))
                    {
                        return;
                    }

                    mLog.error("{}Zello [{}]: error=\"{}\" seq={} command={}",
                        ch(), bridgeCode, errorMsg, seq, originCmd != null ? originCmd : "unknown");
                    setConnectionErrorDetail("[" + bridgeCode + "] " + errorMsg);
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
                                mPendingStopStreamId.set(-1);
                                cancelPendingStopTimeout();
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
                                setConnectionErrorDetail("Channel offline (status=" + status + ")");
                                scheduleReconnect();
                            }
                        }
                    }
                    else if("on_stream_stop".equals(command))
                    {
                        long stoppedId = json.has("stream_id") ? json.get("stream_id").getAsLong() : -1;
                        long currentId = mCurrentStreamId.get();
                        long pendingStopId = mPendingStopStreamId.get();

                        if(stoppedId > 0 && stoppedId == currentId)
                        {
                            // Server-initiated stop of our active stream
                            mLog.info("{}Zello server stopped our stream (id={})", ch(), stoppedId);
                            updateStreamErrorDetail("[3007] server stopped stream (id=" + stoppedId + ")");
                            mStreamActive.set(false);
                            mCurrentStreamId.set(-1);
                            mLastStreamStopTime = System.currentTimeMillis();
                        }
                        else if(stoppedId > 0 && stoppedId == pendingStopId)
                        {
                            // Server confirmed our stop_stream — stream fully closed
                            mPendingStopStreamId.set(-1);
                            cancelPendingStopTimeout();
                            mLog.info("{}Zello on_stream_stop confirmed (id={})", ch(), stoppedId);
                            maybeSchedulePendingStreamStart();
                        }
                        else
                        {
                            mLog.debug("{}Zello on_stream_stop for stream_id={} (not ours: current={}, pendingStop={})",
                                ch(), stoppedId, currentId, pendingStopId);
                        }
                    }
                    else if("on_error".equals(command))
                    {
                        String error = json.has("error") ? json.get("error").getAsString() : "";
                        mLog.error("{}Zello [{}]: {}", ch(), ZelloProtocolUtil.mapBridgeErrorCode(error), message);

                        if("kicked".equals(error))
                        {
                            setConnectionErrorDetail("[3009] kicked");
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
                        mLastKnownStreamId = streamId;
                        mConsecutiveGhostStreams = 0;
                        mConsecutive3008Errors = 0;
                        setLastErrorDetail(null);

                        // Flush any Opus frames buffered while waiting for stream_id — burst then paced drain
                        flushPendingFramesPaced(streamId);
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
