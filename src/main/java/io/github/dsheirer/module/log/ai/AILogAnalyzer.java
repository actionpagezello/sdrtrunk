package io.github.dsheirer.module.log.ai;

import io.github.dsheirer.preference.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AILogAnalyzer {
    private static final Logger mLog = LoggerFactory.getLogger(AILogAnalyzer.class);
    private final UserPreferences mUserPreferences;

    public AILogAnalyzer(UserPreferences userPreferences) {
        mUserPreferences = userPreferences;
    }

    public String analyze(String logContent) throws Exception {
        String apiKey = mUserPreferences.getAIPreference().getGeminiApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("Gemini API Key is missing. Cannot analyze log.");
        }

        mLog.info("Analyzing log content with Gemini...");

        try {
            String model = "gemini-1.5-pro";
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            // Simple json string escape to prevent malformed json payloads when log file contains quotes or new lines.
            String escapedLogContent = logContent.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            String promptText = "Analyze this log file. Translate cryptic stack traces and warning logs into plain-English explanations with actionable fixes:\\n\\n" + escapedLogContent;

            String jsonPayload = "{" +
                    "\"contents\": [{" +
                    "\"parts\":[{\"text\": \"" + promptText + "\"}]" +
                    "}]" +
                    "}";

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                 throw new Exception("API request failed with status code: " + response.statusCode() + " - " + response.body());
            }

            // Extract the 'text' field from the Gemini JSON response
            // We use simple string matching here instead of bringing in a json library if possible
            // A typical response looks like:
            // { "candidates": [ { "content": { "parts": [ { "text": "The analysis text here..." } ] } } ] }
            String responseBody = response.body();
            return extractTextFromJson(responseBody);

        } catch (Exception e) {
            mLog.error("Error analyzing log with Gemini API: " + e.getMessage(), e);
            throw new Exception("Error calling Gemini API: " + e.getMessage(), e);
        }
    }

    private String extractTextFromJson(String json) {
        try {
            String textKey = "\"text\":";
            int textIndex = json.indexOf(textKey);
            if (textIndex != -1) {
                int startIndex = json.indexOf("\"", textIndex + textKey.length());
                if (startIndex != -1) {
                    int endIndex = json.indexOf("\"", startIndex + 1);
                    // Find the unescaped closing quote
                    while(endIndex != -1 && json.charAt(endIndex - 1) == '\\') {
                       endIndex = json.indexOf("\"", endIndex + 1);
                    }
                    if (endIndex != -1) {
                        String result = json.substring(startIndex + 1, endIndex);
                        // basic unescape
                        result = result.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").replace("\\r", "\r").replace("\\t", "\t");
                        return result;
                    }
                }
            }
            return "Could not parse API response: " + json;
        } catch (Exception e) {
             return "Error parsing response: " + e.getMessage();
        }
    }
}
