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

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Broadcast configuration for Zello Consumer (Friends & Family) channel streaming.
 *
 * Zello Consumer uses a fixed WebSocket endpoint (wss://zello.io/ws) and requires
 * a JWT authentication token obtained from the Zello developer portal.
 *
 * Configuration fields:
 * - Channel: The Zello channel name to stream to
 * - Username: Zello account username
 * - Password: Zello account password
 * - Auth Token: JWT authentication token (required for Zello Consumer)
 */
public class ZelloConsumerConfiguration extends BroadcastConfiguration
{
    private static final String CONSUMER_WS_URL = "wss://zello.io/ws";

    private StringProperty mChannel = new SimpleStringProperty();
    private StringProperty mUsername = new SimpleStringProperty();
    private StringProperty mAuthToken = new SimpleStringProperty();
    private IntegerProperty mStreamGuardMs = new SimpleIntegerProperty(0);
    private IntegerProperty mPauseTimeMs = new SimpleIntegerProperty(0);
    private IntegerProperty mRelaxationTimeMs = new SimpleIntegerProperty(700);

    /**
     * Default constructor for Jackson XML deserialization
     */
    public ZelloConsumerConfiguration()
    {
        this(BroadcastFormat.MP3);
    }

    /**
     * Public constructor
     * @param format audio format (MP3 — audio will be re-encoded to Opus for Zello)
     */
    public ZelloConsumerConfiguration(BroadcastFormat format)
    {
        super(format);

        mValid.unbind();
        mValid.bind(Bindings.and(
            Bindings.and(
                Bindings.isNotEmpty(mChannel),
                Bindings.isNotEmpty(mAuthToken)
            ),
            Bindings.and(
                Bindings.isNotEmpty(mUsername),
                Bindings.isNotNull(mPassword)
            )
        ));
    }

    // ========================================================================
    // Channel Name
    // ========================================================================

    public StringProperty channelProperty()
    {
        return mChannel;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "channel")
    public String getChannel()
    {
        return mChannel.get();
    }

    public void setChannel(String channel)
    {
        mChannel.set(channel);
    }

    // ========================================================================
    // Username
    // ========================================================================

    public StringProperty usernameProperty()
    {
        return mUsername;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "username")
    public String getUsername()
    {
        return mUsername.get();
    }

    public void setUsername(String username)
    {
        mUsername.set(username);
    }

    // ========================================================================
    // Auth Token (JWT — required for Zello Consumer)
    // ========================================================================

    public StringProperty authTokenProperty()
    {
        return mAuthToken;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "auth_token")
    public String getAuthToken()
    {
        return mAuthToken.get();
    }

    public void setAuthToken(String authToken)
    {
        mAuthToken.set(authToken);
    }

    // ========================================================================
    // Stream Guard Timeout (ms) — minimum gap between stop and next start
    // ========================================================================

    public IntegerProperty streamGuardMsProperty()
    {
        return mStreamGuardMs;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "stream_guard_ms")
    public int getStreamGuardMs()
    {
        return mStreamGuardMs.get();
    }

    public void setStreamGuardMs(int ms)
    {
        mStreamGuardMs.set(ms);
    }

    // ========================================================================
    // Pause Time (ms) — delay between consecutive transmissions
    // ========================================================================

    public IntegerProperty pauseTimeMsProperty()
    {
        return mPauseTimeMs;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "pause_time_ms")
    public int getPauseTimeMs()
    {
        return mPauseTimeMs.get();
    }

    public void setPauseTimeMs(int ms)
    {
        mPauseTimeMs.set(ms);
    }

    // ========================================================================
    // Relaxation Time (ms) — hold-over before ending transmission
    // ========================================================================

    public IntegerProperty relaxationTimeMsProperty()
    {
        return mRelaxationTimeMs;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "relaxation_time_ms")
    public int getRelaxationTimeMs()
    {
        return mRelaxationTimeMs.get();
    }

    public void setRelaxationTimeMs(int ms)
    {
        mRelaxationTimeMs.set(ms);
    }

    // ========================================================================
    // Network Name — not used for Consumer, but needed for broadcaster compat
    // ========================================================================

    public String getNetworkName()
    {
        return null;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Returns the fixed WebSocket URL for Zello Consumer.
     */
    public String getWebSocketUrl()
    {
        return CONSUMER_WS_URL;
    }

    @JacksonXmlProperty(isAttribute = true, localName = "type",
        namespace = "http://www.w3.org/2001/XMLSchema-instance")
    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.ZELLO;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        ZelloConsumerConfiguration copy = new ZelloConsumerConfiguration();
        copy.setChannel(getChannel());
        copy.setUsername(getUsername());
        copy.setPassword(getPassword());
        copy.setAuthToken(getAuthToken());
        copy.setStreamGuardMs(getStreamGuardMs());
        copy.setPauseTimeMs(getPauseTimeMs());
        copy.setRelaxationTimeMs(getRelaxationTimeMs());
        return copy;
    }
}
