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
import io.github.dsheirer.util.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared WebSocket connection for multiple Zello Work broadcasters on the same network.
 * Manages one WebSocket, logon with all registered channels, keepalive, and reconnect.
 * Routes incoming messages to the correct broadcaster by channel name or sequence number.
 */
public class ZelloSharedConnection
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloSharedConnection.class);
    private static final long RECONNECT_INTERVAL_MS = 15000;
    private static final long RECONNECT_JITTER_MS = 5000;
    private static final long MAX_RECONNECT_INTERVAL_MS = 120000; // 2-minute cap
    private static final long KEEPALIVE_INTERVAL_MS = 30000;
    private static final int KEEPALIVE_MISSED_ACK_THRESHOLD = 3;
    private static final long CONNECTION_TIMEOUT_MS = 45000;

    private final String mWsUrl;
    private final String mNetworkName;
    private final String mUsername;
    private final String mPassword;
    private final Gson mGson = new Gson();

    private final HttpClient mHttpClient;
    private WebSocket mWebSocket;
    private final AtomicBoolean mConnected = new AtomicBoolean(false);
    private final AtomicBoolean mStopped = new AtomicBoolean(false);
    private final AtomicBoolean mReconnecting = new AtomicBoolean(false);
    private final AtomicInteger mSequence = new AtomicInteger(1);
    private final AtomicInteger mSessionEpoch = new AtomicInteger(0);
    private final AtomicInteger mReconnectAttempts = new AtomicInteger(0);

    private ScheduledFuture<?> mReconnectFuture;
    private ScheduledFuture<?> mKeepaliveFuture;
    private ScheduledFuture<?> mConnectionTimeoutFuture;
    private volatile boolean mKeepaliveAwaitingAck = false;
    private volatile int mKeepaliveMissedAcks = 0;

    // Maps channel name to the broadcaster that owns it
    private final ConcurrentHashMap<String, AbstractZelloBroadcaster<?>> mBroadcasters = new ConcurrentHashMap<>();

    // Maps seq number to channel name for routing responses
    private final ConcurrentHashMap<Integer, String> mPendingSeqToChannel = new ConcurrentHashMap<>();

    // Maps stream_id to channel name for routing stream events
    private final ConcurrentHashMap<Long, String> mStreamIdToChannel = new ConcurrentHashMap<>();

    public ZelloSharedConnection(String wsUrl, String networkName, String username, String password)
    {
        mWsUrl = wsUrl;
        mNetworkName = networkName;
        mUsername = username;
        mPassword = password;
        mHttpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();
    }

    /**
     * Registers a broadcaster for a specific Zello channel.
     * If already connected, reconnects to add the new channel to the subscription.
     */
    public synchronized void register(String channelName, AbstractZelloBroadcaster<?> broadcaster)
    {
        mBroadcasters.put(channelName, broadcaster);
        mLog.info("[Pool] Registered channel '{}' (total: {} channels)", channelName, mBroadcasters.size());

        if(mConnected.get())
        {
            // Re-logon to subscribe to the new channel
            sendLogon();
        }
    }

    /**
     * Unregisters a broadcaster. If no more broadcasters remain, disconnects the shared WebSocket.
     */
    public synchronized void unregister(String channelName)
    {
        mBroadcasters.remove(channelName);
        mLog.info("[Pool] Unregistered channel '{}' (remaining: {} channels)", channelName, mBroadcasters.size());

        if(mBroadcasters.isEmpty())
        {
            stop();
        }
    }

    /**
     * Starts the shared connection if not already running.
     */
    public synchronized void start()
    {
        if(mBroadcasters.isEmpty())
        {
            return;
        }

        mStopped.set(false);
        connectWebSocket();
    }

    /**
     * Stops the shared connection and disconnects all broadcasters.
     */
    public synchronized void stop()
    {
        mStopped.set(true);
        stopKeepalive();

        if(mReconnectFuture != null)
        {
            mReconnectFuture.cancel(true);
            mReconnectFuture = null;
        }

        disconnectWebSocket();
    }

    /**
     * Returns the current session epoch for stream validation.
     */
    public int getSessionEpoch()
    {
        return mSessionEpoch.get();
    }

    /**
     * Returns true if the shared connection is connected and authenticated.
     */
    public boolean isConnected()
    {
        return mConnected.get();
    }

    /**
     * Returns true if there are no broadcasters registered with this connection.
     */
    public boolean isEmpty()
    {
        return mBroadcasters.isEmpty();
    }

    /**
     * Allocates a sequence number and tracks it for response routing.
     * @param channelName the channel this command is for
     * @param command the command name (for debugging)
     * @return the allocated sequence number
     */
    public int nextSequenceFor(String channelName, String command)
    {
        int seq = mSequence.getAndIncrement();
        mPendingSeqToChannel.put(seq, channelName);
        return seq;
    }

    /**
     * Registers a stream_id to channel mapping for routing stream events.
     */
    public void trackStreamId(long streamId, String channelName)
    {
        mStreamIdToChannel.put(streamId, channelName);
    }

    /**
     * Removes a stream_id tracking entry.
     */
    public void untrackStreamId(long streamId)
    {
        mStreamIdToChannel.remove(streamId);
    }

    /**
     * Sends a text message through the shared WebSocket.
     */
    public void sendText(String text)
    {
        WebSocket ws = mWebSocket;
        if(ws != null)
        {
            ws.sendText(text, true);
        }
    }

    /**
     * Sends a binary message through the shared WebSocket.
     */
    public void sendBinary(ByteBuffer data)
    {
        WebSocket ws = mWebSocket;
        if(ws != null)
        {
            ws.sendBinary(data, true);
        }
    }

    // ========================================================================
    // Connection management
    // ========================================================================

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

        if(mWebSocket != null)
        {
            try { mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnecting"); }
            catch(Exception e) { /* ignore */ }
            mWebSocket = null;
        }

        mConnected.set(false);

        mLog.info("[Pool] Connecting to {} ({} channels)", mWsUrl, mBroadcasters.size());

        try
        {
            mHttpClient.newWebSocketBuilder()
                .buildAsync(URI.create(mWsUrl), new SharedWebSocketListener())
                .thenAccept(ws ->
                {
                    mWebSocket = ws;
                    mSessionEpoch.incrementAndGet();
                    mReconnecting.set(false);
                    sendLogon();
                    startConnectionTimeout();
                })
                .exceptionally(ex ->
                {
                    mLog.error("[Pool] WebSocket connection failed: {}", ex.getMessage());
                    mReconnecting.set(false);
                    notifyAllBroadcastersConnectionError("Pool WebSocket handshake failed");
                    scheduleReconnect();
                    return null;
                });
        }
        catch(Exception e)
        {
            mLog.error("[Pool] Error creating WebSocket", e);
            mReconnecting.set(false);
            scheduleReconnect();
        }
    }

    private void disconnectWebSocket()
    {
        mConnected.set(false);
        cancelConnectionTimeout();

        if(mWebSocket != null)
        {
            try { mWebSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Shutting down"); }
            catch(Exception e) { /* ignore */ }
            mWebSocket = null;
        }

        // Notify all broadcasters
        for(AbstractZelloBroadcaster<?> b : mBroadcasters.values())
        {
            b.onPoolDisconnected();
        }
    }

    private void sendLogon()
    {
        if(mWebSocket == null)
        {
            return;
        }

        JsonObject logon = new JsonObject();
        logon.addProperty("command", "logon");
        int seq = mSequence.getAndIncrement();
        logon.addProperty("seq", seq);

        // Subscribe to ALL registered channels
        JsonArray channels = new JsonArray();
        for(String ch : mBroadcasters.keySet())
        {
            channels.add(ch);
        }
        logon.add("channels", channels);

        // Zello Work credentials
        logon.addProperty("username", mUsername);
        logon.addProperty("password", mPassword);
        logon.addProperty("platform_name", "Gateway");

        mWebSocket.sendText(mGson.toJson(logon), true);
        mLog.debug("[Pool] Logon sent with {} channels: {}", channels.size(), channels);
    }

    private void startConnectionTimeout()
    {
        cancelConnectionTimeout();
        final int epoch = mSessionEpoch.get();
        mConnectionTimeoutFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            if(epoch == mSessionEpoch.get() && !mConnected.get() && !mStopped.get())
            {
                mLog.warn("[Pool] Connection timeout after {}s", CONNECTION_TIMEOUT_MS / 1000);
                disconnectWebSocket();
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
        if(mStopped.get()) return;
        int attempt = mReconnectAttempts.getAndIncrement();
        long base = Math.min(RECONNECT_INTERVAL_MS * (1L << Math.min(attempt, 3)),
            MAX_RECONNECT_INTERVAL_MS);
        long jitter = ThreadLocalRandom.current().nextLong(RECONNECT_JITTER_MS);
        long delay = base + jitter;
        mLog.debug("[Pool] Scheduling reconnect in {}ms (attempt {})", delay, attempt + 1);

        if(mReconnectFuture != null && !mReconnectFuture.isDone()) return;
        mReconnectFuture = ThreadPool.SCHEDULED.schedule(() ->
        {
            if(!mConnected.get() && !mStopped.get())
            {
                connectWebSocket();
            }
        }, delay, TimeUnit.MILLISECONDS);
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
            if(mWebSocket == null || !mConnected.get()) return;

            if(mKeepaliveAwaitingAck)
            {
                mKeepaliveMissedAcks++;
            }

            if(mKeepaliveMissedAcks >= KEEPALIVE_MISSED_ACK_THRESHOLD)
            {
                mLog.warn("[Pool] Keepalive timeout — {} missed acks, reconnecting", mKeepaliveMissedAcks);
                stopKeepalive();
                mConnected.set(false);
                if(mWebSocket != null)
                {
                    try { mWebSocket.abort(); } catch(Exception e) { /* ignore */ }
                    mWebSocket = null;
                }
                notifyAllBroadcastersConnectionError("Pool keepalive timeout");
                scheduleReconnect();
                return;
            }

            mKeepaliveAwaitingAck = true;

            // Zello Work keepalive is a JSON command
            JsonObject ka = new JsonObject();
            ka.addProperty("command", "keepalive");
            int seq = mSequence.getAndIncrement();
            ka.addProperty("seq", seq);
            mWebSocket.sendText(mGson.toJson(ka), true);
        }
        catch(Exception e)
        {
            mLog.warn("[Pool] Keepalive tick failed: {}", e.getMessage());
            mKeepaliveMissedAcks++;
        }
    }

    private void notifyAllBroadcastersConnectionError(String error)
    {
        for(AbstractZelloBroadcaster<?> b : mBroadcasters.values())
        {
            b.onPoolConnectionError(error);
        }
    }

    // ========================================================================
    // WebSocket listener — routes messages to correct broadcaster
    // ========================================================================

    private class SharedWebSocketListener implements WebSocket.Listener
    {
        private final StringBuilder mTextBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws)
        {
            mLog.debug("[Pool] WebSocket opened");
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
            // Respond to server WebSocket-level Ping with Pong to prevent
            // the server from closing the connection after its 30-second timeout.
            ws.sendPong(msg);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer msg)
        {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason)
        {
            mLog.info("[Pool] WebSocket closed (code={} {})", code, reason);
            stopKeepalive();
            mConnected.set(false);
            notifyAllBroadcastersConnectionError("Pool WebSocket closed: " + reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error)
        {
            mLog.error("[Pool] WebSocket error: {}", error.getMessage());
            stopKeepalive();
            mConnected.set(false);
            if(mWebSocket != null)
            {
                try { mWebSocket.abort(); } catch(Exception e) { /* ignore */ }
                mWebSocket = null;
            }
            notifyAllBroadcastersConnectionError("Pool WebSocket error");
            scheduleReconnect();
        }

        private void handleTextMessage(String message)
        {
            try
            {
                JsonObject json = JsonParser.parseString(message).getAsJsonObject();

                // Logon success
                if(json.has("success") && json.get("success").getAsBoolean() && !json.has("stream_id"))
                {
                    if(!mConnected.getAndSet(true))
                    {
                        mLog.info("[Pool] Logon accepted");
                        mReconnectAttempts.set(0);
                    }
                }

                // Keepalive ack
                if(json.has("seq") && json.has("success") && json.get("success").getAsBoolean())
                {
                    mKeepaliveAwaitingAck = false;
                    mKeepaliveMissedAcks = 0;
                }

                // Logon error
                if(json.has("error") && !json.has("command") && !json.has("stream_id"))
                {
                    int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                    String channelName = seq > 0 ? mPendingSeqToChannel.remove(seq) : null;
                    String error = json.get("error").getAsString();

                    if(channelName != null)
                    {
                        // Route to specific broadcaster
                        AbstractZelloBroadcaster<?> b = mBroadcasters.get(channelName);
                        if(b != null)
                        {
                            b.handlePooledMessage(json);
                        }
                    }
                    else
                    {
                        // Connection-level error
                        mLog.error("[Pool] Error: {}", error);
                        notifyAllBroadcastersConnectionError(error);
                    }
                    return;
                }

                // Command-based messages
                if(json.has("command"))
                {
                    String command = json.get("command").getAsString();

                    if("on_channel_status".equals(command))
                    {
                        // Route by channel name
                        String channel = json.has("channel") ? json.get("channel").getAsString() : null;
                        if(channel != null)
                        {
                            AbstractZelloBroadcaster<?> b = mBroadcasters.get(channel);
                            if(b != null)
                            {
                                String status = json.has("status") ? json.get("status").getAsString() : "";
                                if("online".equals(status))
                                {
                                    cancelConnectionTimeout();
                                    startKeepalive();
                                    b.onPoolChannelOnline();
                                }
                                else
                                {
                                    b.onPoolChannelOffline(status);
                                }
                            }
                        }
                        return;
                    }

                    if("on_stream_stop".equals(command))
                    {
                        long streamId = json.has("stream_id") ? json.get("stream_id").getAsLong() : -1;
                        String channel = mStreamIdToChannel.get(streamId);
                        if(channel != null)
                        {
                            AbstractZelloBroadcaster<?> b = mBroadcasters.get(channel);
                            if(b != null) b.handlePooledMessage(json);
                        }
                        return;
                    }

                    if("on_error".equals(command))
                    {
                        String error = json.has("error") ? json.get("error").getAsString() : "";
                        if("kicked".equals(error))
                        {
                            mLog.error("[Pool] Kicked from server");
                            disconnectWebSocket();
                            notifyAllBroadcastersConnectionError("kicked");
                            scheduleReconnect();
                        }
                        return;
                    }
                }

                // stream_id response (to start_stream)
                if(json.has("stream_id"))
                {
                    int seq = json.has("seq") ? json.get("seq").getAsInt() : -1;
                    String channel = seq > 0 ? mPendingSeqToChannel.remove(seq) : null;
                    if(channel != null)
                    {
                        if(json.has("success") && json.get("success").getAsBoolean())
                        {
                            long streamId = json.get("stream_id").getAsLong();
                            mStreamIdToChannel.put(streamId, channel);
                        }
                        AbstractZelloBroadcaster<?> b = mBroadcasters.get(channel);
                        if(b != null) b.handlePooledMessage(json);
                    }
                    return;
                }

                // Error with seq (route by seq lookup)
                if(json.has("error") && json.has("seq"))
                {
                    int seq = json.get("seq").getAsInt();
                    String channel = mPendingSeqToChannel.remove(seq);
                    if(channel != null)
                    {
                        AbstractZelloBroadcaster<?> b = mBroadcasters.get(channel);
                        if(b != null) b.handlePooledMessage(json);
                    }
                }
            }
            catch(Exception e)
            {
                mLog.error("[Pool] Error parsing message: {}", message, e);
            }
        }
    }
}
