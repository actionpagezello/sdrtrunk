package io.github.dsheirer.gui.preference.ai;

import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.ai.AIPreference;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;

public class AIPreferenceEditor extends VBox {
    private UserPreferences mUserPreferences;
    private AIPreference mAIPreference;

    private CheckBox mEnabledCheckBox;
    private PasswordField mApiKeyField;
    private Button mSaveButton;
    private Button mTestButton;
    private Label mStatusLabel;

    public AIPreferenceEditor(UserPreferences userPreferences) {
        mUserPreferences = userPreferences;
        mAIPreference = userPreferences.getAIPreference();

        setSpacing(10);
        setPadding(new Insets(10));

        mEnabledCheckBox = new CheckBox("Enable AI Optimization Module");
        mEnabledCheckBox.setSelected(mAIPreference.isEnabled());
        mEnabledCheckBox.setOnAction(e -> {
            mAIPreference.setEnabled(mEnabledCheckBox.isSelected());
        });

        Label apiKeyLabel = new Label("Gemini API Key:");
        mApiKeyField = new PasswordField();
        mApiKeyField.setText(mAIPreference.getApiKey());
        mApiKeyField.setPromptText("Enter your Gemini API key");

        Tooltip helpTooltip = new Tooltip("Get your Gemini API Key from Google AI Studio:\nhttps://aistudio.google.com/app/apikey");
        Label helpLabel = new Label(" (?) ");
        helpLabel.setTooltip(helpTooltip);
        helpLabel.setStyle("-fx-text-fill: blue; -fx-cursor: hand;");

        HBox keyBox = new HBox(10, apiKeyLabel, mApiKeyField, helpLabel);

        mStatusLabel = new Label("");

        mSaveButton = new Button("Save Key");
        mSaveButton.setOnAction(e -> {
            mAIPreference.setApiKey(mApiKeyField.getText());
            mStatusLabel.setText("API Key saved.");
            mStatusLabel.setTextFill(Color.GREEN);
            testApiKey();
        });

        mTestButton = new Button("Test Connection");
        mTestButton.setOnAction(e -> testApiKey());

        HBox buttonBox = new HBox(10, mSaveButton, mTestButton);

        getChildren().addAll(mEnabledCheckBox, keyBox, buttonBox, mStatusLabel);
    }

    private void testApiKey() {
        String apiKey = mApiKeyField.getText();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            mStatusLabel.setText("API key is empty.");
            mStatusLabel.setTextFill(Color.RED);
            return;
        }

        mStatusLabel.setText("Testing API key...");
        mStatusLabel.setTextFill(Color.BLACK);

        new Thread(() -> {
            boolean success = false;
            String errorMessage = "";
            try {
                // To test a pure REST API call without bringing in heavy Vertex AI deps right away if they fail
                // Let's use standard Java HTTP Client to verify the Gemini API key
                String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;
                java.net.URL url = new java.net.URL(urlStr);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String payload = "{\"contents\":[{\"parts\":[{\"text\":\"Hello\"}]}]}";
                try(java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    success = true;
                } else {
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        try (java.util.Scanner scanner = new java.util.Scanner(errorStream)) {
                            scanner.useDelimiter("\\A");
                            errorMessage = scanner.hasNext() ? scanner.next() : "Unknown error";
                        }
                    } else {
                        errorMessage = "HTTP Error " + code;
                    }
                }
            } catch (Exception ex) {
                errorMessage = ex.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalErrorMessage = errorMessage;

            Platform.runLater(() -> {
                if (finalSuccess) {
                    mStatusLabel.setText("Connection successful!");
                    mStatusLabel.setTextFill(Color.GREEN);
                } else {
                    mStatusLabel.setText("Connection failed: " + finalErrorMessage);
                    mStatusLabel.setTextFill(Color.RED);
                    mAIPreference.setEnabled(false);
                    mEnabledCheckBox.setSelected(false);
                }
            });
        }).start();
    }
}
