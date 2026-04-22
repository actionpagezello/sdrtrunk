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
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import javafx.scene.control.Hyperlink;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest;
import io.github.dsheirer.gui.preference.PreferenceEditorType;
import javafx.scene.control.Hyperlink;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.geometry.HPos;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tab;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.control.ToggleSwitch;

/**
 * Narrow-Band FM channel configuration editor
 */
public class NBFMConfigurationEditor extends ChannelConfigurationEditor
{
    private Tab mAuxDecoderPane;
    private Tab mDecoderPane;
    private Tab mEventLogPane;
    private Tab mRecordPane;
    private Tab mSourcePane;
    private TextField mTalkgroupField;
    private ToggleSwitch mAudioFilterEnable;
    private TextFormatter<Integer> mTalkgroupTextFormatter;
    private ToggleSwitch mBasebandRecordSwitch;
    private SegmentedButton mBandwidthButton;

    // CTCSS/DCS Tone Filter UI
    private Tab mToneFilterPane;
    private ToggleSwitch mToneFilterEnabledSwitch;
    private ComboBox<ChannelToneFilter.ToneType> mToneTypeCombo;
    private ComboBox<CTCSSCode> mCtcssCodeCombo;
    private ComboBox<DCSCode> mDcsCodeCombo;

    // Squelch Tail Removal UI
    private Tab mSquelchTailPane;
    private ToggleSwitch mSquelchTailEnabledSwitch;
    private Spinner<Integer> mTailRemovalSpinner;
    private Spinner<Integer> mHeadRemovalSpinner;
    private Spinner<Integer> mAudioHangtimeSpinner;

    // Audio Filters (VoxSend Chain) UI
    private Tab mAudioFiltersPane;
    private Slider mInputGainSlider;
    private TextField mInputGainField;
    private ToggleSwitch mLowPassEnabledSwitch;
    private Slider mLowPassCutoffSlider;
    private TextField mLowPassCutoffField;
    // De-emphasis removed from UI — config fields preserved for backward compatibility
    private ToggleSwitch mVoiceEnhanceEnabledSwitch;
    private Slider mVoiceEnhanceSlider;
    private TextField mVoiceEnhanceField;
    private ToggleSwitch mBassBoostEnabledSwitch;
    private Slider mBassBoostSlider;
    private TextField mBassBoostField;
    private ToggleSwitch mHissReductionEnabledSwitch;
    private Slider mHissReductionDbSlider;
    private TextField mHissReductionDbField;
    private Slider mHissReductionCornerSlider;
    private TextField mHissReductionCornerField;
    private ToggleSwitch mSquelchEnabledSwitch;
    private Slider mSquelchThresholdSlider;
    private TextField mSquelchThresholdField;
    private Slider mSquelchReductionSlider;
    private TextField mSquelchReductionField;
    private Slider mHoldTimeSlider;
    private TextField mHoldTimeField;
    private javafx.scene.control.Button mAnalyzeButton;
    private Label mAnalyzeStatusLabel;
    private Timeline mAiPollingTimeline;
    private Hyperlink mAnalyzeStatusLink;

    private boolean mLoadingConfiguration = false;

    private SourceConfigurationEditor mSourceConfigurationEditor;
    private AuxDecoderConfigurationEditor mAuxDecoderConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private final TalkgroupValueChangeListener mTalkgroupValueChangeListener = new TalkgroupValueChangeListener();
    private final IntegerFormatter mDecimalFormatter = new IntegerFormatter(1, 65535);
    private final HexFormatter mHexFormatter = new HexFormatter(1, 65535);

    @Override
    public void setItem(io.github.dsheirer.controller.channel.Channel item)
    {
        super.setItem(item);
        if(mAiPollingTimeline != null) {
            mAiPollingTimeline.play();
        }
    }

    /**
     * Constructs an instance
     * @param playlistManager for playlists
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    private final UserPreferences mUserPreferences;
    public NBFMConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                   UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        mUserPreferences = userPreferences;
        getTabPane().getTabs().add(getSourcePane());
        getTabPane().getTabs().add(getDecoderPane());
        getTabPane().getTabs().add(getToneFilterPane());
        getTabPane().getTabs().add(getSquelchTailPane());
        getTabPane().getTabs().add(getAudioFiltersPane());
        getTabPane().getTabs().add(getAuxDecoderPane());
        getTabPane().getTabs().add(getEventLogPane());
        getTabPane().getTabs().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.NBFM;
    }

    private Tab getSourcePane()
    {
        if(mSourcePane == null)
        {
            mSourcePane = new Tab("Source", getSourceConfigurationEditor());

        }

        return mSourcePane;
    }

    private Tab getDecoderPane()
    {
        if(mDecoderPane == null)
        {
            mDecoderPane = new Tab();
            mDecoderPane.setText("Decoder: NBFM");


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
            mDecoderPane.selectedProperty().addListener((observable, oldValue, newValue) -> {
                if(newValue)
                {
                    //Reset the config so the editor gets updated
                    setDecoderConfiguration(getItem().getDecodeConfiguration());
                }
            });
        }

        return mDecoderPane;
    }

    // === Tone Filter (CTCSS / DCS) pane ===
    private Tab getToneFilterPane()
    {
        if(mToneFilterPane == null)
        {
            mToneFilterPane = new Tab();
            mToneFilterPane.setText("Tone Filter (CTCSS / DCS)");


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

    // === Squelch Tail Removal pane ===
    private Tab getSquelchTailPane()
    {
        if(mSquelchTailPane == null)
        {
            mSquelchTailPane = new Tab();
            mSquelchTailPane.setText("Squelch Tail Removal");


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

            Label hangtimeLabel = new Label("Hangtime (ms)");
            hangtimeLabel.setTooltip(new Tooltip("Delay before closing audio segment after transmission ends.\n" +
                    "Prevents cutting off the end of audio in ThinLine/Zello streams.\n" +
                    "0 = immediate close (default), 100-300 = recommended for streaming"));
            GridPane.setHalignment(hangtimeLabel, HPos.RIGHT);
            GridPane.setConstraints(hangtimeLabel, 4, 1);
            gridPane.getChildren().add(hangtimeLabel);

            mAudioHangtimeSpinner = new Spinner<>(0, 2000, 0, 50);
            mAudioHangtimeSpinner.setEditable(true);
            mAudioHangtimeSpinner.setPrefWidth(100);
            mAudioHangtimeSpinner.setTooltip(new Tooltip("Delay before closing audio segment (ms).\n" +
                    "Prevents audio cutoff at end of transmissions.\n" +
                    "0 = instant close, 100-300 = recommended for streaming"));
            mAudioHangtimeSpinner.getValueFactory().valueProperty()
                    .addListener((obs, ov, nv) -> modifiedProperty().set(true));
            GridPane.setConstraints(mAudioHangtimeSpinner, 5, 1);
            gridPane.getChildren().add(mAudioHangtimeSpinner);

            mSquelchTailPane.setContent(gridPane);
        }
        return mSquelchTailPane;
    }

    // === Audio Filters (VoxSend Chain) pane ===
    private Tab getAudioFiltersPane()
    {
        if(mAudioFiltersPane == null)
        {
            mAudioFiltersPane = new Tab();
            mAudioFiltersPane.setText("Audio Filters (VoxSend Chain)");


            VBox contentBox = new VBox(10);
            contentBox.setPadding(new Insets(10,10,10,10));

            // Analyze section (helps user find optimal threshold)
            GridPane analyzePane = new GridPane();
            analyzePane.setHgap(10);
            analyzePane.setVgap(5);
            analyzePane.setPadding(new Insets(5,0,10,0));

            mAnalyzeButton = new javafx.scene.control.Button("AI Enhance");
            mAnalyzeButton.setTooltip(new Tooltip("Listen to audio for 5-10 seconds and suggest optimal threshold\nMake sure transmissions are active!"));
            mAnalyzeButton.setStyle("-fx-font-weight: bold;");
            mAnalyzeButton.setOnAction(e -> handleAnalyzeClick());
            mAnalyzeButton.setDisable(true); // Disable initially
            GridPane.setConstraints(mAnalyzeButton, 0, 0);
            analyzePane.getChildren().add(mAnalyzeButton);

            mAnalyzeStatusLabel = new Label("Waiting for transmissions...");
            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #666;");
            GridPane.setConstraints(mAnalyzeStatusLabel, 1, 0);
            analyzePane.getChildren().add(mAnalyzeStatusLabel);

            mAnalyzeStatusLink = new Hyperlink("AI features are turned off. Click here to enable.");
            mAnalyzeStatusLink.setOnAction(ev -> {
                MyEventBus.getGlobalEventBus().post(new ViewUserPreferenceEditorRequest(PreferenceEditorType.AI));
            });
            mAnalyzeStatusLink.setVisible(false);
            mAnalyzeStatusLink.setManaged(false);
            GridPane.setConstraints(mAnalyzeStatusLink, 1, 0);
            analyzePane.getChildren().add(mAnalyzeStatusLink);

            contentBox.getChildren().add(analyzePane);
            contentBox.getChildren().add(new Separator());

            // 1. Low-pass filter
            contentBox.getChildren().add(createLowPassSection());
            contentBox.getChildren().add(new Separator());

            // 2. Hiss Reduction (high-shelf cut)
            contentBox.getChildren().add(createHissReductionSection());
            contentBox.getChildren().add(new Separator());

            // 3. Bass Boost
            contentBox.getChildren().add(createBassBoostSection());
            contentBox.getChildren().add(new Separator());

            // 4. Voice Enhancement
            contentBox.getChildren().add(createVoiceEnhanceSection());
            contentBox.getChildren().add(new Separator());

            // 5. Intelligent Squelch
            contentBox.getChildren().add(createSquelchSection());
            contentBox.getChildren().add(new Separator());

            // 7. Output Gain (applied last)
            contentBox.getChildren().add(createInputGainSection());

            mAudioFiltersPane.setContent(contentBox);

            mAiPollingTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
                if (mUserPreferences.getAIPreference().isAIEnabled()) {
                    mAnalyzeStatusLabel.setVisible(true);
                    mAnalyzeStatusLabel.setManaged(true);
                    mAnalyzeStatusLink.setVisible(false);
                    mAnalyzeStatusLink.setManaged(false);
                    if (getItem() != null && getItem().getName() != null) {
                        int count = io.github.dsheirer.module.decode.nbfm.ai.AudioBufferManager.getBufferedEventCount(mUserPreferences, getItem().getName());
                        if (count < 5) {
                            mAnalyzeButton.setDisable(true);
                            mAnalyzeStatusLabel.setText(count + "/5 transmissions. At least 5 required prior to using AI enhance.");
                            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #666;");
                        } else {
                            if (mSquelchEnabledSwitch.isSelected()) {
                                mAnalyzeButton.setDisable(false);
                            }
                            mAnalyzeStatusLabel.setText("Ready for AI optimization.");
                            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #009900;");
                        }
                    }
                } else {
                    mAnalyzeButton.setDisable(true);
                    mAnalyzeStatusLabel.setVisible(false);
                    mAnalyzeStatusLabel.setManaged(false);
                    mAnalyzeStatusLink.setVisible(true);
                    mAnalyzeStatusLink.setManaged(true);
                }
            }));
            mAiPollingTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        return mAudioFiltersPane;
    }

    private Tab getEventLogPane()
    {
        if(mEventLogPane == null)
        {
            mEventLogPane = new Tab("Logging", getEventLogConfigurationEditor());

        }

        return mEventLogPane;
    }

    private Tab getAuxDecoderPane()
    {
        if(mAuxDecoderPane == null)
        {
            mAuxDecoderPane = new Tab("Additional Decoders", getAuxDecoderConfigurationEditor());

        }

        return mAuxDecoderPane;
    }

    private Tab getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new Tab();
            mRecordPane.setText("Recording");


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

    /**
     * Creates a styled TextField that syncs bidirectionally with a Slider.
     * When the user presses Enter or the field loses focus, the parsed value updates the slider.
     *
     * @param slider The slider to sync with
     * @param defaultText Initial display text
     * @param suffix Unit suffix (e.g. " Hz", " dB", "%", " ms")
     * @param format printf format for the numeric value (e.g. "%.0f", "%.1f")
     */
    private TextField createSliderTextField(Slider slider, String defaultText, String suffix, String format)
    {
        TextField field = new TextField(defaultText);
        field.setPrefWidth(90);
        field.setMaxWidth(90);
        field.setStyle("-fx-font-size: 11px;");

        // Commit on Enter key
        field.setOnAction(event -> {
            commitTextFieldToSlider(field, slider, suffix, format);
        });

        // Commit on focus loss
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if(!isFocused)
            {
                commitTextFieldToSlider(field, slider, suffix, format);
            }
        });

        return field;
    }

    /**
     * Parses the numeric value from a text field and updates the associated slider.
     */
    private void commitTextFieldToSlider(TextField field, Slider slider, String suffix, String format)
    {
        try
        {
            String text = field.getText().trim();
            // Strip suffix and common symbols
            text = text.replace(suffix.trim(), "").replace("+", "").replace("x", "").replace("(", "").trim();
            // Handle "1.0x (0.0 dB)" format for gain field
            if(text.contains(" "))
            {
                text = text.split("\\s+")[0];
            }
            double value = Double.parseDouble(text);
            value = Math.max(slider.getMin(), Math.min(slider.getMax(), value));
            slider.setValue(value);
            // Re-format the display to be consistent
            field.setText(String.format(format, value) + suffix);
            if(!mLoadingConfiguration)
            {
                modifiedProperty().set(true);
            }
        }
        catch(NumberFormatException e)
        {
            // Revert to current slider value on parse failure
            field.setText(String.format(format, slider.getValue()) + suffix);
        }
    }

    private VBox createInputGainSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("7. Output Gain (Applied Last)");
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
                mInputGainField.setText(String.format("%.1fx (%.1f dB)", val.floatValue(),
                    20.0 * Math.log10(val.doubleValue())));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mInputGainSlider, 1, 0);
        controlsPane.getChildren().add(mInputGainSlider);

        mInputGainField = new TextField("1.0x (0.0 dB)");
        mInputGainField.setPrefWidth(120);
        mInputGainField.setMaxWidth(120);
        mInputGainField.setStyle("-fx-font-size: 11px;");
        mInputGainField.setOnAction(event -> {
            commitTextFieldToSlider(mInputGainField, mInputGainSlider, "x", "%.1f");
            // Re-format with dB display
            double v = mInputGainSlider.getValue();
            mInputGainField.setText(String.format("%.1fx (%.1f dB)", v, 20.0 * Math.log10(v)));
        });
        mInputGainField.focusedProperty().addListener((obs2, wasFocused, isFocused) -> {
            if(!isFocused)
            {
                commitTextFieldToSlider(mInputGainField, mInputGainSlider, "x", "%.1f");
                double v = mInputGainSlider.getValue();
                mInputGainField.setText(String.format("%.1fx (%.1f dB)", v, 20.0 * Math.log10(v)));
            }
        });
        GridPane.setConstraints(mInputGainField, 2, 0);
        controlsPane.getChildren().add(mInputGainField);

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

        mLowPassCutoffSlider = new Slider(2500, 4000, 2800);
        mLowPassCutoffSlider.setMajorTickUnit(500);
        mLowPassCutoffSlider.setMinorTickCount(4);
        mLowPassCutoffSlider.setShowTickMarks(true);
        mLowPassCutoffSlider.setShowTickLabels(true);
        mLowPassCutoffSlider.setPrefWidth(300);
        mLowPassCutoffSlider.setTooltip(new Tooltip("Higher = brighter\nLower = less noise\nDefault: 2800 Hz"));
        mLowPassCutoffSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                mLowPassCutoffField.setText(val.intValue() + " Hz");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mLowPassCutoffSlider, 1, 0);
        controlsPane.getChildren().add(mLowPassCutoffSlider);

        mLowPassCutoffField = createSliderTextField(mLowPassCutoffSlider, "2800 Hz", " Hz", "%.0f");
        GridPane.setConstraints(mLowPassCutoffField, 2, 0);
        controlsPane.getChildren().add(mLowPassCutoffField);

        section.getChildren().addAll(title, mLowPassEnabledSwitch, controlsPane);
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

        mVoiceEnhanceSlider = new Slider(0, 100, 0);
        mVoiceEnhanceSlider.setMajorTickUnit(25);
        mVoiceEnhanceSlider.setMinorTickCount(4);
        mVoiceEnhanceSlider.setShowTickMarks(true);
        mVoiceEnhanceSlider.setShowTickLabels(true);
        mVoiceEnhanceSlider.setPrefWidth(300);
        mVoiceEnhanceSlider.setTooltip(new Tooltip("Boost speech presence\n0% = off, 100% = max clarity\nDefault: 0%"));
        mVoiceEnhanceSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                mVoiceEnhanceField.setText(val.intValue() + "%");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mVoiceEnhanceSlider, 1, 0);
        controlsPane.getChildren().add(mVoiceEnhanceSlider);

        mVoiceEnhanceField = createSliderTextField(mVoiceEnhanceSlider, "0%", "%", "%.0f");
        GridPane.setConstraints(mVoiceEnhanceField, 2, 0);
        controlsPane.getChildren().add(mVoiceEnhanceField);

        section.getChildren().addAll(title, mVoiceEnhanceEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createBassBoostSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("3. Bass Boost");
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
                mBassBoostField.setText(String.format("+%.1f dB", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mBassBoostSlider, 1, 0);
        controlsPane.getChildren().add(mBassBoostSlider);

        mBassBoostField = createSliderTextField(mBassBoostSlider, "+0.0 dB", " dB", "+%.1f");
        GridPane.setConstraints(mBassBoostField, 2, 0);
        controlsPane.getChildren().add(mBassBoostField);

        section.getChildren().addAll(title, mBassBoostEnabledSwitch, controlsPane);
        return section;
    }

    private VBox createHissReductionSection()
    {
        VBox section = new VBox(5);
        Label title = new Label("2. Hiss Reduction");
        title.setFont(Font.font(null, FontWeight.BOLD, 12));

        mHissReductionEnabledSwitch = new ToggleSwitch("Enable Hiss Reduction");
        mHissReductionEnabledSwitch.setTooltip(new Tooltip(
                "High-shelf cut above corner frequency to reduce FM hiss.\n" +
                "Stacks with Low-Pass Filter."));
        mHissReductionEnabledSwitch.selectedProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                modifiedProperty().set(true);
                mHissReductionDbSlider.setDisable(!val);
                mHissReductionCornerSlider.setDisable(!val);
            }
        });

        GridPane controlsPane = new GridPane();
        controlsPane.setHgap(10);
        controlsPane.setVgap(5);

        // Row 0: Shelf cut amount
        Label dbLabel = new Label("Cut Amount:");
        GridPane.setConstraints(dbLabel, 0, 0);
        controlsPane.getChildren().add(dbLabel);

        mHissReductionDbSlider = new Slider(-12, 0, -6);
        mHissReductionDbSlider.setMajorTickUnit(3);
        mHissReductionDbSlider.setMinorTickCount(2);
        mHissReductionDbSlider.setShowTickMarks(true);
        mHissReductionDbSlider.setShowTickLabels(true);
        mHissReductionDbSlider.setPrefWidth(300);
        mHissReductionDbSlider.setTooltip(new Tooltip(
                "High-shelf attenuation above corner frequency.\n" +
                "0 dB = off, -12 dB = max hiss cut\nDefault: -6 dB"));
        mHissReductionDbSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                mHissReductionDbField.setText(String.format("%.1f dB", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mHissReductionDbSlider, 1, 0);
        controlsPane.getChildren().add(mHissReductionDbSlider);

        mHissReductionDbField = createSliderTextField(mHissReductionDbSlider, "-6.0 dB", " dB", "%.1f");
        GridPane.setConstraints(mHissReductionDbField, 2, 0);
        controlsPane.getChildren().add(mHissReductionDbField);

        // Row 1: Corner frequency
        Label cornerLabel = new Label("Corner Freq:");
        GridPane.setConstraints(cornerLabel, 0, 1);
        controlsPane.getChildren().add(cornerLabel);

        mHissReductionCornerSlider = new Slider(1000, 3500, 2000);
        mHissReductionCornerSlider.setMajorTickUnit(500);
        mHissReductionCornerSlider.setMinorTickCount(4);
        mHissReductionCornerSlider.setShowTickMarks(true);
        mHissReductionCornerSlider.setShowTickLabels(true);
        mHissReductionCornerSlider.setPrefWidth(300);
        mHissReductionCornerSlider.setTooltip(new Tooltip(
                "Shelf pivot frequency. Hiss above this is attenuated.\n" +
                "Lower = more hiss cut but slightly duller voice.\nDefault: 2000 Hz"));
        mHissReductionCornerSlider.valueProperty().addListener((obs, old, val) -> {
            if(!mLoadingConfiguration)
            {
                mHissReductionCornerField.setText(String.format("%.0f Hz", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mHissReductionCornerSlider, 1, 1);
        controlsPane.getChildren().add(mHissReductionCornerSlider);

        mHissReductionCornerField = createSliderTextField(mHissReductionCornerSlider, "2000 Hz", " Hz", "%.0f");
        GridPane.setConstraints(mHissReductionCornerField, 2, 1);
        controlsPane.getChildren().add(mHissReductionCornerField);

        section.getChildren().addAll(title, mHissReductionEnabledSwitch, controlsPane);
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
                if (!val) {
                    mAnalyzeButton.setDisable(true);
                } else if (getItem() != null && io.github.dsheirer.module.decode.nbfm.ai.AudioBufferManager.getBufferedEventCount(mUserPreferences, getItem().getName()) >= 5) {
                    mAnalyzeButton.setDisable(false);
                }
            }
        });

        // Analyze section (helps user find optimal threshold)

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
                mSquelchThresholdField.setText(String.format("%.1f%%", val.doubleValue()));
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mSquelchThresholdSlider, 1, 0);
        controlsPane.getChildren().add(mSquelchThresholdSlider);

        mSquelchThresholdField = createSliderTextField(mSquelchThresholdSlider, "4.0%", "%", "%.1f");
        GridPane.setConstraints(mSquelchThresholdField, 2, 0);
        controlsPane.getChildren().add(mSquelchThresholdField);

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
                mSquelchReductionField.setText(val.intValue() + "%");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mSquelchReductionSlider, 1, 1);
        controlsPane.getChildren().add(mSquelchReductionSlider);

        mSquelchReductionField = createSliderTextField(mSquelchReductionSlider, "80%", "%", "%.0f");
        GridPane.setConstraints(mSquelchReductionField, 2, 1);
        controlsPane.getChildren().add(mSquelchReductionField);

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
                mHoldTimeField.setText(val.intValue() + " ms");
                modifiedProperty().set(true);
            }
        });
        GridPane.setConstraints(mHoldTimeSlider, 1, 2);
        controlsPane.getChildren().add(mHoldTimeSlider);

        mHoldTimeField = createSliderTextField(mHoldTimeSlider, "500 ms", " ms", "%.0f");
        GridPane.setConstraints(mHoldTimeField, 2, 2);
        controlsPane.getChildren().add(mHoldTimeField);

        section.getChildren().addAll(title, mSquelchEnabledSwitch, controlsPane);
        return section;
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
                        bandwidth = DecodeConfigNBFM.Bandwidth.BW_7_5;
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
        mLoadingConfiguration = true;

        if(config instanceof DecodeConfigNBFM)
        {
            getBandwidthButton().setDisable(false);
            DecodeConfigNBFM decodeConfigNBFM = (DecodeConfigNBFM)config;
            final DecodeConfigNBFM.Bandwidth bandwidth = (decodeConfigNBFM.getBandwidth() != null ?
                    decodeConfigNBFM.getBandwidth() : DecodeConfigNBFM.Bandwidth.BW_7_5);

            for(Toggle toggle: getBandwidthButton().getToggleGroup().getToggles())
            {
                toggle.setSelected(toggle.getUserData() == bandwidth);
            }

            updateTextFormatter(decodeConfigNBFM.getTalkgroup());
            getAudioFilterEnable().setDisable(false);
            getAudioFilterEnable().setSelected(decodeConfigNBFM.isAudioFilter());

            // Load tone filter settings
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

            // Load squelch tail settings
            mSquelchTailEnabledSwitch.setSelected(decodeConfigNBFM.isSquelchTailRemovalEnabled());
            mTailRemovalSpinner.getValueFactory().setValue(decodeConfigNBFM.getSquelchTailRemovalMs());
            mHeadRemovalSpinner.getValueFactory().setValue(decodeConfigNBFM.getSquelchHeadRemovalMs());
            mAudioHangtimeSpinner.getValueFactory().setValue(decodeConfigNBFM.getAudioHangtimeMs());

            // Load audio filter settings
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

            // Reset tone filter controls
            mToneFilterEnabledSwitch.setSelected(false);
            mToneTypeCombo.setValue(ChannelToneFilter.ToneType.CTCSS);
            mCtcssCodeCombo.setValue(null);
            mCtcssCodeCombo.setPromptText("Select PL tone");
            mDcsCodeCombo.setValue(null);
            mDcsCodeCombo.setPromptText("Select DCS code");
            updateToneCodeVisibility();

            // Reset squelch tail controls
            mSquelchTailEnabledSwitch.setSelected(false);
            mTailRemovalSpinner.getValueFactory().setValue(100);
            mHeadRemovalSpinner.getValueFactory().setValue(0);

            // Disable audio filter controls
            disableAudioFilterControls();
        }

        mLoadingConfiguration = false;
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

        DecodeConfigNBFM.Bandwidth bandwidth = DecodeConfigNBFM.Bandwidth.BW_7_5;

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

        // Save tone filter settings
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

        // Save squelch tail settings
        config.setSquelchTailRemovalEnabled(mSquelchTailEnabledSwitch.isSelected());
        config.setSquelchTailRemovalMs(mTailRemovalSpinner.getValue());
        config.setSquelchHeadRemovalMs(mHeadRemovalSpinner.getValue());
        config.setAudioHangtimeMs(mAudioHangtimeSpinner.getValue());

        // Save audio filter settings
        saveAudioFilterConfiguration(config);

        getItem().setDecodeConfiguration(config);
    }

    private void loadAudioFilterConfiguration(DecodeConfigNBFM config)
    {
        // Input Gain (map from AGC max gain)
        float inputGain = (float)Math.pow(10.0, config.getAgcMaxGain() / 40.0);
        mInputGainSlider.setValue(inputGain);
        mInputGainField.setText(String.format("%.1fx (%.1f dB)", inputGain,
            20.0 * Math.log10(inputGain)));

        // Low-pass
        mLowPassEnabledSwitch.setSelected(config.isLowPassEnabled());
        mLowPassCutoffSlider.setValue(config.getLowPassCutoff());
        mLowPassCutoffField.setText((int)config.getLowPassCutoff() + " Hz");
        mLowPassCutoffSlider.setDisable(!config.isLowPassEnabled());

        // Voice Enhancement - load from AGC target level
        mVoiceEnhanceEnabledSwitch.setSelected(config.isAgcEnabled());
        // Map -30 to -6 dB range back to 0-100%
        float targetLevel = config.getAgcTargetLevel();
        float voiceAmount = ((targetLevel + 30.0f) / 24.0f) * 100.0f;
        voiceAmount = Math.max(0, Math.min(100, voiceAmount));
        mVoiceEnhanceSlider.setValue(voiceAmount);
        mVoiceEnhanceField.setText((int)voiceAmount + "%");
        mVoiceEnhanceSlider.setDisable(!config.isAgcEnabled());

        // Bass Boost
        mBassBoostEnabledSwitch.setSelected(config.isBassBoostEnabled());
        float bassBoostDb = config.getBassBoostDb();
        mBassBoostSlider.setValue(bassBoostDb);
        mBassBoostField.setText(String.format("+%.1f dB", bassBoostDb));
        mBassBoostSlider.setDisable(!config.isBassBoostEnabled());

        // Hiss Reduction
        mHissReductionEnabledSwitch.setSelected(config.isHissReductionEnabled());
        float hissDb = config.getHissReductionDb();
        mHissReductionDbSlider.setValue(hissDb);
        mHissReductionDbField.setText(String.format("%.1f dB", hissDb));
        double hissCorner = config.getHissReductionCornerHz();
        mHissReductionCornerSlider.setValue(hissCorner);
        mHissReductionCornerField.setText(String.format("%.0f Hz", hissCorner));
        mHissReductionDbSlider.setDisable(!config.isHissReductionEnabled());
        mHissReductionCornerSlider.setDisable(!config.isHissReductionEnabled());

        // Squelch / Noise Gate (Vox-Send style)
        mSquelchEnabledSwitch.setSelected(config.isNoiseGateEnabled());

        // Threshold is stored as percentage (0-100%)
        float thresholdPercent = config.getNoiseGateThreshold();
        mSquelchThresholdSlider.setValue(thresholdPercent);
        mSquelchThresholdField.setText(String.format("%.1f%%", thresholdPercent));

        // Reduction
        mSquelchReductionSlider.setValue(config.getNoiseGateReduction() * 100.0f);
        mSquelchReductionField.setText((int)(config.getNoiseGateReduction() * 100.0f) + "%");

        // Hold time
        int holdTime = config.getNoiseGateHoldTime();
        mHoldTimeSlider.setValue(holdTime);
        mHoldTimeField.setText(holdTime + " ms");

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
        mVoiceEnhanceEnabledSwitch.setSelected(false);
        mVoiceEnhanceSlider.setDisable(true);
        mHissReductionEnabledSwitch.setSelected(false);
        mHissReductionDbSlider.setDisable(true);
        mHissReductionCornerSlider.setDisable(true);
        mSquelchEnabledSwitch.setSelected(false);
        mSquelchThresholdSlider.setDisable(true);
        mSquelchReductionSlider.setDisable(true);
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
        // Voice Enhancement - store amount as AGC target level
        config.setAgcEnabled(mVoiceEnhanceEnabledSwitch.isSelected());
        float voiceAmount = (float)mVoiceEnhanceSlider.getValue();
        // Map 0-100% to -30 to -6 dB range for storage
        float targetLevel = -30.0f + (voiceAmount / 100.0f * 24.0f);
        config.setAgcTargetLevel(targetLevel);

        // Bass Boost
        config.setBassBoostEnabled(mBassBoostEnabledSwitch.isSelected());
        config.setBassBoostDb((float)mBassBoostSlider.getValue());

        // Hiss Reduction
        config.setHissReductionEnabled(mHissReductionEnabledSwitch.isSelected());
        config.setHissReductionDb((float)mHissReductionDbSlider.getValue());
        config.setHissReductionCornerHz(mHissReductionCornerSlider.getValue());

        // Squelch / Noise Gate (Vox-Send style)
        config.setNoiseGateEnabled(mSquelchEnabledSwitch.isSelected());
        config.setNoiseGateThreshold((float)mSquelchThresholdSlider.getValue());  // Already percentage
        config.setNoiseGateReduction((float)mSquelchReductionSlider.getValue() / 100.0f);
        config.setNoiseGateHoldTime((int)mHoldTimeSlider.getValue());
    }

    private void handleAnalyzeClick()
    {
        if (mAnalyzeButton.getText().equals("AI Enhance")) {
            mAnalyzeButton.setText("Enhancing...");
            mAnalyzeStatusLabel.setText("Connecting to Gemini...");
            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #0066cc; -fx-font-weight: bold;");
            io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM config = (io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM) getItem().getDecodeConfiguration();
            new Thread(() -> {
                try {
                    io.github.dsheirer.module.decode.nbfm.ai.AIAudioOptimizer optimizer = new io.github.dsheirer.module.decode.nbfm.ai.AIAudioOptimizer(mUserPreferences);
                    io.github.dsheirer.module.decode.nbfm.ai.AudioBufferManager bufferManager = new io.github.dsheirer.module.decode.nbfm.ai.AudioBufferManager(mUserPreferences, getItem().getName());
                    java.util.List<java.util.List<float[]>> events = bufferManager.getBufferedEvents();

                    io.github.dsheirer.module.decode.nbfm.ai.AIAnalysisResult result = optimizer.analyze(config, events);
                    javafx.application.Platform.runLater(() -> {
                        showAIAnalysisDialog(config, result);
                    });

                } catch (Exception e) {
                    javafx.application.Platform.runLater(() -> {
                        mAnalyzeStatusLabel.setText("\u26A0\uFE0F Error: " + e.getMessage());
                        mAnalyzeStatusLabel.setStyle("-fx-text-fill: #cc0000;");
                        mAnalyzeButton.setText("AI Enhance");
                    });
                }
            }).start();
        }
    }
    private void showAIAnalysisDialog(io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM config, io.github.dsheirer.module.decode.nbfm.ai.AIAnalysisResult result) {
        javafx.scene.control.Dialog<Boolean> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("AI Audio Optimization");
        dialog.setHeaderText("AI Analysis Complete");

        javafx.scene.control.DialogPane dialogPane = dialog.getDialogPane();
        javafx.scene.control.ButtonType saveButtonType = new javafx.scene.control.ButtonType("Save", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType discardButtonType = new javafx.scene.control.ButtonType("Discard", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        dialogPane.getButtonTypes().addAll(saveButtonType, discardButtonType);

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));

        content.getChildren().add(new javafx.scene.control.Label("Issues Found: " + result.getIssuesFound()));
        content.getChildren().add(new javafx.scene.control.Label("Improvements: " + result.getImprovements()));
        content.getChildren().add(new javafx.scene.control.Label("Explanation: " + result.getExplanation()));
        content.getChildren().add(new javafx.scene.control.Separator());
        content.getChildren().add(new javafx.scene.control.Label("Recommended Settings:"));

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(10);
        grid.setVgap(5);

        javafx.scene.control.CheckBox cbHissReduction = new javafx.scene.control.CheckBox("Hiss Reduction Enabled");
        cbHissReduction.setSelected(result.isHissReductionEnabled());
        grid.add(cbHissReduction, 0, 0);

        javafx.scene.control.TextField tfHissReductionDb = new javafx.scene.control.TextField(String.valueOf(result.getHissReductionDb()));
        grid.add(new javafx.scene.control.Label("Hiss Reduction dB:"), 0, 1);
        grid.add(tfHissReductionDb, 1, 1);

        javafx.scene.control.TextField tfHissReductionCorner = new javafx.scene.control.TextField(String.valueOf(result.getHissReductionCorner()));
        grid.add(new javafx.scene.control.Label("Hiss Reduction Corner:"), 0, 2);
        grid.add(tfHissReductionCorner, 1, 2);

        javafx.scene.control.CheckBox cbLowPass = new javafx.scene.control.CheckBox("Low Pass Enabled");
        cbLowPass.setSelected(result.isLowPassEnabled());
        grid.add(cbLowPass, 0, 3);

        javafx.scene.control.TextField tfLowPassCutoff = new javafx.scene.control.TextField(String.valueOf(result.getLowPassCutoff()));
        grid.add(new javafx.scene.control.Label("Low Pass Cutoff:"), 0, 4);
        grid.add(tfLowPassCutoff, 1, 4);

        javafx.scene.control.CheckBox cbDeemphasis = new javafx.scene.control.CheckBox("De-emphasis Enabled");
        cbDeemphasis.setSelected(result.isDeemphasisEnabled());
        grid.add(cbDeemphasis, 0, 5);

        javafx.scene.control.CheckBox cbBassBoost = new javafx.scene.control.CheckBox("Bass Boost Enabled");
        cbBassBoost.setSelected(result.isBassBoostEnabled());
        grid.add(cbBassBoost, 0, 6);

        javafx.scene.control.TextField tfBassBoostDb = new javafx.scene.control.TextField(String.valueOf(result.getBassBoostDb()));
        grid.add(new javafx.scene.control.Label("Bass Boost dB:"), 0, 7);
        grid.add(tfBassBoostDb, 1, 7);

        javafx.scene.control.CheckBox cbAgc = new javafx.scene.control.CheckBox("AGC Enabled");
        cbAgc.setSelected(result.isAgcEnabled());
        grid.add(cbAgc, 0, 8);

        javafx.scene.control.TextField tfAgcTarget = new javafx.scene.control.TextField(String.valueOf(result.getAgcTargetLevel()));
        grid.add(new javafx.scene.control.Label("AGC Target Level:"), 0, 9);
        grid.add(tfAgcTarget, 1, 9);

        javafx.scene.control.CheckBox cbNoiseGate = new javafx.scene.control.CheckBox("Noise Gate Enabled");
        cbNoiseGate.setSelected(result.isNoiseGateEnabled());
        grid.add(cbNoiseGate, 0, 10);

        javafx.scene.control.TextField tfNoiseGateThreshold = new javafx.scene.control.TextField(String.valueOf(result.getNoiseGateThreshold()));
        grid.add(new javafx.scene.control.Label("Noise Gate Threshold:"), 0, 11);
        grid.add(tfNoiseGateThreshold, 1, 11);

        javafx.scene.control.TextField tfNoiseGateReduction = new javafx.scene.control.TextField(String.valueOf(result.getNoiseGateReduction()));
        grid.add(new javafx.scene.control.Label("Noise Gate Reduction:"), 0, 12);
        grid.add(tfNoiseGateReduction, 1, 12);

        javafx.scene.control.TextField tfAgcMaxGain = new javafx.scene.control.TextField(String.valueOf(result.getAgcMaxGain()));
        grid.add(new javafx.scene.control.Label("AGC Max Gain:"), 0, 13);
        grid.add(tfAgcMaxGain, 1, 13);

        content.getChildren().add(grid);
        dialogPane.setContent(content);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return true;
            }
            return false;
        });

        java.util.Optional<Boolean> resultOpt = dialog.showAndWait();

        if (resultOpt.isPresent() && resultOpt.get()) {
            try {
                config.setHissReductionEnabled(cbHissReduction.isSelected());
                config.setHissReductionDb(Float.parseFloat(tfHissReductionDb.getText()));
                config.setHissReductionCornerHz(Double.parseDouble(tfHissReductionCorner.getText()));
                config.setLowPassEnabled(cbLowPass.isSelected());
                config.setLowPassCutoff(Double.parseDouble(tfLowPassCutoff.getText()));
                config.setDeemphasisEnabled(cbDeemphasis.isSelected());
                config.setBassBoostEnabled(cbBassBoost.isSelected());
                config.setBassBoostDb(Float.parseFloat(tfBassBoostDb.getText()));
                config.setAgcEnabled(cbAgc.isSelected());
                config.setAgcTargetLevel(Float.parseFloat(tfAgcTarget.getText()));
                config.setNoiseGateEnabled(cbNoiseGate.isSelected());
                config.setNoiseGateThreshold(Float.parseFloat(tfNoiseGateThreshold.getText()));
                config.setNoiseGateReduction(Float.parseFloat(tfNoiseGateReduction.getText()));
                config.setAgcMaxGain(Float.parseFloat(tfAgcMaxGain.getText()));

                mAnalyzeStatusLabel.setText("\u2705 AI Optimization Applied.");
                mAnalyzeStatusLabel.setStyle("-fx-text-fill: #009900; -fx-font-weight: bold;");
                mAnalyzeButton.setText("AI Enhance");
                loadAudioFilterConfiguration(config);

                modifiedProperty().set(true);
            } catch (NumberFormatException ex) {
                mAnalyzeStatusLabel.setText("\u26A0\uFE0F Error: Invalid number format in settings.");
                mAnalyzeStatusLabel.setStyle("-fx-text-fill: #cc0000;");
                mAnalyzeButton.setText("AI Enhance");
            }
        } else {
            mAnalyzeStatusLabel.setText("Optimization discarded.");
            mAnalyzeStatusLabel.setStyle("-fx-text-fill: #666;");
            mAnalyzeButton.setText("AI Enhance");
        }
    }

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
