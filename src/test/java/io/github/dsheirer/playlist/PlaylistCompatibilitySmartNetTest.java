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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.smartnet.DecodeConfigSmartNet;
import io.github.dsheirer.module.decode.smartnet.SmartNetBandPlan;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (SmartNet): Motorola Type II channels and talkgroup/radio aliases are kept out of the playlist file upstream
 * SDRTrunk reads, and come back from the companion - also after the playlist was saved by another program.
 */
public class PlaylistCompatibilitySmartNetTest
{
    private static ObjectMapper mapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        ObjectMapper mapper = new XmlMapper(xmlModule).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private static Channel channel(String name, io.github.dsheirer.module.decode.config.DecodeConfiguration config,
                                   long frequency)
    {
        Channel channel = new Channel(name);
        channel.setSystem("MSP");
        channel.setSite("Metro Boston");
        channel.setDecodeConfiguration(config);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static PlaylistV2 playlist()
    {
        PlaylistV2 playlist = new PlaylistV2();
        List<Channel> channels = new ArrayList<>();
        channels.add(channel("Fire", new DecodeConfigNBFM(), 154_355_000L));
        DecodeConfigSmartNet smartNet = new DecodeConfigSmartNet();
        smartNet.setBandPlan(SmartNetBandPlan.BAND_800_SPLINTER);
        smartNet.setTrafficChannelPoolSize(6);
        smartNet.setGraphicEQEnabled(true);
        smartNet.setGraphicEQBandGains(new double[]{-6, 0, 0, 0, 0, 0, 0, 0, 0, 3});
        channels.add(channel("MSP Control", smartNet, 854_562_500L));
        playlist.setChannels(channels);

        List<Alias> aliases = new ArrayList<>();
        Alias troop = new Alias("Troop A");
        troop.setAliasListName("MSP");
        troop.addAliasID(new Talkgroup(Protocol.SMARTNET, 33168));
        troop.addAliasID(new Radio(Protocol.SMARTNET, 52612));
        troop.addAliasID(new Talkgroup(Protocol.APCO25, 33168));
        aliases.add(troop);
        playlist.setAliases(aliases);
        return playlist;
    }

    private static boolean hasSmartNetChannel(PlaylistV2 playlist)
    {
        return playlist.getChannels().stream().anyMatch(c -> c.getDecodeConfiguration().getDecoderType() ==
            DecoderType.SMARTNET);
    }

    /**
     * Test: the playlist file has no Type II channel or Type II identifiers; loading with the companion restores them.
     */
    @Test
    void testRoundTrip() throws Exception
    {
        ObjectMapper mapper = mapper();
        String complete = mapper.writeValueAsString(playlist());
        assertTrue(complete.contains("decodeConfigSmartNet"));

        PlaylistCompatibility.SaveFiles files = PlaylistCompatibility.prepareSave(complete, "s1");
        assertFalse(files.playlist().contains("decodeConfigSmartNet"), files.playlist());
        assertFalse(files.playlist().contains("SMARTNET"), files.playlist());
        assertTrue(files.playlist().contains("APCO25"), "other identifiers stay");
        assertTrue(files.companion().contains("decodeConfigSmartNet"));

        //The playlist file alone is a valid playlist with the NBFM channel only
        PlaylistV2 alone = mapper.readValue(files.playlist(), PlaylistV2.class);
        assertEquals(1, alone.getChannels().size());
        assertEquals(1, alone.getAliases().get(0).getAliasIdentifiers().size());

        PlaylistCompatibility.LoadResult result = PlaylistCompatibility.prepareLoad(files.playlist(), files.companion());
        assertEquals(1, result.restoredChannels());
        assertEquals(2, result.restoredAliasIds());
        PlaylistV2 loaded = mapper.readValue(result.xml(), PlaylistV2.class);
        assertEquals(2, loaded.getChannels().size());
        assertTrue(hasSmartNetChannel(loaded));
        DecodeConfigSmartNet config = (DecodeConfigSmartNet)loaded.getChannels().stream()
            .filter(c -> c.getName().equals("MSP Control")).findFirst().orElseThrow().getDecodeConfiguration();
        assertEquals(SmartNetBandPlan.BAND_800_SPLINTER, config.getBandPlan());
        assertEquals(6, config.getTrafficChannelPoolSize());
        assertTrue(config.isGraphicEQEnabled());
        assertEquals(-6.0, config.getGraphicEQBandGains()[0]);
        assertEquals(3.0, config.getGraphicEQBandGains()[9]);

        List<AliasID> ids = loaded.getAliases().get(0).getAliasIdentifiers();
        assertEquals(3, ids.size());
        assertTrue(ids.stream().anyMatch(id -> id instanceof Talkgroup t && t.getProtocol() == Protocol.SMARTNET &&
            t.getValue() == 33168));
        assertTrue(ids.stream().anyMatch(id -> id instanceof Radio r && r.getProtocol() == Protocol.SMARTNET &&
            r.getValue() == 52612));

        //Saving the loaded playlist again does not duplicate anything
        PlaylistCompatibility.SaveFiles again = PlaylistCompatibility.prepareSave(
            mapper.writeValueAsString(loaded), "s2");
        PlaylistCompatibility.LoadResult reloaded = PlaylistCompatibility.prepareLoad(again.playlist(),
            again.companion());
        PlaylistV2 twice = mapper.readValue(reloaded.xml(), PlaylistV2.class);
        assertEquals(2, twice.getChannels().size());
        assertEquals(3, twice.getAliases().get(0).getAliasIdentifiers().size());
    }

    /**
     * Test: after another program saved the playlist (no fork stamp, a channel renamed there), the Type II channel and
     * identifiers still come back, once.
     */
    @Test
    void testForeignWriter() throws Exception
    {
        ObjectMapper mapper = mapper();
        PlaylistCompatibility.SaveFiles files = PlaylistCompatibility.prepareSave(
            mapper.writeValueAsString(playlist()), "s1");

        //Another program reads the playlist file, renames the NBFM channel and saves it without the fork stamp
        PlaylistV2 upstream = mapper.readValue(files.playlist(), PlaylistV2.class);
        upstream.getChannels().get(0).setName("Fire Dispatch");
        String foreign = mapper.writeValueAsString(upstream);
        assertFalse(foreign.contains(PlaylistCompatibility.ATTRIBUTE_SAVE_ID));

        PlaylistCompatibility.LoadResult result = PlaylistCompatibility.prepareLoad(foreign, files.companion());
        assertTrue(result.foreignWriter());
        PlaylistV2 loaded = mapper.readValue(result.xml(), PlaylistV2.class);
        assertEquals(2, loaded.getChannels().size());
        assertTrue(hasSmartNetChannel(loaded));
        assertTrue(loaded.getChannels().stream().anyMatch(c -> c.getName().equals("Fire Dispatch")));
        assertEquals(3, loaded.getAliases().get(0).getAliasIdentifiers().size());
    }
}
