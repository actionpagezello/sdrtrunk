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

package io.github.dsheirer.module.decode.config;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-15.10.10): per-channel JMBE settings.
 */
public class DecodeConfigJMBETest
{
    private static void set(DecodeConfigJMBE config)
    {
        config.setAGC(false);
        config.setComfortNoiseGain(0.3f);
        config.setToneGain(0.6f);
        config.setLegacyToneLevels(true);
    }

    private static void check(DecodeConfigJMBE config, String label)
    {
        assertFalse(config.isAGC(), label + " AGC");
        assertEquals(0.3f, config.getComfortNoiseGain(), 1e-6, label + " comfort noise gain");
        assertEquals(0.6f, config.getToneGain(), 1e-6, label + " tone gain");
        assertTrue(config.isLegacyToneLevels(), label + " legacy tone levels");
    }

    /**
     * Test: new channels get AGC on, tone gain 1.0 and JMBE's comfort noise default 0.05, and gains are clamped.
     */
    @Test
    void testDefaultsAndClamp()
    {
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        assertTrue(config.isAGC());
        assertEquals(1.0f, config.getToneGain(), 1e-6);
        assertEquals(0.05f, config.getComfortNoiseGain(), 1e-6);
        assertFalse(config.isLegacyToneLevels());

        config.setComfortNoiseGain(2.0f);
        assertEquals(1.0f, config.getComfortNoiseGain(), 1e-6);
        config.setToneGain(-1.0f);
        assertEquals(0.0f, config.getToneGain(), 1e-6);
    }

    /**
     * Test: copying a channel's decode configuration keeps its JMBE settings, for each JMBE decoder type, and the
     * settings carry across P25 phases.
     */
    @Test
    void testCopyAndTransfer()
    {
        DecodeConfigJMBE[] configs = {new DecodeConfigDMR(), new DecodeConfigNXDN(), new DecodeConfigP25Phase1(),
                new DecodeConfigP25Phase2()};

        for(DecodeConfigJMBE config: configs)
        {
            set(config);
            check((DecodeConfigJMBE)DecoderFactory.copy(config), config.getClass().getSimpleName() + " copy");
        }

        DecodeConfigP25Phase1 phase1 = new DecodeConfigP25Phase1();
        set(phase1);
        DecodeConfigP25Phase2 phase2 = new DecodeConfigP25Phase2();
        DecoderFactory.transferJMBESettings(phase1, phase2);
        check(phase2, "phase 1 to phase 2");
    }

    /**
     * Test: the playlist attribute names are upstream's, so either program keeps the other's settings.
     */
    @Test
    void testPlaylistAttributes() throws Exception
    {
        XmlMapper mapper = new XmlMapper();
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        set(config);
        String xml = mapper.writeValueAsString(config);

        for(String attribute: new String[]{"jmbeAGC=\"false\"", "jmbeNoiseGain=\"0.3\"", "jmbeToneGain=\"0.6\"",
                "jmbeLegacyToneLevels=\"true\""})
        {
            assertTrue(xml.contains(attribute), "Missing " + attribute + " in " + xml);
        }

        String fleet = xml.replace("jmbeNoiseGain=\"0.3\"", "jmbeNoiseGain=\"1.0\"");
        DecodeConfigP25Phase1 loaded = mapper.readValue(fleet, DecodeConfigP25Phase1.class);
        assertEquals(DecodeConfigJMBE.LEGACY_FORK_COMFORT_NOISE_GAIN, loaded.getComfortNoiseGain(), 1e-6,
                "Stored fleet value loads as written - migration is PlaylistManager's job");
    }
}
