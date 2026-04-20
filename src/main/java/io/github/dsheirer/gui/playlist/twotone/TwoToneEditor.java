package io.github.dsheirer.gui.playlist.twotone;

import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.playlist.TwoToneConfiguration;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.converter.NumberStringConverter;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import javafx.collections.ListChangeListener;


import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.net.URL;

public class TwoToneEditor extends VBox
{
    private final PlaylistManager mPlaylistManager;
    private TableView<TwoToneConfiguration> mTableView;
    private ObservableList<TwoToneConfiguration> mObservableConfigs;
    private TwoToneAliasSelectionEditor mAliasEditor;

    public TwoToneEditor(PlaylistManager playlistManager)
    {
        mPlaylistManager = playlistManager;
        setSpacing(10);
        setPadding(new Insets(10));

        mObservableConfigs = FXCollections.observableArrayList(TwoToneConfiguration.extractor());
        if (playlistManager.getCurrentPlaylist() != null) {
            mObservableConfigs.addAll(playlistManager.getCurrentPlaylist().getTwoToneConfigurations());
        }

        mTableView = new TableView<>(mObservableConfigs);

        TableColumn<TwoToneConfiguration, String> aliasCol = new TableColumn<>("Alias");
        aliasCol.setCellValueFactory(new PropertyValueFactory<>("alias"));
        TableColumn<TwoToneConfiguration, Double> toneACol = new TableColumn<>("Tone A");
        toneACol.setCellValueFactory(new PropertyValueFactory<>("toneA"));
        TableColumn<TwoToneConfiguration, Double> toneBCol = new TableColumn<>("Tone B");
        toneBCol.setCellValueFactory(new PropertyValueFactory<>("toneB"));
        TableColumn<TwoToneConfiguration, Boolean> mqttCol = new TableColumn<>("MQTT Enabled");
        mqttCol.setCellValueFactory(new PropertyValueFactory<>("enableMqttPublish"));

        mTableView.getColumns().addAll(aliasCol, toneACol, toneBCol, mqttCol);
        VBox.setVgrow(mTableView, Priority.ALWAYS);

        GridPane editorGrid = new GridPane();
        editorGrid.setHgap(10);
        editorGrid.setVgap(5);

        TextField aliasField = new TextField();
        ComboBox<String> typeSelector = new ComboBox<>();
        typeSelector.getItems().addAll("A/B Tones", "Long A Tone Only");
        typeSelector.getSelectionModel().select(0);
        TextField toneAField = new TextField();
        TextField toneBField = new TextField();
        ComboBox<String> zelloField = new ComboBox<>();
        for (BroadcastConfiguration bc : mPlaylistManager.getBroadcastModel().getBroadcastConfigurations()) {
            if (bc.getBroadcastServerType() == BroadcastServerType.ZELLO_WORK || bc.getBroadcastServerType() == BroadcastServerType.ZELLO) {
                if (bc.getName() != null) {
                    zelloField.getItems().add(bc.getName());
                }
            }
        }

        CheckBox mqttCheck = new CheckBox("Enable MQTT Publish");
        TextField topicField = new TextField();
        TextArea payloadArea = new TextArea();
        payloadArea.setPrefRowCount(3);

        topicField.disableProperty().bind(mqttCheck.selectedProperty().not());
        payloadArea.disableProperty().bind(mqttCheck.selectedProperty().not());

        editorGrid.add(new Label("Alias:"), 0, 0);
        editorGrid.add(aliasField, 1, 0);
        editorGrid.add(new Label("Type:"), 0, 1);
        editorGrid.add(typeSelector, 1, 1);
        editorGrid.add(new Label("Tone A:"), 0, 2);
        editorGrid.add(toneAField, 1, 2);
        Label toneBLabel = new Label("Tone B:");
        editorGrid.add(toneBLabel, 0, 3);
        editorGrid.add(toneBField, 1, 3);

        toneBLabel.disableProperty().bind(Bindings.equal(typeSelector.valueProperty(), "Long A Tone Only"));
        toneBField.disableProperty().bind(Bindings.equal(typeSelector.valueProperty(), "Long A Tone Only"));

        typeSelector.valueProperty().addListener((obs, oldVal, newVal) -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null && newVal != null) {
                boolean isLong = newVal.equals("Long A Tone Only");
                sel.setLongATone(isLong);
                if (isLong) {
                    toneBField.clear();
                    sel.setToneB(0.0);
                }
            }
        });
        editorGrid.add(new Label("Zello Channel:"), 0, 4);
        editorGrid.add(zelloField, 1, 4);

        editorGrid.add(mqttCheck, 2, 0, 2, 1);
        editorGrid.add(new Label("MQTT Topic:"), 2, 1);
        editorGrid.add(topicField, 3, 1);
        editorGrid.add(new Label("MQTT Payload:"), 2, 2);
        editorGrid.add(payloadArea, 3, 2, 1, 2);

        CheckBox textMessageCheck = new CheckBox("Enable Text Message");
        Label textMessageInfo = new Label("Messages are sent to the Zello Channel.");
        textMessageInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        TextField templateField = new TextField();

        HBox previewBox = new HBox(5);
        Label previewLabel = new Label("Preview:");
        Label previewText = new Label();
        previewText.setStyle("-fx-font-style: italic;");
        previewBox.getChildren().addAll(previewLabel, previewText);

        Runnable updatePreview = () -> {
            String template = templateField.getText() != null && !templateField.getText().isEmpty() ? templateField.getText() : "Dispatch Received: %ALIAS%";
            String alias = aliasField.getText() != null && !aliasField.getText().isEmpty() ? aliasField.getText() : "Unknown";
            previewText.setText(template.replace("%ALIAS%", alias));
        };

        CheckBox zelloAlertCheck = new CheckBox("Enable Zello Alert Tone");
        ComboBox<String> alertToneCombo = new ComboBox<>();
        alertToneCombo.getItems().addAll("alert1.wav", "alert2.wav");

        Button previewBtn = new Button("Preview");
        previewBtn.setOnAction(ev -> {
            String selectedFile = alertToneCombo.getValue();
            if (selectedFile != null && !selectedFile.isEmpty()) {
                try {
                    URL resource = TwoToneEditor.class.getResource("/audio/" + selectedFile);
                    if (resource != null) {
                        AudioInputStream ais = AudioSystem.getAudioInputStream(resource);
                        Clip clip = AudioSystem.getClip();
                        clip.open(ais);
                        clip.start();
                    } else {
                        System.err.println("Could not find audio resource: /audio/" + selectedFile);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });

        alertToneCombo.disableProperty().bind(zelloAlertCheck.selectedProperty().not());
        previewBtn.disableProperty().bind(zelloAlertCheck.selectedProperty().not());

        templateField.disableProperty().bind(textMessageCheck.selectedProperty().not());
        previewBox.visibleProperty().bind(textMessageCheck.selectedProperty());
        previewBox.managedProperty().bind(textMessageCheck.selectedProperty());

        editorGrid.add(textMessageCheck, 0, 5);
        editorGrid.add(textMessageInfo, 1, 5);
        editorGrid.add(new Label("Message Template:"), 0, 6);
        editorGrid.add(templateField, 1, 6);
        editorGrid.add(previewBox, 1, 7);
        editorGrid.add(zelloAlertCheck, 0, 8, 2, 1);
        editorGrid.add(new Label("Alert Tone File:"), 0, 9);
        editorGrid.add(alertToneCombo, 1, 9);
        editorGrid.add(previewBtn, 2, 9);


        mTableView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if(mAliasEditor != null) mAliasEditor.setTwoToneConfiguration(newVal);

            if (oldVal != null) {
                aliasField.textProperty().unbindBidirectional(oldVal.aliasProperty());
                zelloField.valueProperty().unbindBidirectional(oldVal.zelloChannelProperty());
                mqttCheck.selectedProperty().unbindBidirectional(oldVal.enableMqttPublishProperty());
                topicField.textProperty().unbindBidirectional(oldVal.mqttTopicProperty());
                payloadArea.textProperty().unbindBidirectional(oldVal.mqttPayloadProperty());
                zelloAlertCheck.selectedProperty().unbindBidirectional(oldVal.enableZelloAlertProperty());
                alertToneCombo.valueProperty().unbindBidirectional(oldVal.zelloAlertFileProperty());
                templateField.textProperty().unbindBidirectional(oldVal.templateProperty());
                textMessageCheck.selectedProperty().unbindBidirectional(oldVal.enableZelloTextMessageProperty());

                oldVal.setToneA(toneAField.getText().isEmpty() ? 0 : Double.parseDouble(toneAField.getText()));
                oldVal.setToneB(toneBField.getText().isEmpty() ? 0 : Double.parseDouble(toneBField.getText()));
            }
            if (newVal != null) {
                aliasField.textProperty().bindBidirectional(newVal.aliasProperty());
                if (newVal.isLongATone()) {
                    typeSelector.getSelectionModel().select("Long A Tone Only");
                } else {
                    typeSelector.getSelectionModel().select("A/B Tones");
                }
                toneAField.setText(String.valueOf(newVal.getToneA()));
                if (newVal.getToneB() == 0.0) {
                    toneBField.setText("");
                } else {
                    toneBField.setText(String.valueOf(newVal.getToneB()));
                }
                zelloField.valueProperty().bindBidirectional(newVal.zelloChannelProperty());
                mqttCheck.selectedProperty().bindBidirectional(newVal.enableMqttPublishProperty());
                topicField.textProperty().bindBidirectional(newVal.mqttTopicProperty());
                payloadArea.textProperty().bindBidirectional(newVal.mqttPayloadProperty());
                zelloAlertCheck.selectedProperty().bindBidirectional(newVal.enableZelloAlertProperty());
                alertToneCombo.valueProperty().bindBidirectional(newVal.zelloAlertFileProperty());
                templateField.textProperty().bindBidirectional(newVal.templateProperty());
                textMessageCheck.selectedProperty().bindBidirectional(newVal.enableZelloTextMessageProperty());
            } else {
                aliasField.clear();
                toneAField.clear();
                toneBField.clear();
                typeSelector.getSelectionModel().select("A/B Tones");
                zelloField.getSelectionModel().clearSelection();
                mqttCheck.setSelected(false);
                topicField.clear();
                payloadArea.clear();
                zelloAlertCheck.setSelected(false);
                alertToneCombo.getSelectionModel().clearSelection();
                templateField.clear();
                textMessageCheck.setSelected(false);
            }
            updatePreview.run();
        });

        // Basic double conversion listener
        aliasField.textProperty().addListener((obs, o, n) -> updatePreview.run());
        templateField.textProperty().addListener((obs, o, n) -> updatePreview.run());

        toneAField.textProperty().addListener((obs, o, n) -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null && !n.isEmpty()) {
                try { sel.setToneA(Double.parseDouble(n)); } catch (Exception ignored) {}
            }
        });
        toneBField.textProperty().addListener((obs, o, n) -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null && !n.isEmpty()) {
                try { sel.setToneB(Double.parseDouble(n)); } catch (Exception ignored) {}
            }
        });

        HBox btnBox = new HBox(10);
        Button addBtn = new Button("Add");
        addBtn.setOnAction(e -> {
            TwoToneConfiguration conf = new TwoToneConfiguration();
            conf.setAlias("New Detector");
            mObservableConfigs.add(conf);
            syncToPlaylist();
            mTableView.getSelectionModel().select(conf);
        });

        Button delBtn = new Button("Delete");
        delBtn.setOnAction(e -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                mObservableConfigs.remove(sel);
                syncToPlaylist();
            }
        });
        btnBox.getChildren().addAll(addBtn, delBtn);


        mAliasEditor = new TwoToneAliasSelectionEditor(mPlaylistManager);

        TabPane tabPane = new TabPane();
        Tab configTab = new Tab("Configuration");
        configTab.setClosable(false);
        VBox configBox = new VBox(10, editorGrid, btnBox);
        configBox.setPadding(new Insets(10));
        configTab.setContent(configBox);

        Tab aliasTab = new Tab("Aliases");
        aliasTab.setClosable(false);
        aliasTab.setContent(mAliasEditor);

        tabPane.getTabs().addAll(configTab, aliasTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getChildren().addAll(new Label("Two Tone Paging Detectors"), mTableView, tabPane);
    }

    private void syncToPlaylist() {
        if (mPlaylistManager.getCurrentPlaylist() != null) {
            mPlaylistManager.getCurrentPlaylist().setTwoToneConfigurations(mObservableConfigs);
            // PlaylistManager will listen to this change normally or we trigger a save if needed
        }
    }

    public void process(TwoToneTabRequest request)
    {
    }
}
