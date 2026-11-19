/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

import io.github.dsheirer.dsp.filter.equalizer.GraphicEqualizer;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.controlsfx.control.ToggleSwitch;

/**
 * AP-fork (ap-11): editor for the 20-band graphic equalizer, shared by the P25, EDACS and Type II channel editors.
 * One vertical slider per band, as on a hardware graphic equalizer, with the band's gain above it and its center
 * frequency below.  Double-click a slider to set that band back to 0 dB.
 */
public class GraphicEqualizerEditor extends VBox
{
    private static final double SLIDER_HEIGHT = 170;
    private static final double COLUMN_WIDTH = 38;

    private final ToggleSwitch mEnabledSwitch = new ToggleSwitch("Enable Graphic Equalizer");
    private final Slider[] mSliders = new Slider[GraphicEqualizer.BAND_COUNT];
    private final Label[] mValueLabels = new Label[GraphicEqualizer.BAND_COUNT];
    private final Button mFlatButton = new Button("Flat");
    private Runnable mModifiedListener;
    private boolean mLoading;

    /**
     * Constructs an instance
     * @param tooltip for the enable switch, saying what audio the equalizer applies to
     */
    public GraphicEqualizerEditor(String tooltip)
    {
        super(10);
        setPadding(new Insets(10));

        mEnabledSwitch.setTooltip(new Tooltip(tooltip));
        mEnabledSwitch.selectedProperty().addListener((obs, old, enabled) -> {
            updateDisabled();
            modified();
        });

        mFlatButton.setTooltip(new Tooltip("Set every band to 0 dB"));
        mFlatButton.setOnAction(event -> {
            for(Slider slider : mSliders)
            {
                slider.setValue(0);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, mEnabledSwitch, spacer, mFlatButton);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox bands = new HBox(2);
        bands.setAlignment(Pos.BOTTOM_LEFT);
        bands.getChildren().add(createScale());

        for(int i = 0; i < GraphicEqualizer.BAND_COUNT; i++)
        {
            bands.getChildren().add(createBand(i));
        }

        getChildren().addAll(top, bands);
        updateDisabled();
    }

    /**
     * Sets a listener called when the user changes the settings
     */
    public void setOnModified(Runnable listener)
    {
        mModifiedListener = listener;
    }

    /**
     * Shows the settings.  Gains for the earlier 10-band equalizer are converted.
     * @param enabled state
     * @param gains in dB, or null for flat
     */
    public void setSettings(boolean enabled, double[] gains)
    {
        mLoading = true;

        try
        {
            mEnabledSwitch.setSelected(enabled);
            double[] normalized = GraphicEqualizer.normalizeGains(gains);

            for(int i = 0; i < GraphicEqualizer.BAND_COUNT; i++)
            {
                mSliders[i].setValue(normalized[i]);
                updateValueLabel(i);
            }

            updateDisabled();
        }
        finally
        {
            mLoading = false;
        }
    }

    /**
     * Indicates if the equalizer is switched on
     */
    public boolean isEqualizerEnabled()
    {
        return mEnabledSwitch.isSelected();
    }

    /**
     * Band gains in dB, one per band
     */
    public double[] getGains()
    {
        double[] gains = new double[GraphicEqualizer.BAND_COUNT];

        for(int i = 0; i < GraphicEqualizer.BAND_COUNT; i++)
        {
            gains[i] = mSliders[i].getValue();
        }

        return gains;
    }

    private VBox createBand(int band)
    {
        Slider slider = new Slider(GraphicEqualizer.MIN_GAIN_DB, GraphicEqualizer.MAX_GAIN_DB, 0);
        slider.setOrientation(Orientation.VERTICAL);
        slider.setMajorTickUnit(6);
        slider.setMinorTickCount(1);
        slider.setShowTickMarks(true);
        slider.setSnapToTicks(false);
        slider.setBlockIncrement(0.5);
        slider.setPrefHeight(SLIDER_HEIGHT);
        slider.setMinHeight(SLIDER_HEIGHT);
        slider.setTooltip(new Tooltip(GraphicEqualizer.BAND_LABELS[band] +
            " band gain (-12 to +12 dB).  Double-click for 0 dB."));
        slider.valueProperty().addListener((obs, old, value) -> {
            //Half dB steps, so a dragged value reads cleanly
            double rounded = Math.round(value.doubleValue() * 2.0) / 2.0;

            if(rounded != value.doubleValue())
            {
                slider.setValue(rounded);
                return;
            }

            updateValueLabel(band);
            modified();
        });
        slider.setOnMouseClicked(event -> {
            if(event.getClickCount() == 2 && !slider.isDisabled())
            {
                slider.setValue(0);
            }
        });
        mSliders[band] = slider;

        Label value = new Label();
        value.setStyle("-fx-font-size: 0.85em;");
        mValueLabels[band] = value;
        updateValueLabel(band);

        Label frequency = new Label(GraphicEqualizer.BAND_SHORT_LABELS[band]);
        frequency.setStyle("-fx-font-size: 0.85em;");

        VBox column = new VBox(4, value, slider, frequency);
        column.setAlignment(Pos.CENTER);
        column.setPrefWidth(COLUMN_WIDTH);
        column.setMinWidth(COLUMN_WIDTH);
        return column;
    }

    /**
     * dB scale to the left of the sliders
     */
    private VBox createScale()
    {
        VBox labels = new VBox();
        labels.setPrefHeight(SLIDER_HEIGHT);
        labels.setMinHeight(SLIDER_HEIGHT);
        String[] marks = {"+12 dB", "+6", "0", "-6", "-12 dB"};

        for(int i = 0; i < marks.length; i++)
        {
            Label label = new Label(marks[i]);
            label.setStyle("-fx-font-size: 0.85em;");
            labels.getChildren().add(label);

            if(i < marks.length - 1)
            {
                Region gap = new Region();
                VBox.setVgrow(gap, Priority.ALWAYS);
                labels.getChildren().add(gap);
            }
        }

        labels.setAlignment(Pos.CENTER_RIGHT);

        //Same top and bottom rows as the band columns, so the scale lines up with the sliders
        Label top = new Label(" ");
        top.setStyle("-fx-font-size: 0.85em;");
        Label bottom = new Label(" ");
        bottom.setStyle("-fx-font-size: 0.85em;");
        VBox scale = new VBox(4, top, labels, bottom);
        scale.setAlignment(Pos.CENTER_RIGHT);
        scale.setPadding(new Insets(0, 4, 0, 0));
        return scale;
    }

    private void updateValueLabel(int band)
    {
        double value = mSliders[band].getValue();
        mValueLabels[band].setText(value == 0 ? "0" : String.format("%+.1f", value));
    }

    private void updateDisabled()
    {
        boolean disabled = !mEnabledSwitch.isSelected();

        for(Slider slider : mSliders)
        {
            if(slider != null)
            {
                slider.setDisable(disabled);
            }
        }

        mFlatButton.setDisable(disabled);
    }

    private void modified()
    {
        if(!mLoading && mModifiedListener != null)
        {
            mModifiedListener.run();
        }
    }
}
