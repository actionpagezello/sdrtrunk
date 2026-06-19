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

import java.util.Base64;
import java.util.Set;

/**
 * Shared Zello Channel API constants and protocol helpers.
 */
public final class ZelloProtocolUtil
{
    public static final int ZELLO_SAMPLE_RATE = 16000;
    public static final int ZELLO_CHANNELS = 1;
    public static final int ZELLO_FRAME_SIZE_MS = 60;
    public static final int ZELLO_FRAME_SIZE_SAMPLES = ZELLO_SAMPLE_RATE * ZELLO_FRAME_SIZE_MS / 1000;
    public static final int OPUS_BITRATE = 28000;

    // codec_header: {sample_rate_hz(16LE), frames_per_packet(8), frame_size_ms(8)}
    public static final byte[] CODEC_HEADER = {(byte)0x80, (byte)0x3E, 0x01, 0x3C};
    public static final String CODEC_HEADER_B64 = Base64.getEncoder().encodeToString(CODEC_HEADER);

    private static final Set<String> TRANSIENT_STREAM_ERRORS = Set.of(
        "channel busy",
        "invalid stream id",
        "failed to stop stream",
        "failed to start stream",
        "failed to start sending message",
        "failed to stop sending message"
    );

    /** Minimum backoff when the server rejects start_stream with channel busy. */
    private static final int CHANNEL_BUSY_BACKOFF_MS = 750;

    private ZelloProtocolUtil()
    {
    }

    /**
     * Maps Zello Channel API error strings to Zello Bridge error codes (3001-3009).
     */
    public static int mapBridgeErrorCode(String error)
    {
        if(error == null)
        {
            return 3008;
        }

        switch(error)
        {
            case "not connected":
                return 3001;
            case "invalid credentials":
            case "not authorized":
                return 3002;
            case "channel is not ready":
                return 3003;
            case "failed to start stream":
                return 3006;
            case "invalid stream id":
            case "failed to stop stream":
                return 3007;
            case "kicked":
                return 3009;
            default:
                return 3008;
        }
    }

    public static boolean isTransientStreamError(String errorMsg)
    {
        return errorMsg != null && TRANSIENT_STREAM_ERRORS.contains(errorMsg);
    }

    /**
     * Extra pause before retrying after a transient stream error. Configured pause/guard times still apply.
     */
    public static int getStreamRetryBackoffMs(String errorMsg)
    {
        if("channel busy".equals(errorMsg))
        {
            return CHANNEL_BUSY_BACKOFF_MS;
        }

        return 0;
    }
}
