package io.github.dsheirer.gui.preference.ai;

import io.github.dsheirer.preference.UserPreferences;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import org.controlsfx.control.ToggleSwitch;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

public class AIPreferenceEditor extends VBox {

    private final UserPreferences mUserPreferences;

    public AIPreferenceEditor(UserPreferences userPreferences) {
        mUserPreferences = userPreferences;

        setPadding(new Insets(10));
        setSpacing(10);

        ToggleSwitch enableAiSwitch = new ToggleSwitch("Enable AI Features");
        enableAiSwitch.setSelected(mUserPreferences.getAIPreference().isAIEnabled());
        enableAiSwitch.selectedProperty().addListener((observable, oldValue, newValue) -> {
            mUserPreferences.getAIPreference().setAIEnabled(newValue);
        });

        Label explanationLabel = new Label("If turned on, the application will save the last 5 audio files from each channel on the computer’s hard drive (to allow for review of audio).");
        explanationLabel.setWrapText(true);

        Label apiKeyLabel = new Label("Gemini API Key:");
        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setText(mUserPreferences.getAIPreference().getGeminiApiKey());
        apiKeyField.textProperty().addListener((observable, oldValue, newValue) -> {
            mUserPreferences.getAIPreference().setGeminiApiKey(newValue);
        });

        Button testButton = new Button("Test");
        Label testResultLabel = new Label("");

        Label modelLabel = new Label("Gemini Model:");
        ComboBox<String> modelComboBox = new ComboBox<>();
        modelComboBox.setEditable(true);
        modelComboBox.setValue(mUserPreferences.getAIPreference().getGeminiModel());
        modelComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                mUserPreferences.getAIPreference().setGeminiModel(newValue);
            }
        });
        HBox modelBox = new HBox(10, modelLabel, modelComboBox);
        modelBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Hyperlink apiKeyLink = new Hyperlink("Get a Gemini API Key here");
        apiKeyLink.setOnAction(e -> {
            try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(new URI("https://aistudio.google.com/app/apikey"));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        testButton.setOnAction(e -> {
            testResultLabel.setText("Testing...");
            String apiKey = apiKeyField.getText();
            if (apiKey == null || apiKey.trim().isEmpty()) {
                testResultLabel.setText("Please enter an API key first.");
                return;
            }

            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey.trim()))
                    .GET()
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        Platform.runLater(() -> {
                            if (response.statusCode() == 200) {
                                testResultLabel.setText("Test passed");
                                try {
                                    ObjectMapper mapper = new ObjectMapper();
                                    JsonNode root = mapper.readTree(response.body());
                                    JsonNode models = root.get("models");
                                    if (models != null && models.isArray()) {
                                        modelComboBox.getItems().clear();
                                        for (JsonNode model : models) {
                                            JsonNode nameNode = model.get("name");
                                            if (nameNode != null) {
                                                modelComboBox.getItems().add(nameNode.asText());
                                            }
                                        }
                                        if (!modelComboBox.getItems().contains(modelComboBox.getValue()) && !modelComboBox.getItems().isEmpty()) {
                                            modelComboBox.setValue(modelComboBox.getItems().get(0));
                                        }
                                    }
                                } catch (Exception ex) {
                                    testResultLabel.setText("Test passed, but failed to parse models.");
                                }
                            } else {
                                testResultLabel.setText("Test failed: " + response.statusCode());
                            }
                        });
                    }).exceptionally(ex -> {
                        Platform.runLater(() -> testResultLabel.setText("Test failed: " + ex.getMessage()));
                        return null;
                    });
        });

        HBox apiKeyBox = new HBox(10, apiKeyLabel, apiKeyField, testButton, testResultLabel);
        apiKeyBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox settingsBox = new VBox(10, explanationLabel, apiKeyBox, modelBox, apiKeyLink);
        settingsBox.visibleProperty().bind(enableAiSwitch.selectedProperty());
        settingsBox.managedProperty().bind(enableAiSwitch.selectedProperty());

        getChildren().addAll(enableAiSwitch, settingsBox);
    }
}
