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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton pool manager for shared Zello WebSocket connections.
 * Groups broadcasters by (wsUrl + username) — same credentials share one WebSocket.
 *
 * Usage:
 *   ZelloConnectionPool pool = ZelloConnectionPool.getInstance();
 *   ZelloSharedConnection conn = pool.acquire(wsUrl, networkName, username, password, channelName, broadcaster);
 *   ...
 *   pool.release(wsUrl, username, channelName);
 */
public class ZelloConnectionPool
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloConnectionPool.class);
    private static final ZelloConnectionPool INSTANCE = new ZelloConnectionPool();

    // Key: wsUrl + "|" + username
    private final ConcurrentHashMap<String, ZelloSharedConnection> mConnections = new ConcurrentHashMap<>();

    private ZelloConnectionPool() {}

    public static ZelloConnectionPool getInstance()
    {
        return INSTANCE;
    }

    /**
     * Builds the pool key from WebSocket URL and username.
     */
    private static String key(String wsUrl, String username)
    {
        return wsUrl + "|" + (username != null ? username : "");
    }

    /**
     * Acquires (or creates) a shared connection for the given credentials,
     * registers the broadcaster for the specified channel, and ensures the connection is started.
     *
     * @param wsUrl WebSocket URL (e.g., wss://zellowork.io/ws/networkname)
     * @param networkName Zello Work network name
     * @param username Zello account username
     * @param password Zello account password
     * @param channelName Zello channel name this broadcaster handles
     * @param broadcaster the broadcaster to register
     * @return the shared connection
     */
    public synchronized ZelloSharedConnection acquire(String wsUrl, String networkName,
                                                      String username, String password,
                                                      String channelName,
                                                      AbstractZelloBroadcaster<?> broadcaster)
    {
        String k = key(wsUrl, username);
        ZelloSharedConnection conn = mConnections.get(k);

        if(conn == null)
        {
            mLog.info("[Pool] Creating new shared connection for {} (user: {})", wsUrl, username);
            conn = new ZelloSharedConnection(wsUrl, networkName, username, password);
            mConnections.put(k, conn);
        }

        conn.register(channelName, broadcaster);
        conn.start();

        mLog.info("[Pool] Acquired connection for channel '{}' (key: {})", channelName, k);
        return conn;
    }

    /**
     * Releases a channel from its shared connection. If no channels remain,
     * the connection is stopped and removed from the pool.
     *
     * @param wsUrl WebSocket URL
     * @param username Zello account username
     * @param channelName Zello channel name to release
     */
    public synchronized void release(String wsUrl, String username, String channelName)
    {
        String k = key(wsUrl, username);
        ZelloSharedConnection conn = mConnections.get(k);

        if(conn != null)
        {
            conn.unregister(channelName);
            mLog.info("[Pool] Released channel '{}' from connection (key: {})", channelName, k);

            // ZelloSharedConnection.unregister() auto-stops when empty.
            // Remove from pool map when no broadcasters remain.
            if(conn.isEmpty())
            {
                mConnections.remove(k);
                mLog.info("[Pool] Removed empty connection (key: {})", k);
            }
        }
    }

    /**
     * Returns the number of active shared connections in the pool.
     */
    public int getConnectionCount()
    {
        return mConnections.size();
    }
}
