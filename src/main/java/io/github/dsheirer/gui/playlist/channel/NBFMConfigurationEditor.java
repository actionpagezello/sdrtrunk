/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
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
 * Narrow-Band FM channel configuration editor with VoxSend audio processing
 */
public class NBFMConfigurationEditor extends ChannelConfigurationEditor
{
    private TitledPane mAuxDecoderPane;
    private TitledPane mDecoderPane;
    private TitledPane mAudioFiltersPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private TextField mTalkgroupField;
    private ToggleSwitch mAudioFilterEnable;
    private TextFormatter<Integer> mTalkgroupTextFormatter;
    private ToggleSwitch mBasebandRecordSwitch;
    private SegmentedButton mBandwidthButton;

    // VoxSend Audio Filter Controls
    private Slider mInputGainSlider;
    private Label mInputGainLabel;
    private ToggleSwitch mLowPassEnabledSwitch;
    private Slider mLowPassCutoffSlider;
    private Label mLowPassCutoffLabel;
    private ToggleSwitch mDeemphasisEnabledSwitch;
    private ComboBox<String> mDeemphasisTimeConstantCombo;
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
    private javafx.scene.control.Button mAnalyzeButton;
    private Label mAnalyzeStatusLabel;

    private SourceConfigurationEditor mSourceConfigurationEditor;
    private AuxDecoderConfigurationEditor mAuxDecoderConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private final TalkgroupValueChangeListener mTalkgroupValueChangeListener = new TalkgroupValueChangeListener();
    private final IntegerFormatter mDecimalFormatter = new IntegerFormatter(1, 65535);
    private final HexFormatter mHexFormatter = new HexFormatter(1, 65535);

    private boolean mLoadingConfiguration = false;

    /**
     * Constructs an instance
     */
    public NBFMConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                   UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
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

            mDecoderPane.expandedProperty().addListener((observable, oldValue, newValue) -> {
                if(newValue && getItem() != null)
                {
                    setDecoderConfiguration(getItem().getDecodeConfiguration());
                }
            });
        }
        return mDecoderPane;
    }

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

            // 3. De-emphasis
            contentBox.getChildren().add(createDeemphasisSection());
            contentBox.getChildren().add(new Separator());

            // 4. Voice Enhancement
            contentBox.getChildren().add(createVoiceEnhanceSection());
            contentBox.getChildren().add(new Separator());

            // 5. Intelligent Squelch
            contentBox.getChildren().add(createSquelchSection());
            contentBox.getChildren().add(new Separator());

            // 6. Output Gain (applied last)
            contentBox.getChildren().add(createInputGainSection());

            mAudioFiltersPane.setContent(contentBox);
        }
        return mAudioFiltersPane;
    }

    private VBox createInputGainSection()
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

        mInputGainSlider = new Slider(0.1, 5.0, 1.0);
        mInputGainSlider.setMajorTickUnit(1.0);
        mInputGainSlider.setMinorTickCount(4);
        mInputGainSlider.setShowTickMarks(true);
        mInputGainSlider.setShowTickLabels(true);
        mInputGainSlider.setPrefWidth(300);
        mInputGainSlider.setTooltip(new Tooltip("Amplify weak signals before processing\n1.0 = unity, 2.0 = +6dB"));
        mInputGainSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                mInputGainLabel.setText(String.format("%.1fx (%.1f dB)", val.floatValue(), 
                    20.0 * Math.log10(val.doubleValue())));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mInputGainSlider, 1, 0);
        controlsPane.getChildren().add(mInputGainSlider);

        mInputGainLabel = new Label("1.0x (0.0 dB)");
        GridPane.setConstraints(mInputGainLabel, 2, 0);
        controlsPane.getChildren().add(mInputGainLabel);

        section.getChildren().addAll(title, controlsPane);
        return section;
    }

    private VBox createLowPassSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("1. Low-Pass Filter");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mLowPassEnabledSwitch = new ToggleSwitch("Enable Low-Pass Filter");
        mLowPassEnabledSwitch.setTooltip(new Tooltip("Remove high-frequency hiss/noise"));
        mLowPassEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
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
            if(!mLoadingConfiguration)
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

    private VBox createDeemphasisSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("3. FM De-emphasis");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mDeemphasisEnabledSwitch = new ToggleSwitch("Enable De-emphasis");
        mDeemphasisEnabledSwitch.setTooltip(new Tooltip("Correct FM pre-emphasis from transmitter"));
        mDeemphasisEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                modifiedProperty().set(true);
                mDeemphasisTimeConstantCombo.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        Label tcLabel = new Label("Time Constant:");
        GridPane.setConstraints(tcLabel, 0, 0);
        controlsPane.getChildren().add(tcLabel);

        mDeemphasisTimeConstantCombo = new ComboBox<>();
        mDeemphasisTimeConstantCombo.getItems().addAll("75 μs (North America)", "50 μs (Europe)");
        mDeemphasisTimeConstantCombo.setTooltip(new Tooltip("75μs for North America, 50μs for Europe"));
        mDeemphasisTimeConstantCombo.setOnAction(e -> {
            if(!mLoadingConfiguration) modifiedProperty().set(true);
        });
        GridPane.setConstraints(mDeemphasisTimeConstantCombo, 1, 0);
        controlsPane.getChildren().add(mDeemphasisTimeConstantCombo);

        section.getChildren().addAll(title, mDeemphasisEnabledSwitch, controlsPane);
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
            if(!mLoadingConfiguration)
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
            if(!mLoadingConfiguration)
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

    private VBox createBassBoostSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("2. Bass Boost");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mBassBoostEnabledSwitch = new ToggleSwitch("Enable Bass Boost");
        mBassBoostEnabledSwitch.setTooltip(new Tooltip("Boost low frequencies below 400 Hz for warmth"));
        mBassBoostEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
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
            if(!mLoadingConfiguration)
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

    private VBox createSquelchSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("5. Squelch / Noise Gate");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mSquelchEnabledSwitch = new ToggleSwitch("Enable Squelch/Noise Gate");
        mSquelchEnabledSwitch.setTooltip(new Tooltip("Silence carrier/static between voice"));
        mSquelchEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                modifiedProperty().set(true);
                mSquelchThresholdSlider.setDisable(!val);
                mSquelchReductionSlider.setDisable(!val);
                mHoldTimeSlider.setDisable(!val);
                mAnalyzeButton.setDisable(!val);
            }
        });

        // Analyze section (helps user find optimal threshold)
        GridPane analyzePane = new GridPane();
        analyzePane.setHgap(10);
        analyzePane.setVgap(5);
        analyzePane.setPadding(new Insets(5,0,10,0));

        mAnalyzeButton = new javafx.scene.control.Button("Analyze Audio & Suggest Settings");
        mAnalyzeButton.setTooltip(new Tooltip("Listen to audio for 5-10 seconds and suggest optimal threshold\nMake sure transmissions are active!"));
        mAnalyzeButton.setStyle("-fx-font-weight: bold;");
        mAnalyzeButton.setOnAction(e -> handleAnalyzeClick());
        GridPane.setConstraints(mAnalyzeButton, 0, 0);
        analyzePane.getChildren().add(mAnalyzeButton);

        mAnalyzeStatusLabel = new Label("Click 'Analyze' while transmissions are active");
        mAnalyzeStatusLabel.setStyle("-fx-text-fill: #666;");
        GridPane.setConstraints(mAnalyzeStatusLabel, 1, 0);
        analyzePane.getChildren().add(mAnalyzeStatusLabel);

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
            if(!mLoadingConfiguration)
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
            if(!mLoadingConfiguration)
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

        // Hold Time (Delay): 0-1000ms
        Label holdLabel = new Label("Delay (Hold Time):");
        GridPane.setConstraints(holdLabel, 0, 2);
        controlsPane.getChildren().add(holdLabel);

        mHoldTimeSlider = new Slider(0, 1000, 500);
        mHoldTimeSlider.setMajorTickUnit(250);
        mHoldTimeSlider.setMinorTickCount(4);
        mHoldTimeSlider.setShowTickMarks(true);
        mHoldTimeSlider.setShowTickLabels(true);
        mHoldTimeSlider.setPrefWidth(300);
        mHoldTimeSlider.setTooltip(new Tooltip("Keep gate open after voice stops\nSilences carrier/static between voice\nDefault: 500ms"));
        mHoldTimeSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
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

        section.getChildren().addAll(title, mSquelchEnabledSwitch, analyzePane, controlsPane);
        return section;
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

            mBandwidthButton.getToggleGroup().getToggles().addListener((ListChangeListener<Toggle>)c ->
            {
                if(getItem() != null && getItem().getDecodeConfiguration() instanceof DecodeConfigNBFM)
                {
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
        mLoadingConfiguration = true;

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

            // Load VoxSend audio filter settings
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

            disableAudioFilterControls();
        }

        mLoadingConfiguration = false;
    }

    private void loadAudioFilterConfiguration(DecodeConfigNBFM config)
    {
        // Input Gain (map from AGC max gain)
        float inputGain = (float)Math.pow(10.0, config.getAgcMaxGain() / 40.0);
        mInputGainSlider.setValue(inputGain);
        mInputGainLabel.setText(String.format("%.1fx (%.1f dB)", inputGain, 
            20.0 * Math.log10(inputGain)));

        // Low-pass
        mLowPassEnabledSwitch.setSelected(config.isLowPassEnabled());
        mLowPassCutoffSlider.setValue(config.getLowPassCutoff());
        mLowPassCutoffLabel.setText((int)config.getLowPassCutoff() + " Hz");
        mLowPassCutoffSlider.setDisable(!config.isLowPassEnabled());

        // De-emphasis
        mDeemphasisEnabledSwitch.setSelected(config.isDeemphasisEnabled());
        double tc = config.getDeemphasisTimeConstant();
        mDeemphasisTimeConstantCombo.setValue(tc == 75.0 ? "75 μs (North America)" : "50 μs (Europe)");
        mDeemphasisTimeConstantCombo.setDisable(!config.isDeemphasisEnabled());

        // Voice Enhancement - load from AGC target level
        mVoiceEnhanceEnabledSwitch.setSelected(config.isAgcEnabled());
        // Map -30 to -6 dB range back to 0-100%
        float targetLevel = config.getAgcTargetLevel();
        float voiceAmount = ((targetLevel + 30.0f) / 24.0f) * 100.0f;
        voiceAmount = Math.max(0, Math.min(100, voiceAmount));
        mVoiceEnhanceSlider.setValue(voiceAmount);
        mVoiceEnhanceLabel.setText((int)voiceAmount + "%");
        mVoiceEnhanceSlider.setDisable(!config.isAgcEnabled());

        // Bass Boost
        mBassBoostEnabledSwitch.setSelected(config.isBassBoostEnabled());
        float bassBoostDb = config.getBassBoostDb();
        mBassBoostSlider.setValue(bassBoostDb);
        mBassBoostLabel.setText(String.format("+%.1f dB", bassBoostDb));
        mBassBoostSlider.setDisable(!config.isBassBoostEnabled());

        // Squelch / Noise Gate (Vox-Send style)
        mSquelchEnabledSwitch.setSelected(config.isNoiseGateEnabled());
        
        // Threshold is stored as percentage (0-100%)
        float thresholdPercent = config.getNoiseGateThreshold();
        mSquelchThresholdSlider.setValue(thresholdPercent);
        mSquelchThresholdLabel.setText(String.format("%.1f%%", thresholdPercent));
        
        // Reduction
        mSquelchReductionSlider.setValue(config.getNoiseGateReduction() * 100.0f);
        mSquelchReductionLabel.setText((int)(config.getNoiseGateReduction() * 100.0f) + "%");
        
        // Hold time
        int holdTime = config.getNoiseGateHoldTime();
        mHoldTimeSlider.setValue(holdTime);
        mHoldTimeLabel.setText(holdTime + " ms");
        
        // Disable controls if squelch is off
        boolean squelchEnabled = config.isNoiseGateEnabled();
        mSquelchThresholdSlider.setDisable(!squelchEnabled);
        mSquelchReductionSlider.setDisable(!squelchEnabled);
        mHoldTimeSlider.setDisable(!squelchEnabled);
    }

    private void disableAudioFilterControls()
    {
        mInputGainSlider.setValue(1.0);
        mLowPassEnabledSwitch.setSelected(false);
        mLowPassCutoffSlider.setDisable(true);
        mDeemphasisEnabledSwitch.setSelected(false);
        mDeemphasisTimeConstantCombo.setDisable(true);
        mVoiceEnhanceEnabledSwitch.setSelected(false);
        mVoiceEnhanceSlider.setDisable(true);
        mSquelchEnabledSwitch.setSelected(false);
        mSquelchThresholdSlider.setDisable(true);
        mSquelchReductionSlider.setDisable(true);
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
        if(talkgroup == null) talkgroup = 1;
        config.setTalkgroup(talkgroup);
        config.setAudioFilter(getAudioFilterEnable().isSelected());

        // Save VoxSend audio filter settings
        saveAudioFilterConfiguration(config);

        getItem().setDecodeConfiguration(config);
    }

    private void saveAudioFilterConfiguration(DecodeConfigNBFM config)
    {
        // Input Gain (store as AGC max gain for compatibility)
        float inputGain = (float)mInputGainSlider.getValue();
        float maxGainDb = (float)(40.0 * Math.log10(inputGain));
        config.setAgcMaxGain(maxGainDb);
        config.setAgcEnabled(true);

        // Low-pass
        config.setLowPassEnabled(mLowPassEnabledSwitch.isSelected());
        config.setLowPassCutoff(mLowPassCutoffSlider.getValue());

        // De-emphasis
        config.setDeemphasisEnabled(mDeemphasisEnabledSwitch.isSelected());
        String selected = mDeemphasisTimeConstantCombo.getValue();
        double tc = (selected != null && selected.startsWith("75")) ? 75.0 : 50.0;
        config.setDeemphasisTimeConstant(tc);

        // Voice Enhancement - store amount as AGC target level
        config.setAgcEnabled(mVoiceEnhanceEnabledSwitch.isSelected());
        float voiceAmount = (float)mVoiceEnhanceSlider.getValue();
        // Map 0-100% to -30 to -6 dB range for storage
        float targetLevel = -30.0f + (voiceAmount / 100.0f * 24.0f);
        config.setAgcTargetLevel(targetLevel);

        // Bass Boost
        config.setBassBoostEnabled(mBassBoostEnabledSwitch.isSelected());
        config.setBassBoostDb((float)mBassBoostSlider.getValue());

        // Squelch / Noise Gate (Vox-Send style)
        config.setNoiseGateEnabled(mSquelchEnabledSwitch.isSelected());
        config.setNoiseGateThreshold((float)mSquelchThresholdSlider.getValue());  // Already percentage
        config.setNoiseGateReduction((float)mSquelchReductionSlider.getValue() / 100.0f);
        config.setNoiseGateHoldTime((int)mHoldTimeSlider.getValue());
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
    
    /**
     * Handle analyze button click
     * TODO: Wire this to the decoder's NBFMAudioFilters instance
     * For now, this shows the UI flow - actual analysis requires decoder access
     */
    private void handleAnalyzeClick()
    {
        if (mAnalyzeButton.getText().equals("Analyze Audio & Suggest Settings")) {
            // Start analysis
            mAnalyzeButton.setText("Stop Analysis");
            mAnalyzeStatusLabel.setText("Analyzing... listening to audio (10 seconds)");
            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #0066cc; -fx-font-weight: bold;");
            
            // TODO: Get decoder's audio filter and start analyzing
            // NBFMAudioFilters filter = getDecoderAudioFilter();
            // filter.startAnalyzing();
            
            // TODO: After 10 seconds (or when stopped), get results
            // javafx.application.Platform.runLater(() -> {
            //     float[] results = filter.stopAnalyzing();
            //     if (results != null) {
            //         float carrierMax = results[0];
            //         float voiceMin = results[1];
            //         float recommended = results[2];
            //         
            //         mSquelchThresholdSlider.setValue(recommended);
            //         mAnalyzeStatusLabel.setText(String.format(
            //             "✅ Suggested: %.1f%% (Carrier: %.1f%%, Voice: %.1f%%)", 
            //             recommended, carrierMax, voiceMin));
            //         mAnalyzeStatusLabel.setStyle("-fx-text-fill: #009900; -fx-font-weight: bold;");
            //         modifiedProperty().set(true);
            //     } else {
            //         mAnalyzeStatusLabel.setText("⚠️ Not enough audio - try again with active transmissions");
            //         mAnalyzeStatusLabel.setStyle("-fx-text-fill: #cc6600;");
            //     }
            //     mAnalyzeButton.setText("Analyze Audio & Suggest Settings");
            // }, 10000);  // 10 second delay
            
            // For now, just show a message after short delay
            new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(1000),
                ae -> {
                    mAnalyzeStatusLabel.setText("⚠️ Analysis requires decoder connection (not yet wired)");
                    mAnalyzeStatusLabel.setStyle("-fx-text-fill: #cc6600;");
                    mAnalyzeButton.setText("Analyze Audio & Suggest Settings");
                }
            )).play();
            
        } else {
            // Stop analysis
            mAnalyzeButton.setText("Analyze Audio & Suggest Settings");
            mAnalyzeStatusLabel.setText("Analysis stopped");
            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #666;");
            
            // TODO: Stop analyzing
            // filter.stopAnalyzing();
        }
    }
}
