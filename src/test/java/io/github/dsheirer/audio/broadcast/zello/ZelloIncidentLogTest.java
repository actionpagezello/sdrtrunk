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

/**
 * AP-fork (ap-15.11.3): incident line formatting.
 */
public class ZelloIncidentLogTest
{
    /**
     * Test: token, password and key fields in a server response are masked; the error text is kept.
     */
    @Test
    void testRedact()
    {
        String response = "{\"seq\":12,\"error\":\"not authorized\",\"refresh_token\":\"abc123\",\"password\":\"p w\"," +
                "\"auth_token\": \"xyz\",\"apiKey\":\"k\"}";
        String redacted = ZelloIncidentLog.redact(response);

        for(String secret: new String[]{"abc123", "p w", "xyz", "\"k\""})
        {
            assertFalse(redacted.contains(secret), "Leaked " + secret + " in " + redacted);
        }

        assertTrue(redacted.contains("\"error\":\"not authorized\""));
        assertTrue(redacted.contains("\"seq\":12"));
    }

    /**
     * Test: quoting and durations.
     */
    @Test
    void testFormat()
    {
        assertEquals("-", ZelloIncidentLog.q(null));
        assertEquals("\"Lawrence MA Fire\"", ZelloIncidentLog.q("Lawrence MA Fire"));
        assertEquals("\"say \\\"hi\\\"\"", ZelloIncidentLog.q("say \"hi\""));
        assertEquals("12.3s", ZelloIncidentLog.duration(12_300));
        assertEquals("4m05s", ZelloIncidentLog.duration(245_000));
        assertEquals("2h19m43s", ZelloIncidentLog.duration(8_383_000));
        assertEquals("-", ZelloIncidentLog.duration(-1));
    }
}
