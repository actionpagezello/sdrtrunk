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
 * Real-time audio broadcaster for Zello Work channels via WebSocket.
 */
public class ZelloBroadcaster extends AbstractZelloBroadcaster<ZelloConfiguration>
{
    public ZelloBroadcaster(ZelloConfiguration configuration, InputAudioFormat inputAudioFormat,
                            MP3Setting mp3Setting, AliasModel aliasModel)
    {
        super(configuration);
    }

    @Override
    protected String connectTargetLabel()
    {
        return "Zello Work";
    }

    @Override
    protected void onBeforeConnect()
    {
        // no-op for Work
    }

    @Override
    protected void populateLogon(JsonObject logon)
    {
        ZelloConfiguration config = getBroadcastConfiguration();
        logon.addProperty("username", config.getUsername());
        logon.addProperty("password", config.getPassword());
        logon.addProperty("platform_name", "Gateway");
    }

    @Override
    protected void sendKeepalive() throws Exception
    {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("command", "keepalive");
        int seq = nextSequence();
        cmd.addProperty("seq", seq);
        trackPendingCommand(seq, "keepalive");
        getWebSocket().sendText(getGson().toJson(cmd), true);
    }

    @Override
    protected boolean isKeepaliveAckCommand(String command)
    {
        return "keepalive".equals(command);
    }

    @Override
    protected void onInboundPing(WebSocket ws, ByteBuffer msg)
    {
        //AP-fork: an inbound ping is proof the socket is alive, so it resets the liveness deadline -
        //not only our own JSON keepalive ack. Zello Support confirmed (2026-10-03) that their keepalive
        //is server-driven: they ping every 30 s and close the connection if a Pong does not come back
        //within 30 s, and they recommend a read deadline reset on each inbound frame.
        //
        //The pong below is the load-bearing part and was already correct. What was missing is this
        //reset: with it, a channel that is receiving Zello's pings can never accumulate missed acks
        //toward the reconnect threshold. ZelloConsumerBroadcaster already did this; the Work
        //broadcaster did not, because its deadline was driven solely by acks to its own keepalive
        //command.
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
        return json.has("refresh_token");
    }

    @Override
    protected boolean tryHandleAuthError(String errorMsg, int bridgeCode)
    {
        return false;
    }
}
