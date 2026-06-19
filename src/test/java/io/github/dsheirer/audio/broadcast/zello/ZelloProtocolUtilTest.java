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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ZelloProtocolUtilTest
{
    @Test
    public void mapBridgeErrorCodeKnownErrors()
    {
        assertEquals(3001, ZelloProtocolUtil.mapBridgeErrorCode("not connected"));
        assertEquals(3002, ZelloProtocolUtil.mapBridgeErrorCode("invalid credentials"));
        assertEquals(3002, ZelloProtocolUtil.mapBridgeErrorCode("not authorized"));
        assertEquals(3003, ZelloProtocolUtil.mapBridgeErrorCode("channel is not ready"));
        assertEquals(3006, ZelloProtocolUtil.mapBridgeErrorCode("failed to start stream"));
        assertEquals(3007, ZelloProtocolUtil.mapBridgeErrorCode("invalid stream id"));
        assertEquals(3007, ZelloProtocolUtil.mapBridgeErrorCode("failed to stop stream"));
        assertEquals(3009, ZelloProtocolUtil.mapBridgeErrorCode("kicked"));
    }

    @Test
    public void mapBridgeErrorCodeUnknown()
    {
        assertEquals(3008, ZelloProtocolUtil.mapBridgeErrorCode("something else"));
        assertEquals(3008, ZelloProtocolUtil.mapBridgeErrorCode(null));
    }

    @Test
    public void isTransientStreamError()
    {
        assertTrue(ZelloProtocolUtil.isTransientStreamError("channel busy"));
        assertTrue(ZelloProtocolUtil.isTransientStreamError("invalid stream id"));
        assertTrue(ZelloProtocolUtil.isTransientStreamError("failed to start sending message"));
        assertFalse(ZelloProtocolUtil.isTransientStreamError("invalid credentials"));
        assertFalse(ZelloProtocolUtil.isTransientStreamError(null));
    }

    @Test
    public void getStreamRetryBackoffMs()
    {
        assertEquals(750, ZelloProtocolUtil.getStreamRetryBackoffMs("channel busy"));
        assertEquals(0, ZelloProtocolUtil.getStreamRetryBackoffMs("failed to start stream"));
        assertEquals(0, ZelloProtocolUtil.getStreamRetryBackoffMs(null));
    }
}
