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

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ZelloSessionEpochTest
{
    private ZelloBroadcaster mBroadcaster;

    @BeforeEach
    public void setup()
    {
        ZelloConfiguration config = new ZelloConfiguration(BroadcastFormat.MP3);
        config.setNetworkName("testnet");
        config.setChannel("test-channel");
        config.setUsername("user");
        config.setPassword("pass");
        mBroadcaster = new ZelloBroadcaster(config, InputAudioFormat.SR_8000, MP3Setting.getDefault(), new AliasModel());
    }

    @Test
    public void reconnectIncrementsSessionEpochLeavingStaleStreamEpoch()
    {
        mBroadcaster.setStreamSessionEpochForTesting(0);
        mBroadcaster.incrementSessionEpochForTesting();
        assertEquals(1, mBroadcaster.getSessionEpochForTesting());
        assertNotEquals(mBroadcaster.getStreamSessionEpochForTesting(), mBroadcaster.getSessionEpochForTesting());
    }
}
