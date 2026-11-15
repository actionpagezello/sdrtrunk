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

package io.github.dsheirer.module.demodulate.fm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AP-fork (SmartNet): carrier squelch on trunked voice channels - closes when the carrier drops 20 dB or more below the
 * call's level (MSP Monson: the carrier drops about 50 dB when the transmitter stops), not on ordinary fading.
 */
public class FMCarrierSquelchTest
{
    private static float[][] block(double amplitude)
    {
        float[] i = new float[250];
        float[] q = new float[250];

        for(int x = 0; x < i.length; x++)
        {
            i[x] = (float)(amplitude * Math.cos(x * 0.3));
            q[x] = (float)(amplitude * Math.sin(x * 0.3));
        }

        return new float[][]{i, q};
    }

    @Test
    void testCarrierSquelch()
    {
        FMDemodulatorModule module = new FMDemodulatorModule(12500.0);
        float[][] carrier = block(0.5);
        assertFalse(module.carrierSquelched(carrier[0], carrier[1]), "disabled by default");

        module.setCarrierSquelch(true);

        for(int x = 0; x < 20; x++)
        {
            assertFalse(module.carrierSquelched(carrier[0], carrier[1]));
        }

        float[][] faded = block(0.5 * Math.pow(10, -12 / 20.0)); //12 dB fade
        assertFalse(module.carrierSquelched(faded[0], faded[1]), "a fade keeps the audio");

        float[][] dropped = block(0.5 * Math.pow(10, -50 / 20.0)); //transmitter off
        assertTrue(module.carrierSquelched(dropped[0], dropped[1]), "carrier gone mutes");

        assertFalse(module.carrierSquelched(carrier[0], carrier[1]), "next transmission opens again");
    }
}
