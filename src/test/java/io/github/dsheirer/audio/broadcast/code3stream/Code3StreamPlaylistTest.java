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
package io.github.dsheirer.audio.broadcast.code3stream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.broadcast.thinline.ThinlineRadioConfiguration;
import io.github.dsheirer.playlist.PlaylistV2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (1.0.0-ap-1): the fork's stream type that builds up to edacs-25 called ThinLine Radio is now Code 3 Stream.
 * A stream those builds saved must load as a Code 3 Stream with its settings, and upstream's own Thinline Radio type
 * must load beside it as itself.
 */
public class Code3StreamPlaylistTest
{
    private static final String STREAMS = """
        <playlist version="4">
          <stream type="%s" delay="0" enabled="true" host="https://example.invalid/api/call-upload" \
        maximum_recording_age="600000" name="Test stream" port="80" api_key="k-123" system_id="42"/>
        </playlist>
        """;

    private static ObjectMapper mapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        return new XmlMapper(xmlModule).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static BroadcastConfiguration load(String type) throws Exception
    {
        PlaylistV2 playlist = mapper().readValue(String.format(STREAMS, type), PlaylistV2.class);
        assertEquals(1, playlist.getBroadcastConfigurations().size());
        return playlist.getBroadcastConfigurations().get(0);
    }

    @Test
    void streamSavedAsThinLineRadioLoadsAsCode3Stream() throws Exception
    {
        BroadcastConfiguration loaded = load("ThinLineRadioConfiguration");
        Code3StreamConfiguration code3 = assertInstanceOf(Code3StreamConfiguration.class, loaded);
        assertEquals(BroadcastServerType.CODE3_STREAM, code3.getBroadcastServerType());
        assertEquals("Test stream", code3.getName());
        assertEquals("https://example.invalid/api/call-upload", code3.getHost());
        assertEquals("k-123", code3.getApiKey());
        assertEquals(42, code3.getSystemID());
        assertEquals(600000, code3.getMaximumRecordingAge());
        assertTrue(code3.isEnabled());
    }

    @Test
    void code3StreamIsSavedUnderItsNewNameAndLoadsAgain() throws Exception
    {
        PlaylistV2 playlist = new PlaylistV2();
        playlist.getBroadcastConfigurations().add(load("ThinLineRadioConfiguration"));
        String saved = mapper().writeValueAsString(playlist);
        assertTrue(saved.contains("type=\"code3StreamConfiguration\""), saved);
        assertFalse(saved.contains("ThinLineRadioConfiguration"), saved);

        PlaylistV2 reloaded = mapper().readValue(saved, PlaylistV2.class);
        Code3StreamConfiguration code3 = assertInstanceOf(Code3StreamConfiguration.class,
            reloaded.getBroadcastConfigurations().get(0));
        assertEquals("k-123", code3.getApiKey());
        assertEquals(42, code3.getSystemID());
        assertEquals("https://example.invalid/api/call-upload", code3.getHost());
    }

    @Test
    void upstreamThinlineRadioLoadsAsItself() throws Exception
    {
        BroadcastConfiguration loaded = load("ThinlineRadioConfiguration");
        ThinlineRadioConfiguration thinline = assertInstanceOf(ThinlineRadioConfiguration.class, loaded);
        assertEquals(BroadcastServerType.THINLINE_RADIO, thinline.getBroadcastServerType());
        assertEquals("k-123", thinline.getApiKey());
        assertEquals(42, thinline.getSystemID());
    }

    @Test
    void rdioScannerIsUnchanged() throws Exception
    {
        BroadcastConfiguration loaded = load("RdioScannerConfiguration");
        assertEquals(RdioScannerConfiguration.class, loaded.getClass());
        assertEquals(BroadcastServerType.RDIOSCANNER_CALL, loaded.getBroadcastServerType());
    }
}
