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

import io.github.dsheirer.gui.control.HexFormatter;
import io.github.dsheirer.gui.control.IntegerFormatter;
import io.github.dsheirer.gui.playlist.decoder.AuxDecoderConfigurationEditor;
import io.github.dsheirer.gui.playlist.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.playlist.source.FrequencyEditor;
import io.github.dsheirer.gui.playlist.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.ChannelToneFilter;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.ctcss.CTCSSCode;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.identifier.IntegerFormat;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.control.ToggleSwitch;

/**
 * Narrow-Band FM channel configuration editor
 */
public class NBFMConfigurationEditor extends ChannelConfigurationEditor
{
    private TitledPane mAuxDecoderPane;
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private TextField mTalkgroupField;
    private ToggleSwitch mAudioFilterEnable;
    private TextFormatter<Integer> mTalkgroupTextFormatter;
    private ToggleSwitch mBasebandRecordSwitch;
    private SegmentedButton mBandwidthButton;

    private SourceConfigurationEditor mSourceConfigurationEditor;
    private AuxDecoderConfigurationEditor mAuxDecoderConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private final TalkgroupValueChangeListener mTalkgroupValueChangeListener = new TalkgroupValueChangeListener();
    private final IntegerFormatter mDecimalFormatter = new IntegerFormatter(1, 65535);
    private final HexFormatter mHexFormatter = new HexFormatter(1, 65535);

    // === NEW: De-emphasis ===
    private ComboBox<DecodeConfigNBFM.DeemphasisMode> mDeemphasisCombo;

    // === NEW: Tone Filter UI ===
    private TitledPane mToneFilterPane;
    private ToggleSwitch mToneFilterEnabledSwitch;
    private ComboBox<ChannelToneFilter.ToneType> mToneTypeCombo;
    private ComboBox<CTCSSCode> mCtcssCodeCombo;
    private ComboBox<DCSCode> mDcsCodeCombo;

    // === NEW: Squelch Tail Removal UI ===
    private TitledPane mSquelchTailPane;
    private ToggleSwitch mSquelchTailEnabledSwitch;
    private Spinner<Integer> mTailRemovalSpinner;
    private Spinner<Integer> mHeadRemovalSpinner;

    // === NEW: VoxSend Audio Filters UI ===
    private TitledPane mAudioFiltersPane;
    private Slider mOutputGainSlider;
    private Label mOutputGainLabel;
    private ToggleSwitch mLowPassEnabledSwitch;
    private Slider mLowPassCutoffSlider;
    private Label mLowPassCutoffLabel;
    private ToggleSwitch mVoxDeemphasisEnabledSwitch;
    private ComboBox<String> mVoxDeemphasisTimeConstantCombo;
    private ToggleSwitch mVoiceEnhanceEnabledSwitch;
    private Slider mVoiceEnhanceSlider;
    private Label mVoiceEnhanceLabel;
    private ToggleSwitch mBassBoostEnabledSwitch;
    private Slider mBassBoostSlider;
    private Label mBassBoostLabel;
    private ToggleSwitch mSquelchEnabledSwitch;
    private Slider mSquelchThresholdSlider;
    private Label mSquelchThresholdLabel;
    private Slider mSquelchReductionSlider;
    private Label mSquelchReductionLabel;
    private Slider mHoldTimeSlider;
    private Label mHoldTimeLabel;
    private boolean mLoadingAudioFilters = false;

    /**
     * Constructs an instance
     * @param playlistManager for playlists
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    public NBFMConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                   UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getToneFilterPane());
        getTitledPanesBox().getChildren().add(getSquelchTailPane());
        getTitledPanesBox().getChildren().add(getAudioFiltersPane());
        getTitledPanesBox().getChildren().add(getAuxDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    private TitledPane getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new TitledPane("Source", getSourceConfigurationEditor());
            mSourcePane.setExpanded(true);
        }

        return mSourcePane;
    }

    private TitledPane getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new TitledPane();
            mDecoderPane.setText("Decoder: NBFM");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label bandwidthLabel = new Label("Channel Bandwidth");
            GridPane.setHalignment(bandwidthLabel, HPos.RIGHT);
            GridPane.setConstraints(bandwidthLabel, 0, 0);
            gridPane.getChildren().add(bandwidthLabel);

            GridPane.setConstraints(getBandwidthButton(), 1, 0);
            gridPane.getChildren().add(getBandwidthButton());

            Label talkgroupLabel = new Label("Talkgroup To Assign");
            GridPane.setHalignment(talkgroupLabel, HPos.RIGHT);
            GridPane.setConstraints(talkgroupLabel, 0, 1);
            gridPane.getChildren().add(talkgroupLabel);

            GridPane.setConstraints(getTalkgroupField(), 1, 1);
            gridPane.getChildren().add(getTalkgroupField());

            GridPane.setConstraints(getAudioFilterEnable(), 2, 1);
            gridPane.getChildren().add(getAudioFilterEnable());



            mDecoderPane.setContent(gridPane);

            //Special handling - the pill button doesn't like to set a selected state if the pane is not expanded,
            //so detect when the pane is expanded and refresh the config view
            mDecoderPane.expandedProperty().addListener((observable, oldValue, newValue) -> {
                if(newValue)
                {
                    //Reset the config so the editor gets updated
                    setDecoderConfiguration(getItem().getDecodeConfiguration());
                }
            });
        }

        return mDecoderPane;
    }

    // === NEW: De-emphasis combo ===
    private ComboBox<DecodeConfigNBFM.DeemphasisMode> getDeemphasisCombo()
    {
        if(mDeemphasisCombo == null)
        {
            mDeemphasisCombo = new ComboBox<>();
            mDeemphasisCombo.getItems().addAll(DecodeConfigNBFM.DeemphasisMode.values());
            mDeemphasisCombo.setValue(DecodeConfigNBFM.DeemphasisMode.US_750US);
            mDeemphasisCombo.setTooltip(new Tooltip("FM de-emphasis restores flat audio from pre-emphasized FM signal"));
            mDeemphasisCombo.valueProperty().addListener((obs, ov, nv) -> modifiedProperty().set(true));
        }
        return mDeemphasisCombo;
    }

    // === NEW: Tone Filter pane ===
    private TitledPane getToneFilterPane()
    {
        if(mToneFilterPane == null)
        {
            mToneFilterPane = new TitledPane();
            mToneFilterPane.setText("Tone Filter (CTCSS / DCS)");
            mToneFilterPane.setExpanded(false);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10, 10, 10, 10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            // Enable switch
            Label enableLabel = new Label("Enable Tone Filter");
            GridPane.setHalignment(enableLabel, HPos.RIGHT);
            GridPane.setConstraints(enableLabel, 0, 0);
            gridPane.getChildren().add(enableLabel);

            mToneFilterEnabledSwitch = new ToggleSwitch();
            mToneFilterEnabledSwitch.selectedProperty()
                    .addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mToneFilterEnabledSwitch, 1, 0);
            gridPane.getChildren().add(mToneFilterEnabledSwitch);

            Label helpLabel = new Label("When enabled, audio only passes when the selected tone is detected");
            GridPane.setConstraints(helpLabel, 2, 0, 3, 1);
            gridPane.getChildren().add(helpLabel);

            // Tone type selector
            Label typeLabel = new Label("Type");
            GridPane.setHalignment(typeLabel, HPos.RIGHT);
            GridPane.setConstraints(typeLabel, 0, 1);
            gridPane.getChildren().add(typeLabel);

            mToneTypeCombo = new ComboBox<>();
            mToneTypeCombo.getItems().addAll(ChannelToneFilter.ToneType.CTCSS, ChannelToneFilter.ToneType.DCS);
            mToneTypeCombo.setValue(ChannelToneFilter.ToneType.CTCSS);
            mToneTypeCombo.valueProperty().addListener((obs, ov, nv) -> {
                updateToneCodeVisibility();
                modifiedProperty().set(true);
            });
            GridPane.setConstraints(mToneTypeCombo, 1, 1);
            gridPane.getChildren().add(mToneTypeCombo);

            // CTCSS code selector
            mCtcssCodeCombo = new ComboBox<>();
            mCtcssCodeCombo.getItems().addAll(CTCSSCode.STANDARD_CODES);
            mCtcssCodeCombo.setPromptText("Select PL tone");
            mCtcssCodeCombo.setPrefWidth(200);
            mCtcssCodeCombo.valueProperty().addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mCtcssCodeCombo, 2, 1);
            gridPane.getChildren().add(mCtcssCodeCombo);

            // DCS code selector (hidden by default)
            mDcsCodeCombo = new ComboBox<>();
            mDcsCodeCombo.getItems().addAll(DCSCode.STANDARD_CODES);
            mDcsCodeCombo.getItems().addAll(DCSCode.INVERTED_CODES);
            mDcsCodeCombo.setPromptText("Select DCS code");
            mDcsCodeCombo.setPrefWidth(200);
            mDcsCodeCombo.setVisible(false);
            mDcsCodeCombo.setManaged(false);
            mDcsCodeCombo.valueProperty().addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mDcsCodeCombo, 2, 1);
            gridPane.getChildren().add(mDcsCodeCombo);

            mToneFilterPane.setContent(gridPane);
        }
        return mToneFilterPane;
    }

    private void updateToneCodeVisibility()
    {
        boolean isCTCSS = mToneTypeCombo.getValue() == ChannelToneFilter.ToneType.CTCSS;
        mCtcssCodeCombo.setVisible(isCTCSS);
        mCtcssCodeCombo.setManaged(isCTCSS);
        mDcsCodeCombo.setVisible(!isCTCSS);
        mDcsCodeCombo.setManaged(!isCTCSS);
    }

    // === NEW: Squelch Tail Removal pane ===
    private TitledPane getSquelchTailPane()
    {
        if(mSquelchTailPane == null)
        {
            mSquelchTailPane = new TitledPane();
            mSquelchTailPane.setText("Squelch Tail Removal");
            mSquelchTailPane.setExpanded(false);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10, 10, 10, 10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label enableLabel = new Label("Enable");
            GridPane.setHalignment(enableLabel, HPos.RIGHT);
            GridPane.setConstraints(enableLabel, 0, 0);
            gridPane.getChildren().add(enableLabel);

            mSquelchTailEnabledSwitch = new ToggleSwitch();
            mSquelchTailEnabledSwitch.selectedProperty()
                    .addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mSquelchTailEnabledSwitch, 1, 0);
            gridPane.getChildren().add(mSquelchTailEnabledSwitch);

            Label tailLabel = new Label("Tail Trim (ms)");
            GridPane.setHalignment(tailLabel, HPos.RIGHT);
            GridPane.setConstraints(tailLabel, 0, 1);
            gridPane.getChildren().add(tailLabel);

            mTailRemovalSpinner = new Spinner<>(0, 300, 100, 10);
            mTailRemovalSpinner.setEditable(true);
            mTailRemovalSpinner.setPrefWidth(100);
            mTailRemovalSpinner.setTooltip(new Tooltip("Milliseconds to trim from end of transmission (removes noise burst)"));
            mTailRemovalSpinner.getValueFactory().valueProperty()
                    .addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mTailRemovalSpinner, 1, 1);
            gridPane.getChildren().add(mTailRemovalSpinner);

            Label headLabel = new Label("Head Trim (ms)");
            GridPane.setHalignment(headLabel, HPos.RIGHT);
            GridPane.setConstraints(headLabel, 2, 1);
            gridPane.getChildren().add(headLabel);

            mHeadRemovalSpinner = new Spinner<>(0, 150, 0, 10);
            mHeadRemovalSpinner.setEditable(true);
            mHeadRemovalSpinner.setPrefWidth(100);
            mHeadRemovalSpinner.setTooltip(new Tooltip("Milliseconds to trim from start of transmission (removes tone ramp-up)"));
            mHeadRemovalSpinner.getValueFactory().valueProperty()
                    .addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mHeadRemovalSpinner, 3, 1);
            gridPane.getChildren().add(mHeadRemovalSpinner);

            mSquelchTailPane.setContent(gridPane);
        }
        return mSquelchTailPane;
    }

    // === NEW: VoxSend Audio Filters Pane ===
    private TitledPane getAudioFiltersPane()
    {
        if(mAudioFiltersPane == null)
        {
            mAudioFiltersPane = new TitledPane();
            mAudioFiltersPane.setText("Audio Filters (VoxSend Chain)");
            mAudioFiltersPane.setExpanded(false);

            VBox contentBox = new VBox(10);
            contentBox.setPadding(new Insets(10,10,10,10));

            // 1. Low-pass filter
            contentBox.getChildren().add(createLowPassSection());
            contentBox.getChildren().add(new Separator());

            // 2. Bass Boost
            contentBox.getChildren().add(createBassBoostSection());
            contentBox.getChildren().add(new Separator());

            // 3. De-emphasis (VoxSend chain version)
            contentBox.getChildren().add(createVoxDeemphasisSection());
            contentBox.getChildren().add(new Separator());

            // 4. Voice Enhancement
            contentBox.getChildren().add(createVoiceEnhanceSection());
            contentBox.getChildren().add(new Separator());

            // 5. Squelch / Noise Gate
            contentBox.getChildren().add(createNoiseGateSection());
            contentBox.getChildren().add(new Separator());

            // 6. Output Gain (applied last)
            contentBox.getChildren().add(createOutputGainSection());

            mAudioFiltersPane.setContent(contentBox);
        }
        return mAudioFiltersPane;
    }

    private VBox createLowPassSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("1. Low-Pass Filter");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mLowPassEnabledSwitch = new ToggleSwitch("Enable Low-Pass Filter");
        mLowPassEnabledSwitch.setTooltip(new Tooltip("Remove high-frequency hiss/noise"));
        mLowPassEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                modifiedProperty().set(true);
                mLowPassCutoffSlider.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label cutoffLabel = new Label("Cutoff:");
        GridPane.setConstraints(cutoffLabel, 0, 0);
        controlsPane.getChildren().add(cutoffLabel);

        mLowPassCutoffSlider = new Slider(2500, 4000, 3400);
        mLowPassCutoffSlider.setMajorTickUnit(500);
        mLowPassCutoffSlider.setMinorTickCount(4);
        mLowPassCutoffSlider.setShowTickMarks(true);
        mLowPassCutoffSlider.setShowTickLabels(true);
        mLowPassCutoffSlider.setPrefWidth(300);
        mLowPassCutoffSlider.setTooltip(new Tooltip("Higher = brighter\nLower = less noise\nDefault: 3400 Hz"));
        mLowPassCutoffSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mLowPassCutoffLabel.setText(val.intValue() + " Hz");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mLowPassCutoffSlider, 1, 0);
        controlsPane.getChildren().add(mLowPassCutoffSlider);

        mLowPassCutoffLabel = new Label("3400 Hz");
        GridPane.setConstraints(mLowPassCutoffLabel, 2, 0);
        controlsPane.getChildren().add(mLowPassCutoffLabel);

        section.getChildren().addAll(title, mLowPassEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createBassBoostSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("2. Bass Boost");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mBassBoostEnabledSwitch = new ToggleSwitch("Enable Bass Boost");
        mBassBoostEnabledSwitch.setTooltip(new Tooltip("Boost low frequencies below 400 Hz for warmth"));
        mBassBoostEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                modifiedProperty().set(true);
                mBassBoostSlider.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label amountLabel = new Label("Boost Amount:");
        GridPane.setConstraints(amountLabel, 0, 0);
        controlsPane.getChildren().add(amountLabel);

        mBassBoostSlider = new Slider(0, 12, 0);
        mBassBoostSlider.setMajorTickUnit(3);
        mBassBoostSlider.setMinorTickCount(2);
        mBassBoostSlider.setShowTickMarks(true);
        mBassBoostSlider.setShowTickLabels(true);
        mBassBoostSlider.setPrefWidth(300);
        mBassBoostSlider.setTooltip(new Tooltip("Low-shelf boost below 400 Hz\n0 dB = off, +12 dB = max bass\nDefault: 0 dB"));
        mBassBoostSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mBassBoostLabel.setText(String.format("+%.1f dB", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mBassBoostSlider, 1, 0);
        controlsPane.getChildren().add(mBassBoostSlider);

        mBassBoostLabel = new Label("+0.0 dB");
        GridPane.setConstraints(mBassBoostLabel, 2, 0);
        controlsPane.getChildren().add(mBassBoostLabel);

        section.getChildren().addAll(title, mBassBoostEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createVoxDeemphasisSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("3. FM De-emphasis (VoxSend)");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mVoxDeemphasisEnabledSwitch = new ToggleSwitch("Enable De-emphasis");
        mVoxDeemphasisEnabledSwitch.setTooltip(new Tooltip("Correct FM pre-emphasis from transmitter\n(This is the VoxSend chain de-emphasis filter)"));
        mVoxDeemphasisEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                modifiedProperty().set(true);
                mVoxDeemphasisTimeConstantCombo.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label tcLabel = new Label("Time Constant:");
        GridPane.setConstraints(tcLabel, 0, 0);
        controlsPane.getChildren().add(tcLabel);

        mVoxDeemphasisTimeConstantCombo = new ComboBox<>();
        mVoxDeemphasisTimeConstantCombo.getItems().addAll("75 \u00B5s (North America)", "50 \u00B5s (Europe)");
        mVoxDeemphasisTimeConstantCombo.setTooltip(new Tooltip("75\u00B5s for North America, 50\u00B5s for Europe"));
        mVoxDeemphasisTimeConstantCombo.setOnAction(e -> {
            if(!mLoadingAudioFilters) modifiedProperty().set(true);
        });
        GridPane.setConstraints(mVoxDeemphasisTimeConstantCombo, 1, 0);
        controlsPane.getChildren().add(mVoxDeemphasisTimeConstantCombo);

        section.getChildren().addAll(title, mVoxDeemphasisEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createVoiceEnhanceSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("4. Voice Enhancement");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mVoiceEnhanceEnabledSwitch = new ToggleSwitch("Enable Voice Enhancement");
        mVoiceEnhanceEnabledSwitch.setTooltip(new Tooltip("Boost speech clarity (2-4 kHz presence)"));
        mVoiceEnhanceEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                modifiedProperty().set(true);
                mVoiceEnhanceSlider.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label amountLabel = new Label("Amount:");
        GridPane.setConstraints(amountLabel, 0, 0);
        controlsPane.getChildren().add(amountLabel);

        mVoiceEnhanceSlider = new Slider(0, 100, 30);
        mVoiceEnhanceSlider.setMajorTickUnit(25);
        mVoiceEnhanceSlider.setMinorTickCount(4);
        mVoiceEnhanceSlider.setShowTickMarks(true);
        mVoiceEnhanceSlider.setShowTickLabels(true);
        mVoiceEnhanceSlider.setPrefWidth(300);
        mVoiceEnhanceSlider.setTooltip(new Tooltip("Boost speech presence\n0% = off, 100% = max clarity\nDefault: 30%"));
        mVoiceEnhanceSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mVoiceEnhanceLabel.setText(val.intValue() + "%");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mVoiceEnhanceSlider, 1, 0);
        controlsPane.getChildren().add(mVoiceEnhanceSlider);

        mVoiceEnhanceLabel = new Label("30%");
        GridPane.setConstraints(mVoiceEnhanceLabel, 2, 0);
        controlsPane.getChildren().add(mVoiceEnhanceLabel);

        section.getChildren().addAll(title, mVoiceEnhanceEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createNoiseGateSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("5. Squelch / Noise Gate");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mSquelchEnabledSwitch = new ToggleSwitch("Enable Squelch/Noise Gate");
        mSquelchEnabledSwitch.setTooltip(new Tooltip("Silence carrier/static between voice"));
        mSquelchEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                modifiedProperty().set(true);
                mSquelchThresholdSlider.setDisable(!val);
                mSquelchReductionSlider.setDisable(!val);
                mHoldTimeSlider.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        // Threshold: 0-100%
        Label threshLabel = new Label("Threshold:");
        GridPane.setConstraints(threshLabel, 0, 0);
        controlsPane.getChildren().add(threshLabel);

        mSquelchThresholdSlider = new Slider(0, 100, 4.0);
        mSquelchThresholdSlider.setMajorTickUnit(25);
        mSquelchThresholdSlider.setMinorTickCount(4);
        mSquelchThresholdSlider.setShowTickMarks(true);
        mSquelchThresholdSlider.setShowTickLabels(true);
        mSquelchThresholdSlider.setPrefWidth(300);
        mSquelchThresholdSlider.setTooltip(new Tooltip("Gate opens when level > threshold\nLower = more sensitive\nDefault: 4%"));
        mSquelchThresholdSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mSquelchThresholdLabel.setText(String.format("%.1f%%", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mSquelchThresholdSlider, 1, 0);
        controlsPane.getChildren().add(mSquelchThresholdSlider);

        mSquelchThresholdLabel = new Label("4.0%");
        GridPane.setConstraints(mSquelchThresholdLabel, 2, 0);
        controlsPane.getChildren().add(mSquelchThresholdLabel);

        // Reduction: 0-100%
        Label reductionLabel = new Label("Reduction:");
        GridPane.setConstraints(reductionLabel, 0, 1);
        controlsPane.getChildren().add(reductionLabel);

        mSquelchReductionSlider = new Slider(0, 100, 80);
        mSquelchReductionSlider.setMajorTickUnit(25);
        mSquelchReductionSlider.setMinorTickCount(4);
        mSquelchReductionSlider.setShowTickMarks(true);
        mSquelchReductionSlider.setShowTickLabels(true);
        mSquelchReductionSlider.setPrefWidth(300);
        mSquelchReductionSlider.setTooltip(new Tooltip("How much to reduce carrier noise\n0% = no reduction, 100% = full mute\nDefault: 80%"));
        mSquelchReductionSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mSquelchReductionLabel.setText(val.intValue() + "%");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mSquelchReductionSlider, 1, 1);
        controlsPane.getChildren().add(mSquelchReductionSlider);

        mSquelchReductionLabel = new Label("80%");
        GridPane.setConstraints(mSquelchReductionLabel, 2, 1);
        controlsPane.getChildren().add(mSquelchReductionLabel);

        // Hold Time: 0-1000ms
        Label holdLabel = new Label("Delay (Hold Time):");
        GridPane.setConstraints(holdLabel, 0, 2);
        controlsPane.getChildren().add(holdLabel);

        mHoldTimeSlider = new Slider(0, 1000, 500);
        mHoldTimeSlider.setMajorTickUnit(250);
        mHoldTimeSlider.setMinorTickCount(4);
        mHoldTimeSlider.setShowTickMarks(true);
        mHoldTimeSlider.setShowTickLabels(true);
        mHoldTimeSlider.setPrefWidth(300);
        mHoldTimeSlider.setTooltip(new Tooltip("Keep gate open after voice stops\nPrevents word chopping\nDefault: 500ms"));
        mHoldTimeSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mHoldTimeLabel.setText(val.intValue() + " ms");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mHoldTimeSlider, 1, 2);
        controlsPane.getChildren().add(mHoldTimeSlider);

        mHoldTimeLabel = new Label("500 ms");
        GridPane.setConstraints(mHoldTimeLabel, 2, 2);
        controlsPane.getChildren().add(mHoldTimeLabel);

        section.getChildren().addAll(title, mSquelchEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createOutputGainSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("6. Output Gain (Applied Last)");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label gainLabel = new Label("Gain:");
        GridPane.setConstraints(gainLabel, 0, 0);
        controlsPane.getChildren().add(gainLabel);

        mOutputGainSlider = new Slider(0.1, 5.0, 1.0);
        mOutputGainSlider.setMajorTickUnit(1.0);
        mOutputGainSlider.setMinorTickCount(4);
        mOutputGainSlider.setShowTickMarks(true);
        mOutputGainSlider.setShowTickLabels(true);
        mOutputGainSlider.setPrefWidth(300);
        mOutputGainSlider.setTooltip(new Tooltip("Amplify final output\n1.0 = unity, 2.0 = +6dB"));
        mOutputGainSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingAudioFilters)
            {
                mOutputGainLabel.setText(String.format("%.1fx (%.1f dB)", val.floatValue(),
                    20.0 * Math.log10(val.doubleValue())));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mOutputGainSlider, 1, 0);
        controlsPane.getChildren().add(mOutputGainSlider);

        mOutputGainLabel = new Label("1.0x (0.0 dB)");
        GridPane.setConstraints(mOutputGainLabel, 2, 0);
        controlsPane.getChildren().add(mOutputGainLabel);

        section.getChildren().addAll(title, controlsPane);
        return section;
    }

    private void loadAudioFilterConfiguration(DecodeConfigNBFM config)
    {
        mLoadingAudioFilters = true;

        // Low-pass
        mLowPassEnabledSwitch.setSelected(config.isLowPassEnabled());
        mLowPassCutoffSlider.setValue(config.getLowPassCutoff());
        mLowPassCutoffLabel.setText((int)config.getLowPassCutoff() + " Hz");
        mLowPassCutoffSlider.setDisable(!config.isLowPassEnabled());

        // Bass Boost
        mBassBoostEnabledSwitch.setSelected(config.isBassBoostEnabled());
        mBassBoostSlider.setValue(config.getBassBoostDb());
        mBassBoostLabel.setText(String.format("+%.1f dB", config.getBassBoostDb()));
        mBassBoostSlider.setDisable(!config.isBassBoostEnabled());

        // VoxSend De-emphasis
        // Map from master's DeemphasisMode to VoxSend combo
        DecodeConfigNBFM.DeemphasisMode deemph = config.getDeemphasis();
        boolean deemphEnabled = (deemph != DecodeConfigNBFM.DeemphasisMode.NONE);
        mVoxDeemphasisEnabledSwitch.setSelected(deemphEnabled);
        if(deemph == DecodeConfigNBFM.DeemphasisMode.CEPT_530US)
        {
            mVoxDeemphasisTimeConstantCombo.setValue("50 \u00B5s (Europe)");
        }
        else
        {
            mVoxDeemphasisTimeConstantCombo.setValue("75 \u00B5s (North America)");
        }
        mVoxDeemphasisTimeConstantCombo.setDisable(!deemphEnabled);

        // Voice Enhancement
        mVoiceEnhanceEnabledSwitch.setSelected(config.isVoiceEnhanceEnabled());
        mVoiceEnhanceSlider.setValue(config.getVoiceEnhanceAmount());
        mVoiceEnhanceLabel.setText((int)config.getVoiceEnhanceAmount() + "%");
        mVoiceEnhanceSlider.setDisable(!config.isVoiceEnhanceEnabled());

        // Noise Gate
        mSquelchEnabledSwitch.setSelected(config.isNoiseGateEnabled());
        mSquelchThresholdSlider.setValue(config.getNoiseGateThreshold());
        mSquelchThresholdLabel.setText(String.format("%.1f%%", config.getNoiseGateThreshold()));
        mSquelchReductionSlider.setValue(config.getNoiseGateReduction() * 100.0f);
        mSquelchReductionLabel.setText((int)(config.getNoiseGateReduction() * 100.0f) + "%");
        mHoldTimeSlider.setValue(config.getNoiseGateHoldTime());
        mHoldTimeLabel.setText(config.getNoiseGateHoldTime() + " ms");
        mSquelchThresholdSlider.setDisable(!config.isNoiseGateEnabled());
        mSquelchReductionSlider.setDisable(!config.isNoiseGateEnabled());
        mHoldTimeSlider.setDisable(!config.isNoiseGateEnabled());

        // Output Gain
        mOutputGainSlider.setValue(config.getOutputGain());
        mOutputGainLabel.setText(String.format("%.1fx (%.1f dB)", config.getOutputGain(),
            20.0 * Math.log10(config.getOutputGain())));

        mLoadingAudioFilters = false;
    }

    private void saveAudioFilterConfiguration(DecodeConfigNBFM config)
    {
        // Low-pass
        config.setLowPassEnabled(mLowPassEnabledSwitch.isSelected());
        config.setLowPassCutoff(mLowPassCutoffSlider.getValue());

        // Bass Boost
        config.setBassBoostEnabled(mBassBoostEnabledSwitch.isSelected());
        config.setBassBoostDb((float)mBassBoostSlider.getValue());

        // Voice Enhancement
        config.setVoiceEnhanceEnabled(mVoiceEnhanceEnabledSwitch.isSelected());
        config.setVoiceEnhanceAmount((float)mVoiceEnhanceSlider.getValue());

        // Noise Gate
        config.setNoiseGateEnabled(mSquelchEnabledSwitch.isSelected());
        config.setNoiseGateThreshold((float)mSquelchThresholdSlider.getValue());
        config.setNoiseGateReduction((float)mSquelchReductionSlider.getValue() / 100.0f);
        config.setNoiseGateHoldTime((int)mHoldTimeSlider.getValue());

        // Output Gain
        config.setOutputGain((float)mOutputGainSlider.getValue());
    }

    private void disableAudioFilterControls()
    {
        mLoadingAudioFilters = true;
        mOutputGainSlider.setValue(1.0);
        mLowPassEnabledSwitch.setSelected(false);
        mLowPassCutoffSlider.setDisable(true);
        mBassBoostEnabledSwitch.setSelected(false);
        mBassBoostSlider.setDisable(true);
        mVoxDeemphasisEnabledSwitch.setSelected(false);
        mVoxDeemphasisTimeConstantCombo.setDisable(true);
        mVoiceEnhanceEnabledSwitch.setSelected(false);
        mVoiceEnhanceSlider.setDisable(true);
        mSquelchEnabledSwitch.setSelected(false);
        mSquelchThresholdSlider.setDisable(true);
        mSquelchReductionSlider.setDisable(true);
        mHoldTimeSlider.setDisable(true);
        mLoadingAudioFilters = false;
    }

    private TitledPane getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new TitledPane("Logging", getEventLogConfigurationEditor());
            mEventLogPane.setExpanded(false);
        }

        return mEventLogPane;
    }

    private TitledPane getAuxDecoderPane()
    {
        if(mAuxDecoderPane == null)
        {
            mAuxDecoderPane = new TitledPane("Additional Decoders", getAuxDecoderConfigurationEditor());
            mAuxDecoderPane.setExpanded(false);
        }

        return mAuxDecoderPane;
    }

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            GridPane.setConstraints(getBasebandRecordSwitch(), 0, 0);
            gridPane.getChildren().add(getBasebandRecordSwitch());

            Label recordBasebandLabel = new Label("Channel (Baseband I&Q)");
            GridPane.setHalignment(recordBasebandLabel, HPos.LEFT);
            GridPane.setConstraints(recordBasebandLabel, 1, 0);
            gridPane.getChildren().add(recordBasebandLabel);

            mRecordPane.setContent(gridPane);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager);

            //Add a listener so that we can push change notifications up to this editor
            mSourceConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mSourceConfigurationEditor;
    }

    private EventLogConfigurationEditor getEventLogConfigurationEditor()
    {
        if(mEventLogConfigurationEditor == null)
        {
            List<EventLogType> types = new ArrayList<>();
            types.add(EventLogType.CALL_EVENT);
            types.add(EventLogType.DECODED_MESSAGE);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private AuxDecoderConfigurationEditor getAuxDecoderConfigurationEditor()
    {
        if(mAuxDecoderConfigurationEditor == null)
        {
            mAuxDecoderConfigurationEditor = new AuxDecoderConfigurationEditor(DecoderType.AUX_DECODERS);
            mAuxDecoderConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mAuxDecoderConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mAuxDecoderConfigurationEditor;
    }

    /**
     * Toggle switch for enable/disable the audio filtering in the audio module.
     * @return toggle switch.
     */
    private ToggleSwitch getAudioFilterEnable()
    {
        if(mAudioFilterEnable == null)
        {
            mAudioFilterEnable = new ToggleSwitch("High-Pass Audio Filter");
            mAudioFilterEnable.setTooltip(new Tooltip("High-pass filter to remove DC offset and sub-audible signalling"));
            mAudioFilterEnable.selectedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mAudioFilterEnable;
    }

    private SegmentedButton getBandwidthButton()
    {
        if(mBandwidthButton == null)
        {
            mBandwidthButton = new SegmentedButton();
            mBandwidthButton.getStyleClass().add(SegmentedButton.STYLE_CLASS_DARK);
            mBandwidthButton.setDisable(true);

            for(DecodeConfigNBFM.Bandwidth bandwidth : DecodeConfigNBFM.Bandwidth.FM_BANDWIDTHS)
            {
                ToggleButton toggleButton = new ToggleButton(bandwidth.toString());
                toggleButton.setUserData(bandwidth);
                mBandwidthButton.getButtons().add(toggleButton);
            }

            mBandwidthButton.getToggleGroup().selectedToggleProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));

            //Note: there is a weird timing bug with the segmented button where the toggles are not added to
            //the toggle group until well after the control is rendered.  We attempt to setItem() on the
            //decode configuration and we're unable to correctly set the bandwidth setting.  As a work
            //around, we'll listen for the toggles to be added and update them here.  This normally only
            //happens when we first instantiate the editor and load an item for editing the first time.
            mBandwidthButton.getToggleGroup().getToggles().addListener((ListChangeListener<Toggle>)c ->
            {
                //This change event happens when the toggles are added -- we don't need to inspect the change event
                if(getItem() != null && getItem().getDecodeConfiguration() instanceof DecodeConfigNBFM)
                {
                    //Capture current modified state so that we can reapply after adjusting control states
                    boolean modified = modifiedProperty().get();

                    DecodeConfigNBFM config = (DecodeConfigNBFM)getItem().getDecodeConfiguration();
                    DecodeConfigNBFM.Bandwidth bandwidth = config.getBandwidth();
                    if(bandwidth == null)
                    {
                        bandwidth = DecodeConfigNBFM.Bandwidth.BW_12_5;
                    }

                    for(Toggle toggle: getBandwidthButton().getToggleGroup().getToggles())
                    {
                        toggle.setSelected(toggle.getUserData() == bandwidth);
                    }

                    modifiedProperty().set(modified);
                }
            });
        }

        return mBandwidthButton;
    }

    private TextField getTalkgroupField()
    {
        if(mTalkgroupField == null)
        {
            mTalkgroupField = new TextField();
            mTalkgroupField.setTextFormatter(mTalkgroupTextFormatter);
        }

        return mTalkgroupField;
    }

    /**
     * Updates the talkgroup editor's text formatter.
     * @param value to set in the control.
     */
    private void updateTextFormatter(int value)
    {
        if(mTalkgroupTextFormatter != null)
        {
            mTalkgroupTextFormatter.valueProperty().removeListener(mTalkgroupValueChangeListener);
        }

        IntegerFormat format = mUserPreferences.getTalkgroupFormatPreference().getTalkgroupFormat(Protocol.NBFM);

        if(format == null)
        {
            format = IntegerFormat.DECIMAL;
        }

        if(format == IntegerFormat.DECIMAL)
        {
            mTalkgroupTextFormatter = mDecimalFormatter;
            getTalkgroupField().setTooltip(new Tooltip("1 - 65,535"));
        }
        else
        {
            mTalkgroupTextFormatter = mDecimalFormatter;
            getTalkgroupField().setTooltip(new Tooltip("1 - FFFF"));
        }

        mTalkgroupTextFormatter.setValue(value);

        getTalkgroupField().setTextFormatter(mTalkgroupTextFormatter);
        mTalkgroupTextFormatter.valueProperty().addListener(mTalkgroupValueChangeListener);
    }

    /**
     * Change listener to detect when talkgroup value has changed and set modified property to true.
     */
    public class TalkgroupValueChangeListener implements ChangeListener<Integer>
    {
        @Override
        public void changed(ObservableValue<? extends Integer> observable, Integer oldValue, Integer newValue)
        {
            modifiedProperty().set(true);
        }
    }


    private ToggleSwitch getBasebandRecordSwitch()
    {
        if(mBasebandRecordSwitch == null)
        {
            mBasebandRecordSwitch = new ToggleSwitch();
            mBasebandRecordSwitch.setDisable(true);
            mBasebandRecordSwitch.setTextAlignment(TextAlignment.RIGHT);
            mBasebandRecordSwitch.selectedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mBasebandRecordSwitch;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        if(config instanceof DecodeConfigNBFM)
        {
            getBandwidthButton().setDisable(false);
            DecodeConfigNBFM decodeConfigNBFM = (DecodeConfigNBFM)config;
            final DecodeConfigNBFM.Bandwidth bandwidth = (decodeConfigNBFM.getBandwidth() != null ?
                    decodeConfigNBFM.getBandwidth() : DecodeConfigNBFM.Bandwidth.BW_12_5);

            for(Toggle toggle: getBandwidthButton().getToggleGroup().getToggles())
            {
                toggle.setSelected(toggle.getUserData() == bandwidth);
            }

            updateTextFormatter(decodeConfigNBFM.getTalkgroup());
            getAudioFilterEnable().setDisable(false);
            getAudioFilterEnable().setSelected(decodeConfigNBFM.isAudioFilter());

            // === NEW: Load de-emphasis ===
            getDeemphasisCombo().setValue(decodeConfigNBFM.getDeemphasis());

            // === NEW: Load tone filter settings ===
            mToneFilterEnabledSwitch.setSelected(decodeConfigNBFM.isToneFilterEnabled());
            List<ChannelToneFilter> savedFilters = decodeConfigNBFM.getToneFilters();
            if(savedFilters != null && !savedFilters.isEmpty())
            {
                ChannelToneFilter filter = savedFilters.get(0);
                mToneTypeCombo.setValue(filter.getToneType());
                updateToneCodeVisibility();
                if(filter.getToneType() == ChannelToneFilter.ToneType.CTCSS)
                {
                    CTCSSCode code = filter.getCTCSSCode();
                    if(code != null && code != CTCSSCode.UNKNOWN)
                    {
                        mCtcssCodeCombo.setValue(code);
                    }
                }
                else if(filter.getToneType() == ChannelToneFilter.ToneType.DCS)
                {
                    DCSCode code = filter.getDCSCode();
                    if(code != null)
                    {
                        mDcsCodeCombo.setValue(code);
                    }
                }
            }
            else
            {
                mToneTypeCombo.setValue(ChannelToneFilter.ToneType.CTCSS);
                mCtcssCodeCombo.setValue(null);
                mCtcssCodeCombo.setPromptText("Select PL tone");
                mDcsCodeCombo.setValue(null);
                mDcsCodeCombo.setPromptText("Select DCS code");
                updateToneCodeVisibility();
            }

            // === NEW: Load squelch tail settings ===
            mSquelchTailEnabledSwitch.setSelected(decodeConfigNBFM.isSquelchTailRemovalEnabled());
            mTailRemovalSpinner.getValueFactory().setValue(decodeConfigNBFM.getSquelchTailRemovalMs());
            mHeadRemovalSpinner.getValueFactory().setValue(decodeConfigNBFM.getSquelchHeadRemovalMs());

            // === NEW: Load VoxSend audio filter settings ===
            loadAudioFilterConfiguration(decodeConfigNBFM);
        }
        else
        {
            getBandwidthButton().setDisable(true);

            for(Toggle toggle: getBandwidthButton().getToggleGroup().getToggles())
            {
                toggle.setSelected(false);
            }

            updateTextFormatter(0);
            getTalkgroupField().setDisable(true);
            getAudioFilterEnable().setDisable(true);
            getAudioFilterEnable().setSelected(false);

            // === NEW: Reset new controls ===
            getDeemphasisCombo().setValue(DecodeConfigNBFM.DeemphasisMode.US_750US);
            mToneFilterEnabledSwitch.setSelected(false);
            mToneTypeCombo.setValue(ChannelToneFilter.ToneType.CTCSS);
            mCtcssCodeCombo.setValue(null);
            mCtcssCodeCombo.setPromptText("Select PL tone");
            mDcsCodeCombo.setValue(null);
            mDcsCodeCombo.setPromptText("Select DCS code");
            updateToneCodeVisibility();
            mSquelchTailEnabledSwitch.setSelected(false);
            mTailRemovalSpinner.getValueFactory().setValue(100);
            mHeadRemovalSpinner.getValueFactory().setValue(0);

            // === NEW: Reset VoxSend audio filter controls ===
            disableAudioFilterControls();
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigNBFM config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigNBFM)
        {
            config = (DecodeConfigNBFM)getItem().getDecodeConfiguration();
        }
        else
        {
            config = new DecodeConfigNBFM();
        }

        DecodeConfigNBFM.Bandwidth bandwidth = DecodeConfigNBFM.Bandwidth.BW_12_5;

        if(getBandwidthButton().getToggleGroup().getSelectedToggle() != null)
        {
            bandwidth = (DecodeConfigNBFM.Bandwidth)getBandwidthButton().getToggleGroup().getSelectedToggle().getUserData();
        }

        config.setBandwidth(bandwidth);

        Integer talkgroup = mTalkgroupTextFormatter.getValue();

        if(talkgroup == null)
        {
            talkgroup = 1;
        }

        config.setTalkgroup(talkgroup);
        config.setAudioFilter(getAudioFilterEnable().isSelected());

        // Save de-emphasis from VoxSend controls
        if(mVoxDeemphasisEnabledSwitch.isSelected())
        {
            String tc = mVoxDeemphasisTimeConstantCombo.getValue();
            if(tc != null && tc.contains("50"))
            {
                config.setDeemphasis(DecodeConfigNBFM.DeemphasisMode.CEPT_530US);
            }
            else
            {
                config.setDeemphasis(DecodeConfigNBFM.DeemphasisMode.US_750US);
            }
        }
        else
        {
            config.setDeemphasis(DecodeConfigNBFM.DeemphasisMode.NONE);
        }

        // === NEW: Save tone filter settings ===
        config.setToneFilterEnabled(mToneFilterEnabledSwitch.isSelected());
        List<ChannelToneFilter> filters = new ArrayList<>();
        ChannelToneFilter.ToneType selectedType = mToneTypeCombo.getValue();
        if(selectedType == ChannelToneFilter.ToneType.CTCSS)
        {
            CTCSSCode code = mCtcssCodeCombo.getValue();
            if(code != null && code != CTCSSCode.UNKNOWN)
            {
                filters.add(new ChannelToneFilter(selectedType, code.name(), ""));
            }
        }
        else if(selectedType == ChannelToneFilter.ToneType.DCS)
        {
            DCSCode code = mDcsCodeCombo.getValue();
            if(code != null)
            {
                filters.add(new ChannelToneFilter(selectedType, code.name(), ""));
            }
        }
        config.setToneFilters(filters);

        // === NEW: Save squelch tail settings ===
        config.setSquelchTailRemovalEnabled(mSquelchTailEnabledSwitch.isSelected());
        config.setSquelchTailRemovalMs(mTailRemovalSpinner.getValue());
        config.setSquelchHeadRemovalMs(mHeadRemovalSpinner.getValue());

        // === NEW: Save VoxSend audio filter settings ===
        saveAudioFilterConfiguration(config);

        getItem().setDecodeConfiguration(config);
    }

    @Override
    protected void setEventLogConfiguration(EventLogConfiguration config)
    {
        getEventLogConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveEventLogConfiguration()
    {
        getEventLogConfigurationEditor().save();

        if(getEventLogConfigurationEditor().getItem().getLoggers().isEmpty())
        {
            getItem().setEventLogConfiguration(null);
        }
        else
        {
            getItem().setEventLogConfiguration(getEventLogConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setAuxDecoderConfiguration(AuxDecodeConfiguration config)
    {
        getAuxDecoderConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        getAuxDecoderConfigurationEditor().save();

        if(getAuxDecoderConfigurationEditor().getItem().getAuxDecoders().isEmpty())
        {
            getItem().setAuxDecodeConfiguration(null);
        }
        else
        {
            getItem().setAuxDecodeConfiguration(getAuxDecoderConfigurationEditor().getItem());
        }
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        if(config != null)
        {
            getBasebandRecordSwitch().setDisable(false);
            getBasebandRecordSwitch().selectedProperty().set(config.contains(RecorderType.BASEBAND));
        }
        else
        {
            getBasebandRecordSwitch().selectedProperty().set(false);
            getBasebandRecordSwitch().setDisable(true);
        }
    }

    @Override
    protected void saveRecordConfiguration()
    {
        RecordConfiguration config = new RecordConfiguration();

        if(getBasebandRecordSwitch().selectedProperty().get())
        {
            config.addRecorder(RecorderType.BASEBAND);
        }

        getItem().setRecordConfiguration(config);
    }

    @Override
    protected void setSourceConfiguration(SourceConfiguration config)
    {
        getSourceConfigurationEditor().setSourceConfiguration(config);
    }

    @Override
    protected void saveSourceConfiguration()
    {
        getSourceConfigurationEditor().save();
        SourceConfiguration sourceConfiguration = getSourceConfigurationEditor().getSourceConfiguration();
        getItem().setSourceConfiguration(sourceConfiguration);
    }
}
