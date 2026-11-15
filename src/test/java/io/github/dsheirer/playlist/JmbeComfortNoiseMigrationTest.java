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

package io.github.dsheirer.playlist;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AP-fork (ap-15.10.10): one-time migration of the fork's stored JMBE comfort noise gain.
 */
public class JmbeComfortNoiseMigrationTest
{
    @Test
    void testMigratesOnce() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node("sdrtrunk-ap-test-" + System.nanoTime());

        try
        {
            Channel p25 = new Channel("p25");
            DecodeConfigP25Phase1 p25Config = new DecodeConfigP25Phase1();
            p25Config.setComfortNoiseGain(1.0f);
            p25.setDecodeConfiguration(p25Config);

            Channel dmr = new Channel("dmr");
            DecodeConfigDMR dmrConfig = new DecodeConfigDMR();
            dmrConfig.setComfortNoiseGain(0.4f);
            dmr.setDecodeConfiguration(dmrConfig);

            Channel nbfm = new Channel("nbfm");
            nbfm.setDecodeConfiguration(new DecodeConfigNBFM());

            List<Channel> channels = List.of(p25, dmr, nbfm);
            assertEquals(1, PlaylistManager.migrateJmbeComfortNoiseGain(channels, preferences, "k"));
            assertEquals(0.05f, p25Config.getComfortNoiseGain(), 1e-6, "Stored 1.0 becomes the default");
            assertEquals(0.4f, dmrConfig.getComfortNoiseGain(), 1e-6, "Other values are kept");

            //A value set to 1.0 after the migration is kept
            p25Config.setComfortNoiseGain(1.0f);
            assertEquals(-1, PlaylistManager.migrateJmbeComfortNoiseGain(channels, preferences, "k"));
            assertEquals(1.0f, p25Config.getComfortNoiseGain(), 1e-6);

            //A different playlist is migrated on its own
            assertEquals(1, PlaylistManager.migrateJmbeComfortNoiseGain(channels, preferences, "other"));
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
