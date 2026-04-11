package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.gui.playlist.Editor;
import io.github.dsheirer.module.decode.config.TwoToneDetectorConfiguration;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.util.converter.DoubleStringConverter;
import javafx.util.converter.IntegerStringConverter;
import javafx.scene.control.TextFormatter;

public class TwoToneDetectorConfigurationEditor extends Editor<TwoToneDetectorConfiguration> {

    private TextField mLabelField;
    private TextField mToneAHzField;
    private TextField mToneADurationMsField;
    private CheckBox mLongToneCheckBox;
    private TextField mToneBHzField;
    private TextField mToneBDurationMsField;
    private CheckBox mZelloAlertEnabledCheckBox;
    private TextField mZelloChannelField;
    private TextField mZelloAlertTextField;
    private TwoToneDetectorConfiguration mConfiguration;

    public TwoToneDetectorConfigurationEditor() {
        GridPane gridPane = new GridPane();
        gridPane.setHgap(10);
        gridPane.setVgap(10);
        gridPane.setPadding(new Insets(10));

        int row = 0;

        Label labelLabel = new Label("Label:");
        GridPane.setHalignment(labelLabel, HPos.RIGHT);
        gridPane.add(labelLabel, 0, row);
        gridPane.add(getLabelField(), 1, row);
        GridPane.setHgrow(getLabelField(), Priority.ALWAYS);
        row++;

        Label toneAHzLabel = new Label("Tone A (Hz):");
        GridPane.setHalignment(toneAHzLabel, HPos.RIGHT);
        gridPane.add(toneAHzLabel, 0, row);
        gridPane.add(getToneAHzField(), 1, row);
        row++;

        Label toneADurationLabel = new Label("Tone A Duration (ms):");
        GridPane.setHalignment(toneADurationLabel, HPos.RIGHT);
        gridPane.add(toneADurationLabel, 0, row);
        gridPane.add(getToneADurationMsField(), 1, row);
        row++;

        Label longToneLabel = new Label("Long Tone:");
        GridPane.setHalignment(longToneLabel, HPos.RIGHT);
        gridPane.add(longToneLabel, 0, row);
        gridPane.add(getLongToneCheckBox(), 1, row);
        row++;

        Label toneBHzLabel = new Label("Tone B (Hz):");
        GridPane.setHalignment(toneBHzLabel, HPos.RIGHT);
        gridPane.add(toneBHzLabel, 0, row);
        gridPane.add(getToneBHzField(), 1, row);
        row++;

        Label toneBDurationLabel = new Label("Tone B Duration (ms):");
        GridPane.setHalignment(toneBDurationLabel, HPos.RIGHT);
        gridPane.add(toneBDurationLabel, 0, row);
        gridPane.add(getToneBDurationMsField(), 1, row);
        row++;

        Label zelloEnabledLabel = new Label("Zello Alert Enabled:");
        GridPane.setHalignment(zelloEnabledLabel, HPos.RIGHT);
        gridPane.add(zelloEnabledLabel, 0, row);
        gridPane.add(getZelloAlertEnabledCheckBox(), 1, row);
        row++;

        Label zelloChannelLabel = new Label("Zello Channel:");
        GridPane.setHalignment(zelloChannelLabel, HPos.RIGHT);
        gridPane.add(zelloChannelLabel, 0, row);
        gridPane.add(getZelloChannelField(), 1, row);
        row++;

        Label zelloAlertTextLabel = new Label("Zello Alert Text:");
        GridPane.setHalignment(zelloAlertTextLabel, HPos.RIGHT);
        gridPane.add(zelloAlertTextLabel, 0, row);
        gridPane.add(getZelloAlertTextField(), 1, row);

        getChildren().add(gridPane);

        // Setup state listeners
        getLongToneCheckBox().selectedProperty().addListener((observable, oldValue, newValue) -> {
            getToneBHzField().setDisable(newValue);
            getToneBDurationMsField().setDisable(newValue);
            if (mConfiguration != null) {
                mConfiguration.setLongTone(newValue);
            }
        });
        getZelloAlertEnabledCheckBox().selectedProperty().addListener((observable, oldValue, newValue) -> {
            getZelloChannelField().setDisable(!newValue);
            getZelloAlertTextField().setDisable(!newValue);
            if (mConfiguration != null) {
                mConfiguration.setZelloAlertEnabled(newValue);
            }
        });
        getLabelField().textProperty().addListener((observable, oldValue, newValue) -> {
            if (mConfiguration != null) mConfiguration.setLabel(newValue);
        });
        getZelloChannelField().textProperty().addListener((observable, oldValue, newValue) -> {
            if (mConfiguration != null) mConfiguration.setZelloChannel(newValue);
        });
        getZelloAlertTextField().textProperty().addListener((observable, oldValue, newValue) -> {
            if (mConfiguration != null) mConfiguration.setZelloAlertText(newValue);
        });
    }

    private TextField getLabelField() {
        if (mLabelField == null) {
            mLabelField = new TextField();
        }
        return mLabelField;
    }

    private TextField getToneAHzField() {
        if (mToneAHzField == null) {
            mToneAHzField = new TextField();
            TextFormatter<Double> formatter = new TextFormatter<>(new DoubleStringConverter());
            formatter.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (mConfiguration != null && newVal != null) mConfiguration.setToneAFrequency(newVal);
            });
            mToneAHzField.setTextFormatter(formatter);
        }
        return mToneAHzField;
    }

    private TextField getToneADurationMsField() {
        if (mToneADurationMsField == null) {
            mToneADurationMsField = new TextField();
            TextFormatter<Integer> formatter = new TextFormatter<>(new IntegerStringConverter());
            formatter.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (mConfiguration != null && newVal != null) mConfiguration.setToneADurationMs(newVal);
            });
            mToneADurationMsField.setTextFormatter(formatter);
        }
        return mToneADurationMsField;
    }

    private CheckBox getLongToneCheckBox() {
        if (mLongToneCheckBox == null) {
            mLongToneCheckBox = new CheckBox();
        }
        return mLongToneCheckBox;
    }

    private TextField getToneBHzField() {
        if (mToneBHzField == null) {
            mToneBHzField = new TextField();
            TextFormatter<Double> formatter = new TextFormatter<>(new DoubleStringConverter());
            formatter.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (mConfiguration != null && newVal != null) mConfiguration.setToneBFrequency(newVal);
            });
            mToneBHzField.setTextFormatter(formatter);
        }
        return mToneBHzField;
    }

    private TextField getToneBDurationMsField() {
        if (mToneBDurationMsField == null) {
            mToneBDurationMsField = new TextField();
            TextFormatter<Integer> formatter = new TextFormatter<>(new IntegerStringConverter());
            formatter.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (mConfiguration != null && newVal != null) mConfiguration.setToneBDurationMs(newVal);
            });
            mToneBDurationMsField.setTextFormatter(formatter);
        }
        return mToneBDurationMsField;
    }

    private CheckBox getZelloAlertEnabledCheckBox() {
        if (mZelloAlertEnabledCheckBox == null) {
            mZelloAlertEnabledCheckBox = new CheckBox();
        }
        return mZelloAlertEnabledCheckBox;
    }

    private TextField getZelloChannelField() {
        if (mZelloChannelField == null) {
            mZelloChannelField = new TextField();
        }
        return mZelloChannelField;
    }

    private TextField getZelloAlertTextField() {
        if (mZelloAlertTextField == null) {
            mZelloAlertTextField = new TextField();
        }
        return mZelloAlertTextField;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void save() {
    }

    @Override
    public void revert() {
        setEditorValue(mConfiguration);
    }

    @Override
    public void setEditorValue(TwoToneDetectorConfiguration value) {
        mConfiguration = null;
        if (value != null) {
            getLabelField().setText(value.getLabel());
            getToneAHzField().setText(String.valueOf(value.getToneAFrequency()));
            getToneADurationMsField().setText(String.valueOf(value.getToneADurationMs()));
            getLongToneCheckBox().setSelected(value.isLongTone());
            getToneBHzField().setText(String.valueOf(value.getToneBFrequency()));
            getToneBDurationMsField().setText(String.valueOf(value.getToneBDurationMs()));
            getZelloAlertEnabledCheckBox().setSelected(value.isZelloAlertEnabled());
            getZelloChannelField().setText(value.getZelloChannel());
            getZelloAlertTextField().setText(value.getZelloAlertText());

            getToneBHzField().setDisable(value.isLongTone());
            getToneBDurationMsField().setDisable(value.isLongTone());
            getZelloChannelField().setDisable(!value.isZelloAlertEnabled());
            getZelloAlertTextField().setDisable(!value.isZelloAlertEnabled());
        } else {
            getLabelField().clear();
            getToneAHzField().clear();
            getToneADurationMsField().clear();
            getLongToneCheckBox().setSelected(false);
            getToneBHzField().clear();
            getToneBDurationMsField().clear();
            getZelloAlertEnabledCheckBox().setSelected(false);
            getZelloChannelField().clear();
            getZelloAlertTextField().clear();
        }
        mConfiguration = value;
    }
}
