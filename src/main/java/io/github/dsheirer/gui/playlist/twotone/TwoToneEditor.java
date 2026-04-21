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
import javafx.scene.layout.Region;
import javafx.application.Platform;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class TwoToneEditor extends VBox
{
    public static final double[] MOTOROLA_QCII = {
            330.5, 349.0, 368.5, 389.0, 410.6, 433.7, 457.9, 483.5, 510.5, 539.0,
            569.1, 600.9, 634.5, 669.9, 707.3, 746.8, 788.5, 832.5, 879.0, 928.1,
            321.7, 339.6, 358.6, 378.6, 399.8, 422.1, 445.7, 470.5, 496.8, 1092.4,
            524.6, 553.9, 584.8, 617.4, 651.9, 688.3, 726.8, 767.4, 810.2, 855.5,
            903.2, 953.7, 1006.9, 1063.2, 1122.5, 1185.2, 1251.4, 1321.2, 1395.0, 1472.9,
            1555.2, 1642.4, 1733.7, 1830.5, 1930.2, 2036.0, 2149.2, 2268.6, 2395.0, 2528.5
    };

    public static final double[] PLECTRON = {
            282.2, 294.7, 307.8, 321.4, 335.6, 350.5, 366.0, 382.0, 399.2, 416.9,
            435.3, 454.6, 474.8, 495.8, 517.8, 540.7, 564.7, 589.7, 615.8, 643.0,
            672.0, 701.0, 732.0, 765.0, 799.0, 834.0, 871.0, 910.0, 950.0, 992.0,
            1036.0, 1082.0, 1130.0, 1180.0, 1232.0, 1287.0, 1344.0, 1403.0, 1465.0,
            1530.0, 1598.0, 1669.0, 1743.0, 1820.0, 1901.0, 1985.0, 2073.0, 2164.0,
            2260.0, 2361.0, 2465.0, 2575.0, 2688.0, 2807.0, 2932.0, 3062.0
    };

    private static final List<String> STANDARD_FREQUENCIES;

    static {
        Set<Double> allFreqs = new TreeSet<>();
        for (double d : MOTOROLA_QCII) allFreqs.add(d);
        for (double d : PLECTRON) allFreqs.add(d);
        STANDARD_FREQUENCIES = allFreqs.stream().map(String::valueOf).collect(Collectors.toList());
    }

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


        GridPane editorGrid = new GridPane();
        editorGrid.setHgap(10);
        editorGrid.setVgap(5);

        TextField aliasField = new TextField();
        ComboBox<String> typeSelector = new ComboBox<>();
        typeSelector.getItems().addAll("A/B Tones", "Long A Tone Only");
        typeSelector.getSelectionModel().select(0);
        ComboBox<String> toneAField = new ComboBox<>();
        toneAField.setEditable(true);
        toneAField.getItems().addAll(STANDARD_FREQUENCIES);
        ComboBox<String> toneBField = new ComboBox<>();
        toneBField.setEditable(true);
        toneBField.getItems().addAll(STANDARD_FREQUENCIES);

        javafx.beans.value.ChangeListener<String> filterListenerA = (obs, oldValue, newValue) -> {
            if (newValue == null) return;
            List<String> filtered = STANDARD_FREQUENCIES.stream()
                    .filter(f -> f.startsWith(newValue))
                    .collect(Collectors.toList());
            Platform.runLater(() -> {
                if (filtered.equals(toneAField.getItems())) return;
                String text = toneAField.getEditor().getText();
                int caret = toneAField.getEditor().getCaretPosition();
                toneAField.getItems().setAll(filtered);
                toneAField.getEditor().setText(text);
                toneAField.getEditor().positionCaret(caret);
                if (!filtered.isEmpty() && toneAField.isFocused()) toneAField.show();
            });
        };
        toneAField.getEditor().textProperty().addListener(filterListenerA);

        javafx.beans.value.ChangeListener<String> filterListenerB = (obs, oldValue, newValue) -> {
            if (newValue == null) return;
            List<String> filtered = STANDARD_FREQUENCIES.stream()
                    .filter(f -> f.startsWith(newValue))
                    .collect(Collectors.toList());
            Platform.runLater(() -> {
                if (filtered.equals(toneBField.getItems())) return;
                String text = toneBField.getEditor().getText();
                int caret = toneBField.getEditor().getCaretPosition();
                toneBField.getItems().setAll(filtered);
                toneBField.getEditor().setText(text);
                toneBField.getEditor().positionCaret(caret);
                if (!filtered.isEmpty() && toneBField.isFocused()) toneBField.show();
            });
        };
        toneBField.getEditor().textProperty().addListener(filterListenerB);
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
                    toneBField.getEditor().clear();
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

                try {
                    oldVal.setToneA(toneAField.getEditor().getText().isEmpty() ? 0 : Double.parseDouble(toneAField.getEditor().getText()));
                } catch (NumberFormatException e) {
                    oldVal.setToneA(0.0);
                }
                try {
                    oldVal.setToneB(toneBField.getEditor().getText().isEmpty() ? 0 : Double.parseDouble(toneBField.getEditor().getText()));
                } catch (NumberFormatException e) {
                    oldVal.setToneB(0.0);
                }
            }
            if (newVal != null) {
                aliasField.textProperty().bindBidirectional(newVal.aliasProperty());
                if (newVal.isLongATone()) {
                    typeSelector.getSelectionModel().select("Long A Tone Only");
                } else {
                    typeSelector.getSelectionModel().select("A/B Tones");
                }
                toneAField.getEditor().setText(String.valueOf(newVal.getToneA()));
                toneAField.setValue(String.valueOf(newVal.getToneA()));
                if (newVal.getToneB() == 0.0) {
                    toneBField.getEditor().setText("");
                    toneBField.setValue(null);
                } else {
                    toneBField.getEditor().setText(String.valueOf(newVal.getToneB()));
                    toneBField.setValue(String.valueOf(newVal.getToneB()));
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
                toneAField.getEditor().clear();
                toneAField.setValue(null);
                toneBField.getEditor().clear();
                toneBField.setValue(null);
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

        toneAField.getEditor().textProperty().addListener((obs, o, n) -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null && !n.isEmpty()) {
                try { sel.setToneA(Double.parseDouble(n)); } catch (Exception ignored) {}
            }
        });
        toneBField.getEditor().textProperty().addListener((obs, o, n) -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null && !n.isEmpty()) {
                try { sel.setToneB(Double.parseDouble(n)); } catch (Exception ignored) {}
            }
        });

        // Save button for the configuration tab
        HBox configBtnBox = new HBox(10);
        Button saveBtn = new Button("Save");
        saveBtn.setOnAction(e -> {
            mPlaylistManager.schedulePlaylistSave();
        });
        configBtnBox.getChildren().addAll(saveBtn);

        // Table buttons (right of the table)
        VBox tableBtnBox = new VBox(5);
        tableBtnBox.setMinWidth(Region.USE_PREF_SIZE);

        MenuButton newBtn = new MenuButton("New");
        newBtn.setMaxWidth(Double.MAX_VALUE);
        MenuItem newDetectorItem = new MenuItem("Detector");
        newDetectorItem.setOnAction(e -> {
            TwoToneConfiguration conf = new TwoToneConfiguration();
            conf.setAlias("New Detector");
            mObservableConfigs.add(conf);
            syncToPlaylist();
            mTableView.getSelectionModel().select(conf);
            mTableView.scrollTo(conf);
        });
        newBtn.getItems().add(newDetectorItem);

        Button delBtn = new Button("Delete");
        delBtn.setMaxWidth(Double.MAX_VALUE);
        delBtn.setOnAction(e -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                mObservableConfigs.remove(sel);
                syncToPlaylist();
            }
        });

        Button cloneBtn = new Button("Clone");
        cloneBtn.setMaxWidth(Double.MAX_VALUE);
        cloneBtn.setOnAction(e -> {
            TwoToneConfiguration sel = mTableView.getSelectionModel().getSelectedItem();
            if (sel != null) {
                TwoToneConfiguration copy = sel.copyOf();
                copy.setAlias(copy.getAlias() + " (Copy)");
                mObservableConfigs.add(copy);
                syncToPlaylist();
                mTableView.getSelectionModel().select(copy);
                mTableView.scrollTo(copy);
            }
        });

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        refreshBtn.setOnAction(e -> {
            mObservableConfigs.setAll(mPlaylistManager.getCurrentPlaylist().getTwoToneConfigurations());
        });

        tableBtnBox.getChildren().addAll(newBtn, cloneBtn, delBtn, refreshBtn);

        HBox tableAndButtonsBox = new HBox(10);
        HBox.setHgrow(mTableView, Priority.ALWAYS);
        tableAndButtonsBox.getChildren().addAll(mTableView, tableBtnBox);
        VBox.setVgrow(tableAndButtonsBox, Priority.ALWAYS);


        mAliasEditor = new TwoToneAliasSelectionEditor(mPlaylistManager);

        TabPane tabPane = new TabPane();
        Tab configTab = new Tab("Configuration");
        configTab.setClosable(false);
        VBox configBox = new VBox(10, editorGrid, configBtnBox);
        configBox.setPadding(new Insets(10));
        configTab.setContent(configBox);

        Tab aliasTab = new Tab("Aliases");
        aliasTab.setClosable(false);
        aliasTab.setContent(mAliasEditor);

        tabPane.getTabs().addAll(configTab, aliasTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getChildren().addAll(new Label("Two Tone Paging Detectors"), tableAndButtonsBox, tabPane);
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
