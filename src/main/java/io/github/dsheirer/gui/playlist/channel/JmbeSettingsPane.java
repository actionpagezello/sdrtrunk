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

package io.github.dsheirer.gui.playlist.channel;

import io.github.dsheirer.module.decode.config.DecodeConfigJMBE;
import javafx.beans.binding.Bindings;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.javafx.IconNode;
import org.controlsfx.control.ToggleSwitch;

/**
 * Per-channel JMBE (AMBE/IMBE) audio settings for the DMR, NXDN and P25 channel editors.  AP-fork (ap-15.10.10): the
 * controls from upstream SDRTrunk #2484/#2511/#2530 (comfort noise volume, AGC, tone volume, legacy tone levels, each
 * slider with a value label and a reset button) as one pane shared by the four editors.  The settings take effect
 * with JMBE library version 2.0 or later; JMBE 1.x ignores them.
 */
public class JmbeSettingsPane extends TitledPane
{
    private final Runnable mModifiedListener;
    private final ToggleSwitch mAGCSwitch = new ToggleSwitch();
    private final ToggleSwitch mLegacyToneLevelsSwitch = new ToggleSwitch();
    private final Slider mNoiseGainSlider = new Slider(0.0, 1.0, DecodeConfigJMBE.DEFAULT_COMFORT_NOISE_GAIN);
    private final Slider mToneGainSlider = new Slider(0.0, 1.0, DecodeConfigJMBE.DEFAULT_TONE_GAIN);
    private boolean mLoading;

    /**
     * Constructs an instance
     * @param modifiedListener invoked when the user changes a setting
     */
    public JmbeSettingsPane(Runnable modifiedListener)
    {
        mModifiedListener = modifiedListener;
        setText("AMBE/IMBE Audio (JMBE 2.x)");
        setExpanded(false);

        mAGCSwitch.setTooltip(new Tooltip("Automatic gain control - JMBE adjusts each call's audio volume"));
        mLegacyToneLevelsSwitch.setTooltip(new Tooltip("Use the tone levels of JMBE versions before 2.0 (AMBE only)"));
        mNoiseGainSlider.setTooltip(new Tooltip("Comfort noise played in place of lost or muted voice frames: " +
                "0.0 (none) to 1.0. Default " + DecodeConfigJMBE.DEFAULT_COMFORT_NOISE_GAIN));
        mToneGainSlider.setTooltip(new Tooltip("Tone volume: 0.0 (no tones) to 1.0 (default). AMBE only"));

        mAGCSwitch.selectedProperty().addListener((o, old, n) -> modified());
        mLegacyToneLevelsSwitch.selectedProperty().addListener((o, old, n) -> modified());
        mNoiseGainSlider.valueProperty().addListener((o, old, n) -> modified());
        mToneGainSlider.valueProperty().addListener((o, old, n) -> modified());

        GridPane gridPane = new GridPane();
        gridPane.setPadding(new Insets(10));
        gridPane.setHgap(10);
        gridPane.setVgap(10);

        addLabel(gridPane, "Comfort Noise Volume:", 0, 0, mNoiseGainSlider.getTooltip());
        gridPane.add(createSliderWithReset(mNoiseGainSlider, DecodeConfigJMBE.DEFAULT_COMFORT_NOISE_GAIN), 1, 0);
        addLabel(gridPane, "AGC:", 2, 0, mAGCSwitch.getTooltip());
        gridPane.add(mAGCSwitch, 3, 0);

        addLabel(gridPane, "Tone Volume:", 0, 1, mToneGainSlider.getTooltip());
        gridPane.add(createSliderWithReset(mToneGainSlider, DecodeConfigJMBE.DEFAULT_TONE_GAIN), 1, 1);
        addLabel(gridPane, "Use Legacy Tone Levels:", 2, 1, mLegacyToneLevelsSwitch.getTooltip());
        gridPane.add(mLegacyToneLevelsSwitch, 3, 1);

        ColumnConstraints labelColumn = new ColumnConstraints();
        ColumnConstraints growColumn = new ColumnConstraints();
        growColumn.setHgrow(Priority.ALWAYS);
        gridPane.getColumnConstraints().addAll(labelColumn, growColumn);

        setContent(gridPane);
        setConfiguration(null);
    }

    private static void addLabel(GridPane gridPane, String text, int column, int row, Tooltip tooltip)
    {
        Label label = new Label(text);
        label.setTooltip(tooltip);
        GridPane.setHalignment(label, HPos.RIGHT);
        gridPane.add(label, column, row);
    }

    private void modified()
    {
        if(!mLoading && mModifiedListener != null)
        {
            mModifiedListener.run();
        }
    }

    /**
     * Loads the settings from the configuration, or disables the controls when the configuration is null.
     */
    public void setConfiguration(DecodeConfigJMBE config)
    {
        mLoading = true;
        boolean disable = config == null;
        mAGCSwitch.setDisable(disable);
        mLegacyToneLevelsSwitch.setDisable(disable);
        mNoiseGainSlider.setDisable(disable);
        mToneGainSlider.setDisable(disable);

        if(config != null)
        {
            mAGCSwitch.setSelected(config.isAGC());
            mLegacyToneLevelsSwitch.setSelected(config.isLegacyToneLevels());
            mNoiseGainSlider.setValue(config.getComfortNoiseGain());
            mToneGainSlider.setValue(config.getToneGain());
        }
        else
        {
            mAGCSwitch.setSelected(true);
            mLegacyToneLevelsSwitch.setSelected(false);
            mNoiseGainSlider.setValue(DecodeConfigJMBE.DEFAULT_COMFORT_NOISE_GAIN);
            mToneGainSlider.setValue(DecodeConfigJMBE.DEFAULT_TONE_GAIN);
        }

        mLoading = false;
    }

    /**
     * Saves the settings to the configuration.
     */
    public void save(DecodeConfigJMBE config)
    {
        if(config != null)
        {
            config.setAGC(mAGCSwitch.isSelected());
            config.setLegacyToneLevels(mLegacyToneLevelsSwitch.isSelected());
            config.setComfortNoiseGain((float)mNoiseGainSlider.getValue());
            config.setToneGain((float)mToneGainSlider.getValue());
        }
    }

    /**
     * Creates a horizontally growing box with the slider, a label showing the slider's value and a reset button
     * (upstream #2530).
     */
    private static HBox createSliderWithReset(Slider slider, double defaultValue)
    {
        IconNode resetIcon = new IconNode(FontAwesome.UNDO);
        resetIcon.setIconSize(10);

        Button resetButton = new Button();
        resetIcon.fillProperty().bind(resetButton.textFillProperty());
        resetButton.setGraphic(resetIcon);
        resetButton.setTooltip(new Tooltip("Reset to default value"));
        resetButton.disableProperty().bind(slider.disableProperty());
        resetButton.setOnAction(event -> slider.setValue(defaultValue));

        Label valueLabel = new Label();
        valueLabel.textProperty().bind(Bindings.format("%.2f", slider.valueProperty()));
        valueLabel.disableProperty().bind(slider.disableProperty());
        valueLabel.setMinWidth(Region.USE_PREF_SIZE);

        HBox box = new HBox(5, slider, valueLabel, resetButton);
        box.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider, Priority.ALWAYS);
        GridPane.setHgrow(box, Priority.ALWAYS);
        return box;
    }
}
