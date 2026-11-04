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

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AP-fork (ap-15.11.3): one structured log line per Zello connection incident, carrying the fields Zello support asked
 * for to match a client-side event against their servers (2026-11-03): UTC time to the second, machine, gateway
 * username, channel, network, the command and its seq, the full server response, the WebSocket close code and reason
 * and how long the connection had been open, and reconnect attempts and timing.
 *
 * Every line starts with "ZELLO INCIDENT" so a window around an incident can be cut from the log with a text search.
 * Passwords are never logged, and token-like fields in server responses are masked.
 */
public final class ZelloIncidentLog
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloIncidentLog.class);
    public static final String PREFIX = "ZELLO INCIDENT";
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(\"[A-Za-z_]*(?:token|password|passwd|secret|auth|key)[A-Za-z_]*\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);
    private static final String MACHINE = machineName();

    private ZelloIncidentLog()
    {
    }

    /**
     * Logs an incident.
     * @param event short name: disconnect, socket_error, connect_failed, server_error, reconnect_scheduled,
     * reconnected, connect_rate_limited, stop_ack_timeout
     * @param network label, e.g. "Zello Work (actionpage)"
     * @param user gateway username, or null
     * @param channel Zello channel name
     * @param details further key=value pairs, already formatted (values with spaces quoted by the caller via q())
     */
    public static void log(String event, String network, String user, String channel, String details)
    {
        mLog.info("{} utc={} machine={} network={} user={} channel={} event={}{}", PREFIX,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), MACHINE, q(network), q(user), q(channel), event,
                details == null || details.isEmpty() ? "" : " " + details);
    }

    /**
     * Quotes a value for the incident line.  Null becomes -.
     */
    public static String q(String value)
    {
        if(value == null || value.isEmpty())
        {
            return "-";
        }

        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace('\n', ' ').replace('\r', ' ') + "\"";
    }

    /**
     * Masks the value of any token, password, secret, auth or key field in a JSON text.
     */
    public static String redact(String json)
    {
        if(json == null)
        {
            return null;
        }

        return SECRET_FIELD.matcher(json).replaceAll("$1\"***\"");
    }

    /**
     * Formats a duration in milliseconds as e.g. 2h19m43s, 4m05s or 12.3s.  Negative means unknown.
     */
    public static String duration(long ms)
    {
        if(ms < 0)
        {
            return "-";
        }

        long seconds = ms / 1000;

        if(seconds < 60)
        {
            return String.format("%.1fs", ms / 1000.0);
        }

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format("%dh%02dm%02ds", hours, minutes, secs) : String.format("%dm%02ds", minutes, secs);
    }

    private static String machineName()
    {
        String name = System.getenv("COMPUTERNAME");

        if(name == null || name.isEmpty())
        {
            try
            {
                name = InetAddress.getLocalHost().getHostName();
            }
            catch(Exception e)
            {
                name = "unknown";
            }
        }

        return name;
    }
}
