package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.module.decode.config.TwoToneDetectorConfiguration;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

public class TwoToneDetectorPanel extends TitledPane {

    private ListView<TwoToneDetectorConfiguration> mListView;
    private TwoToneDetectorConfigurationEditor mEditor;
    private ObservableList<TwoToneDetectorConfiguration> mItems;

    public TwoToneDetectorPanel() {
        super("Two-Tone Paging Detectors", null);
        setExpanded(false);

        mItems = FXCollections.observableArrayList();
        mListView = new ListView<>(mItems);
        mListView.setPrefHeight(150);
        mListView.setCellFactory(param -> new javafx.scene.control.ListCell<TwoToneDetectorConfiguration>() {
            @Override
            protected void updateItem(TwoToneDetectorConfiguration item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getLabel());
                }
            }
        });

        mEditor = new TwoToneDetectorConfigurationEditor();
        mEditor.setDisable(true);

        mListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            mEditor.setEditorValue(newValue);
            mEditor.setDisable(newValue == null);
        });

        Button addButton = new Button("Add");
        addButton.setOnAction(event -> {
            TwoToneDetectorConfiguration newConfig = new TwoToneDetectorConfiguration();
            mItems.add(newConfig);
            mListView.getSelectionModel().select(newConfig);
        });

        Button removeButton = new Button("Remove");
        removeButton.setOnAction(event -> {
            TwoToneDetectorConfiguration selected = mListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                mItems.remove(selected);
            }
        });

        HBox buttonBox = new HBox(10, addButton, removeButton);
        buttonBox.setPadding(new Insets(5, 0, 5, 0));

        VBox listBox = new VBox(mListView, buttonBox);
        VBox.setVgrow(mListView, Priority.ALWAYS);

        HBox mainBox = new HBox(10, listBox, mEditor);
        HBox.setHgrow(mEditor, Priority.ALWAYS);
        mainBox.setPadding(new Insets(10));

        setContent(mainBox);
    }

    public void setConfiguration(List<TwoToneDetectorConfiguration> configList) {
        mItems.clear();
        if (configList != null) {
            mItems.addAll(configList);
        }
    }

    public List<TwoToneDetectorConfiguration> getConfiguration() {
        return new ArrayList<>(mItems);
    }
}
