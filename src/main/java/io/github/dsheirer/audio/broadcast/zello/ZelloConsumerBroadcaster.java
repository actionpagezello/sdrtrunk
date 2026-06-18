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

import com.google.gson.JsonObject;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;

/**
 * Real-time audio broadcaster for Zello Consumer channels via WebSocket.
 */
public class ZelloConsumerBroadcaster extends AbstractZelloBroadcaster<ZelloConsumerConfiguration>
{
    /** Refresh token from Zello Consumer logon — used for fast reconnection */
    private volatile String mRefreshToken;

    public ZelloConsumerBroadcaster(ZelloConsumerConfiguration configuration, InputAudioFormat inputAudioFormat,
                                    MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(configuration);
    }

    @Override
    protected String connectTargetLabel()
    {
        return "Zello Consumer";
    }

    @Override
    protected void onBeforeConnect()
    {
        mRefreshToken = null;
    }

    @Override
    protected void populateLogon(JsonObject logon)
    {
        ZelloConsumerConfiguration config = getBroadcastConfiguration();
        if(mRefreshToken != null && !mRefreshToken.isEmpty())
        {
            logon.addProperty("refresh_token", mRefreshToken);
        }
        else
        {
            logon.addProperty("username", config.getUsername());
            logon.addProperty("password", config.getPassword());
            String authToken = config.getAuthToken();
            if(authToken != null && !authToken.isEmpty())
            {
                logon.addProperty("auth_token", authToken);
            }
        }
    }

    @Override
    protected void sendKeepalive() throws Exception
    {
        getWebSocket().sendPing(ByteBuffer.allocate(0));
    }

    @Override
    protected boolean isKeepaliveAckCommand(String command)
    {
        return false;
    }

    @Override
    protected void onInboundPing(WebSocket ws, ByteBuffer msg)
    {
        handleKeepaliveAck();
        ws.sendPong(msg);
        ws.request(1);
    }

    @Override
    protected CompletionStage<?> onInboundPong(WebSocket ws, ByteBuffer msg)
    {
        handleKeepaliveAck();
        ws.request(1);
        return null;
    }

    @Override
    protected boolean handleLogonExtras(JsonObject json)
    {
        if(json.has("refresh_token") ||
            (json.has("success") && json.get("success").getAsBoolean() && !json.has("stream_id")))
        {
            if(json.has("refresh_token"))
            {
                mRefreshToken = json.get("refresh_token").getAsString();
            }
            return true;
        }

        return false;
    }

    @Override
    protected boolean tryHandleAuthError(String errorMsg, int bridgeCode)
    {
        if(mRefreshToken != null && ("invalid credentials".equals(errorMsg) || "not authorized".equals(errorMsg)))
        {
            mLog.warn("{}Refresh token rejected — retrying with full credentials", ch());
            mRefreshToken = null;
            setLastErrorDetail("[" + bridgeCode + "] refresh_token expired, retrying");
            sendLogon();
            return true;
        }

        return false;
    }
}
