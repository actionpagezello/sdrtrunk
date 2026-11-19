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
package io.github.dsheirer.dsp.filter.equalizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.github.dsheirer.module.decode.edacs.DecodeConfigEDACS;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.smartnet.DecodeConfigSmartNet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (ap-11): the 20-band equalizer, and conversion of settings saved for the 10-band equalizer.
 */
public class GraphicEqualizerTest
{
    private static final double[] LEGACY = {-6, -3, 0, 2, 4, 6, 4, 2, 0, 3};

    private static ObjectMapper mapper()
    {
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        xmlModule.setDefaultUseWrapper(false);
        return new XmlMapper(xmlModule).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    public void bandsRunFrom170HzTo3500HzAndKeepTheEarlierCenters()
    {
        assertEquals(20, GraphicEqualizer.BAND_COUNT);
        assertEquals(20, GraphicEqualizer.DEFAULT_CENTER_FREQUENCIES.length);
        assertEquals(20, GraphicEqualizer.BAND_LABELS.length);
        assertEquals(20, GraphicEqualizer.BAND_SHORT_LABELS.length);
        double[] earlier = {200, 280, 390, 550, 770, 1100, 1500, 2100, 2900, 3500};

        for(int k = 0; k < earlier.length; k++)
        {
            assertEquals(earlier[k], GraphicEqualizer.DEFAULT_CENTER_FREQUENCIES[2 * k + 1]);
        }

        for(int i = 1; i < GraphicEqualizer.BAND_COUNT; i++)
        {
            assertTrue(GraphicEqualizer.DEFAULT_CENTER_FREQUENCIES[i] > GraphicEqualizer.DEFAULT_CENTER_FREQUENCIES[i - 1]);
        }
    }

    @Test
    public void earlierSettingsKeepTheirBandsAndFillTheBandsBetween()
    {
        double[] gains = GraphicEqualizer.fromLegacyGains(LEGACY);
        assertEquals(20, gains.length);
        assertEquals(0.0, gains[0], "band below 200 Hz starts flat");

        for(int k = 0; k < 10; k++)
        {
            assertEquals(LEGACY[k], gains[2 * k + 1]);
        }

        for(int k = 0; k < 9; k++)
        {
            assertEquals((LEGACY[k] + LEGACY[k + 1]) / 2.0, gains[2 * k + 2]);
        }

        assertArrayEquals(LEGACY, GraphicEqualizer.toLegacyGains(gains));
        assertArrayEquals(gains, GraphicEqualizer.normalizeGains(LEGACY));
    }

    @Test
    public void normalizedGainsAreClampedAndSized()
    {
        double[] gains = GraphicEqualizer.normalizeGains(new double[]{20, -20, 1});
        assertEquals(20, gains.length);
        assertEquals(12.0, gains[0]);
        assertEquals(-12.0, gains[1]);
        assertEquals(1.0, gains[2]);
        assertEquals(0.0, gains[19]);
        assertEquals(20, GraphicEqualizer.normalizeGains(null).length);
    }

    @Test
    public void allBandsBoostedAddUpToAboutWhatTheEarlierEqualizerGave()
    {
        //All 20 bands at +6 dB, measured with a 1 kHz tone between two band centers: neighboring bands add up to
        //between +6 and +10 dB, as with the 10-band equalizer (about +7 dB at 1 kHz)
        GraphicEqualizer equalizer = new GraphicEqualizer(8000);
        equalizer.setEnabled(true);
        double[] gains = new double[20];
        java.util.Arrays.fill(gains, 6.0);
        equalizer.setBandGains(gains);

        double peak = 0;

        for(int n = 0; n < 16000; n++)
        {
            float out = equalizer.process((float)(0.1 * Math.sin(2 * Math.PI * 1000 * n / 8000.0)));

            if(n > 8000)
            {
                peak = Math.max(peak, Math.abs(out));
            }
        }

        double gainDb = 20 * Math.log10(peak / 0.1);
        assertTrue(gainDb > 5 && gainDb < 10, "gain " + gainDb);
    }

    @Test
    public void playlistsWithOnlyTheEarlierElementAreConverted() throws Exception
    {
        DecodeConfigP25Phase1 old = new DecodeConfigP25Phase1();
        old.setGraphicEQBandGains(new double[20]);
        String xml = mapper().writeValueAsString(old);
        //Simulate a playlist saved by ap-10: only the 10-band element
        String wrapperStart = "<graphic_eq_band_gains_20>";
        int start = xml.indexOf(wrapperStart);
        int end = xml.indexOf("</graphic_eq_band_gains_20>") + "</graphic_eq_band_gains_20>".length();
        assertTrue(start > 0, xml);
        xml = xml.substring(0, start) + xml.substring(end);
        String legacyStart = "<graphic_eq_band_gains_200_3500>";
        int legacy = xml.indexOf(legacyStart);
        int legacyEnd = xml.indexOf("</graphic_eq_band_gains_200_3500>");
        StringBuilder gains = new StringBuilder();

        for(double gain : LEGACY)
        {
            gains.append("<gain>").append(gain).append("</gain>");
        }

        xml = xml.substring(0, legacy + legacyStart.length()) + gains + xml.substring(legacyEnd);

        DecodeConfigP25Phase1 loaded = mapper().readValue(xml, DecodeConfigP25Phase1.class);
        assertArrayEquals(GraphicEqualizer.fromLegacyGains(LEGACY), loaded.getGraphicEQBandGains());
    }

    @Test
    public void bothElementsAreWrittenAndTheTwentyBandOneIsUsed() throws Exception
    {
        double[] twenty = new double[20];

        for(int i = 0; i < 20; i++)
        {
            twenty[i] = i % 2 == 0 ? 1.5 : -2.0;
        }

        DecodeConfigEDACS edacs = new DecodeConfigEDACS();
        edacs.setGraphicEQBandGains(twenty);
        String xml = mapper().writeValueAsString(edacs);
        assertTrue(xml.contains("graphic_eq_band_gains_20"), xml);
        assertTrue(xml.contains("graphic_eq_band_gains_200_3500"), xml);
        assertArrayEquals(twenty, mapper().readValue(xml, DecodeConfigEDACS.class).getGraphicEQBandGains());

        DecodeConfigSmartNet smartNet = new DecodeConfigSmartNet();
        smartNet.setGraphicEQBandGains(twenty);
        String smartNetXml = mapper().writeValueAsString(smartNet);
        assertArrayEquals(twenty, mapper().readValue(smartNetXml, DecodeConfigSmartNet.class).getGraphicEQBandGains());

        DecodeConfigP25Phase1 p25 = new DecodeConfigP25Phase1();
        p25.setGraphicEQBandGains(twenty);
        String p25Xml = mapper().writeValueAsString(p25);
        assertArrayEquals(twenty, mapper().readValue(p25Xml, DecodeConfigP25Phase1.class).getGraphicEQBandGains());
    }
}
