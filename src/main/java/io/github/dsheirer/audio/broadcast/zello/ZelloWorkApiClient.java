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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for the Zello Work Server REST API (v1.1.0).
 * Used to fetch available channels for a user from the Zello Work network.
 *
 * Base URL: https://{network}.zellowork.com/
 *
 * Authentication flow (per official docs):
 * 1. GET /user/gettoken  (no params) → returns {token, sid, code:"200"}
 * 2. POST /user/login?sid={sid}  with username + password hash
 *    password = md5(md5(rawPassword) + token + apiKey)
 * 3. GET /user/get/login/{username}?sid={sid} → returns user object with channels
 * 4. GET /user/logout?sid={sid}
 */
public class ZelloWorkApiClient
{
    private static final Logger mLog = LoggerFactory.getLogger(ZelloWorkApiClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * Base URL candidates to try, in order.
     * The official v1.1.0 docs specify just the root, but some older Zello Work
     * deployments use /server/json/ as a path prefix.
     */
    private static final String[] BASE_PATHS = {"", "server/json/"};

    /**
     * Fetches the list of channels that the specified gateway user has access to.
     * Authenticates to the REST API using admin credentials, then looks up the
     * gateway user's channel assignments.
     *
     * @param network Zello Work network name (e.g., "actionpage")
     * @param adminUsername admin account username for REST API authentication
     * @param adminPassword admin account raw password
     * @param apiKey the Zello Work Server API key (from admin panel)
     * @param gatewayUsername the gateway user whose channels to fetch
     * @return list of channel names the gateway user belongs to
     * @throws Exception on auth failure or network error
     */
    public static List<String> fetchUserChannels(String network, String adminUsername,
                                                  String adminPassword, String apiKey,
                                                  String gatewayUsername) throws Exception
    {
        // Trim inputs to avoid whitespace issues from copy/paste
        network = network != null ? network.trim() : "";
        adminUsername = adminUsername != null ? adminUsername.trim() : "";
        adminPassword = adminPassword != null ? adminPassword.trim() : "";
        apiKey = apiKey != null ? apiKey.trim() : "";
        gatewayUsername = gatewayUsername != null ? gatewayUsername.trim() : "";

        mLog.info("[ZelloAPI] fetchUserChannels: network='{}', admin='{}', gatewayUser='{}', apiKey length={}",
            network, adminUsername, gatewayUsername, apiKey.length());

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        String host = "https://" + network + ".zellowork.com/";

        // Try each base path until gettoken succeeds
        String baseUrl = null;
        String token = null;
        String sid = null;

        for(String path : BASE_PATHS)
        {
            String candidateBase = host + path;
            String tokenUrl = candidateBase + "user/gettoken";

            mLog.info("[ZelloAPI] Trying gettoken at: {}", tokenUrl);

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(TIMEOUT)
                .GET()
                .build();

            try
            {
                HttpResponse<String> tokenResponse = client.send(tokenRequest,
                    HttpResponse.BodyHandlers.ofString());

                mLog.info("[ZelloAPI] gettoken HTTP status: {}, body: {}",
                    tokenResponse.statusCode(), tokenResponse.body());

                // Log if we were redirected
                if(!tokenResponse.uri().toString().equals(tokenUrl))
                {
                    mLog.info("[ZelloAPI] Request was redirected to: {}", tokenResponse.uri());
                }

                JsonObject tokenJson = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
                String code = tokenJson.has("code") ? tokenJson.get("code").getAsString() : "";

                if("200".equals(code) && tokenJson.has("token") && tokenJson.has("sid"))
                {
                    baseUrl = candidateBase;
                    token = tokenJson.get("token").getAsString();
                    sid = tokenJson.get("sid").getAsString();
                    mLog.info("[ZelloAPI] gettoken succeeded at base: {}, sid={}", baseUrl, sid);
                    break;
                }
                else
                {
                    mLog.warn("[ZelloAPI] gettoken at {} returned code={}, status={}",
                        candidateBase, code,
                        tokenJson.has("status") ? tokenJson.get("status").getAsString() : "n/a");
                }
            }
            catch(Exception e)
            {
                mLog.warn("[ZelloAPI] gettoken at {} failed: {}", candidateBase, e.getMessage());
            }
        }

        if(baseUrl == null || token == null || sid == null)
        {
            throw new Exception("Failed to get auth token from Zello Work API.\n"
                + "Tried URLs:\n"
                + "  " + host + "user/gettoken\n"
                + "  " + host + "server/json/user/gettoken\n"
                + "Verify that the network name '" + network + "' is correct.");
        }

        try
        {
            // Step 2: Login with admin credentials
            // Per official docs: password = md5( md5(rawPassword) + token + apiKey )
            String passwordHash = md5(md5(adminPassword) + token + apiKey);

            String loginBody = "username=" + URLEncoder.encode(adminUsername, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(passwordHash, StandardCharsets.UTF_8);

            String loginUrl = baseUrl + "user/login?sid=" + sid;
            mLog.info("[ZelloAPI] Logging in as admin '{}' at: {}", adminUsername, loginUrl);

            HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create(loginUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .build();

            HttpResponse<String> loginResponse = client.send(loginRequest,
                HttpResponse.BodyHandlers.ofString());
            mLog.info("[ZelloAPI] login response: {}", loginResponse.body());

            JsonObject loginJson = JsonParser.parseString(loginResponse.body()).getAsJsonObject();

            String loginCode = loginJson.has("code") ? loginJson.get("code").getAsString() : "";
            if(!"200".equals(loginCode))
            {
                String error = loginJson.has("status") ?
                    loginJson.get("status").getAsString() : loginResponse.body();
                throw new Exception("Admin login failed (code " + loginCode + "): " + error
                    + "\nVerify admin credentials and API key.");
            }

            mLog.info("[ZelloAPI] Admin login successful, fetching channels for gateway user '{}'",
                gatewayUsername);

            // Step 3: Fetch gateway user's channel list
            String encodedGatewayUser = URLEncoder.encode(gatewayUsername, StandardCharsets.UTF_8);
            String userUrl = baseUrl + "user/get/login/" + encodedGatewayUser + "?sid=" + sid;
            mLog.info("[ZelloAPI] Fetching channels for gateway user '{}' at: {}", gatewayUsername, userUrl);

            HttpRequest userRequest = HttpRequest.newBuilder()
                .uri(URI.create(userUrl))
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> userResponse = client.send(userRequest,
                HttpResponse.BodyHandlers.ofString());
            mLog.info("[ZelloAPI] user/get HTTP status: {}, body length: {}",
                userResponse.statusCode(), userResponse.body().length());

            JsonObject userJson = JsonParser.parseString(userResponse.body()).getAsJsonObject();

            String userCode = userJson.has("code") ? userJson.get("code").getAsString() : "";
            if(!"200".equals(userCode))
            {
                throw new Exception("Failed to fetch user channels: " + userResponse.body());
            }

            List<String> channels = new ArrayList<>();

            if(userJson.has("users") && userJson.get("users").isJsonArray())
            {
                JsonArray users = userJson.getAsJsonArray("users");
                if(!users.isEmpty())
                {
                    JsonObject user = users.get(0).getAsJsonObject();
                    if(user.has("channels") && user.get("channels").isJsonArray())
                    {
                        JsonArray channelArray = user.getAsJsonArray("channels");
                        for(JsonElement ch : channelArray)
                        {
                            if(ch.isJsonPrimitive())
                            {
                                channels.add(ch.getAsString());
                            }
                            else if(ch.isJsonObject() && ch.getAsJsonObject().has("name"))
                            {
                                channels.add(ch.getAsJsonObject().get("name").getAsString());
                            }
                        }
                    }
                }
            }

            Collections.sort(channels, String.CASE_INSENSITIVE_ORDER);
            mLog.info("[ZelloAPI] Found {} channels for gateway user '{}'", channels.size(), gatewayUsername);
            return channels;
        }
        finally
        {
            // Step 4: Logout
            try
            {
                HttpRequest logoutRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "user/logout?sid=" + sid))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
                client.send(logoutRequest, HttpResponse.BodyHandlers.ofString());
                mLog.debug("[ZelloAPI] Logged out");
            }
            catch(Exception e)
            {
                mLog.debug("[ZelloAPI] Logout failed (non-critical): {}", e.getMessage());
            }
        }
    }

    /**
     * Fetches all channels from the network, optionally filtered by search term.
     *
     * @param network Zello Work network name
     * @param username admin username for API login
     * @param password admin raw password
     * @param apiKey Zello Work Server API key
     * @param search optional search term to filter channels (null for all)
     * @return list of channel names
     * @throws Exception on auth failure or network error
     */
    public static List<String> fetchAllChannels(String network, String username,
                                                 String password, String apiKey,
                                                 String search) throws Exception
    {
        network = network != null ? network.trim() : "";
        username = username != null ? username.trim() : "";
        password = password != null ? password.trim() : "";
        apiKey = apiKey != null ? apiKey.trim() : "";

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        String host = "https://" + network + ".zellowork.com/";

        // Try each base path until gettoken succeeds
        String baseUrl = null;
        String token = null;
        String sid = null;

        for(String path : BASE_PATHS)
        {
            String candidateBase = host + path;
            String tokenUrl = candidateBase + "user/gettoken";

            mLog.info("[ZelloAPI] Trying gettoken at: {}", tokenUrl);

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(TIMEOUT)
                .GET()
                .build();

            try
            {
                HttpResponse<String> tokenResponse = client.send(tokenRequest,
                    HttpResponse.BodyHandlers.ofString());

                JsonObject tokenJson = JsonParser.parseString(tokenResponse.body()).getAsJsonObject();
                String code = tokenJson.has("code") ? tokenJson.get("code").getAsString() : "";

                if("200".equals(code) && tokenJson.has("token") && tokenJson.has("sid"))
                {
                    baseUrl = candidateBase;
                    token = tokenJson.get("token").getAsString();
                    sid = tokenJson.get("sid").getAsString();
                    mLog.info("[ZelloAPI] gettoken succeeded at base: {}", baseUrl);
                    break;
                }
            }
            catch(Exception e)
            {
                mLog.warn("[ZelloAPI] gettoken at {} failed: {}", candidateBase, e.getMessage());
            }
        }

        if(baseUrl == null || token == null || sid == null)
        {
            throw new Exception("Failed to get auth token from Zello Work API.\n"
                + "Tried URLs:\n"
                + "  " + host + "user/gettoken\n"
                + "  " + host + "server/json/user/gettoken\n"
                + "Verify that the network name '" + network + "' is correct.");
        }

        try
        {
            // Step 2: Login
            String passwordHash = md5(md5(password) + token + apiKey);
            String loginBody = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(passwordHash, StandardCharsets.UTF_8);

            HttpRequest loginRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "user/login?sid=" + sid))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody))
                .build();

            HttpResponse<String> loginResponse = client.send(loginRequest,
                HttpResponse.BodyHandlers.ofString());
            JsonObject loginJson = JsonParser.parseString(loginResponse.body()).getAsJsonObject();

            String loginCode = loginJson.has("code") ? loginJson.get("code").getAsString() : "";
            if(!"200".equals(loginCode))
            {
                String error = loginJson.has("status") ?
                    loginJson.get("status").getAsString() : loginResponse.body();
                throw new Exception("Login failed (code " + loginCode + "): " + error);
            }

            // Step 3: Fetch channels
            StringBuilder channelUrl = new StringBuilder(baseUrl + "channel/get");
            if(search != null && !search.trim().isEmpty())
            {
                channelUrl.append("/search/")
                    .append(URLEncoder.encode(search.trim(), StandardCharsets.UTF_8));
            }
            channelUrl.append("?sid=").append(sid);

            HttpRequest channelRequest = HttpRequest.newBuilder()
                .uri(URI.create(channelUrl.toString()))
                .timeout(TIMEOUT)
                .GET()
                .build();

            HttpResponse<String> channelResponse = client.send(channelRequest,
                HttpResponse.BodyHandlers.ofString());
            JsonObject channelJson = JsonParser.parseString(channelResponse.body()).getAsJsonObject();

            List<String> channels = new ArrayList<>();
            if(channelJson.has("channels") && channelJson.get("channels").isJsonArray())
            {
                JsonArray channelArray = channelJson.getAsJsonArray("channels");
                for(JsonElement ch : channelArray)
                {
                    if(ch.isJsonObject())
                    {
                        JsonObject chObj = ch.getAsJsonObject();
                        if(chObj.has("name"))
                        {
                            channels.add(chObj.get("name").getAsString());
                        }
                    }
                }
            }

            Collections.sort(channels, String.CASE_INSENSITIVE_ORDER);
            mLog.info("[ZelloAPI] Found {} channels (search: '{}')", channels.size(),
                search != null ? search : "all");
            return channels;
        }
        finally
        {
            try
            {
                HttpRequest logoutRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "user/logout?sid=" + sid))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
                client.send(logoutRequest, HttpResponse.BodyHandlers.ofString());
            }
            catch(Exception e)
            {
                // non-critical
            }
        }
    }

    /**
     * Computes the MD5 hex digest of a string.
     */
    private static String md5(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for(byte b : digest)
            {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        }
        catch(Exception e)
        {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
