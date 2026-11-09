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

import io.github.dsheirer.gui.playlist.eventlog.EventLogConfigurationEditor;
import io.github.dsheirer.gui.playlist.record.RecordConfigurationEditor;
import io.github.dsheirer.gui.playlist.source.FrequencyEditor;
import io.github.dsheirer.gui.playlist.source.SourceConfigurationEditor;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.AuxDecodeConfiguration;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.smartnet.DecodeConfigSmartNet;
import io.github.dsheirer.module.decode.smartnet.SmartNetBandPlan;
import io.github.dsheirer.module.log.EventLogType;
import io.github.dsheirer.module.log.config.EventLogConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.RecorderType;
import io.github.dsheirer.record.config.RecordConfiguration;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.dsheirer.module.decode.config.DecodeConfiguration.CALL_TIMEOUT_MAXIMUM;
import static io.github.dsheirer.module.decode.config.DecodeConfiguration.CALL_TIMEOUT_MINIMUM;

/**
 * AP-fork (SmartNet): editor for a Motorola Type II control channel.
 */
public class SmartNetConfigurationEditor extends ChannelConfigurationEditor
{
    private final static Logger mLog = LoggerFactory.getLogger(SmartNetConfigurationEditor.class);
    private TitledPane mDecoderPane;
    private TitledPane mEventLogPane;
    private TitledPane mRecordPane;
    private TitledPane mSourcePane;
    private SourceConfigurationEditor mSourceConfigurationEditor;
    private EventLogConfigurationEditor mEventLogConfigurationEditor;
    private RecordConfigurationEditor mRecordConfigurationEditor;
    private ComboBox<SmartNetBandPlan> mBandPlanComboBox;
    private Spinner<Integer> mTrafficChannelPoolSizeSpinner;
    private Spinner<Integer> mCallTimeoutSpinner;

    /**
     * Constructs an instance
     * @param playlistManager for playlists
     * @param tunerManager for tuners
     * @param userPreferences for preferences
     */
    public SmartNetConfigurationEditor(PlaylistManager playlistManager, TunerManager tunerManager,
                                       UserPreferences userPreferences, IFilterProcessor filterProcessor)
    {
        super(playlistManager, tunerManager, userPreferences, filterProcessor);
        getTitledPanesBox().getChildren().add(getSourcePane());
        getTitledPanesBox().getChildren().add(getDecoderPane());
        getTitledPanesBox().getChildren().add(getEventLogPane());
        getTitledPanesBox().getChildren().add(getRecordPane());
    }

    @Override
    public DecoderType getDecoderType()
    {
        return DecoderType.SMARTNET;
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
            mDecoderPane.setText("Decoder: Motorola Type II");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10,10,10,10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label bandPlanLabel = new Label("Band Plan");
            GridPane.setHalignment(bandPlanLabel, HPos.RIGHT);
            GridPane.setConstraints(bandPlanLabel, 0, 0);
            gridPane.getChildren().add(bandPlanLabel);

            GridPane.setConstraints(getBandPlanComboBox(), 1, 0, 3, 1,
                HPos.LEFT, VPos.CENTER, Priority.ALWAYS, Priority.SOMETIMES);
            gridPane.getChildren().add(getBandPlanComboBox());

            Label poolSizeLabel = new Label("Max Voice Channels");
            GridPane.setHalignment(poolSizeLabel, HPos.RIGHT);
            GridPane.setConstraints(poolSizeLabel, 0, 1);
            gridPane.getChildren().add(poolSizeLabel);

            GridPane.setConstraints(getTrafficChannelPoolSizeSpinner(), 1, 1);
            gridPane.getChildren().add(getTrafficChannelPoolSizeSpinner());

            Label callTimeoutLabel = new Label("Call Limit Seconds");
            GridPane.setHalignment(callTimeoutLabel, HPos.RIGHT);
            GridPane.setConstraints(callTimeoutLabel, 2, 1);
            gridPane.getChildren().add(callTimeoutLabel);

            GridPane.setConstraints(getCallTimeoutSpinner(), 3, 1);
            gridPane.getChildren().add(getCallTimeoutSpinner());

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
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

    private TitledPane getRecordPane()
    {
        if(mRecordPane == null)
        {
            mRecordPane = new TitledPane();
            mRecordPane.setText("Recording");
            mRecordPane.setExpanded(false);

            Label notice = new Label("Note: use aliases to control call audio recording");
            notice.setPadding(new Insets(10, 10, 0, 10));

            VBox vBox = new VBox();
            vBox.getChildren().addAll(getRecordConfigurationEditor(), notice);

            mRecordPane.setContent(vBox);
        }

        return mRecordPane;
    }

    private SourceConfigurationEditor getSourceConfigurationEditor()
    {
        if(mSourceConfigurationEditor == null)
        {
            mSourceConfigurationEditor = new FrequencyEditor(mTunerManager,
                DecodeConfigSmartNet.CHANNEL_ROTATION_DELAY_MINIMUM_MS,
                DecodeConfigSmartNet.CHANNEL_ROTATION_DELAY_MAXIMUM_MS,
                DecodeConfigSmartNet.CHANNEL_ROTATION_DELAY_DEFAULT_MS);

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
            types.add(EventLogType.TRAFFIC_CALL_EVENT);
            types.add(EventLogType.TRAFFIC_DECODED_MESSAGE);

            mEventLogConfigurationEditor = new EventLogConfigurationEditor(types);
            mEventLogConfigurationEditor.setPadding(new Insets(5,5,5,5));
            mEventLogConfigurationEditor.modifiedProperty().addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            types.add(RecorderType.DEMODULATED_BIT_STREAM);
            types.add(RecorderType.TRAFFIC_BASEBAND);
            types.add(RecorderType.TRAFFIC_DEMODULATED_BIT_STREAM);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    private ComboBox<SmartNetBandPlan> getBandPlanComboBox()
    {
        if(mBandPlanComboBox == null)
        {
            mBandPlanComboBox = new ComboBox<>();
            mBandPlanComboBox.setMaxWidth(Double.MAX_VALUE);
            mBandPlanComboBox.setDisable(true);
            mBandPlanComboBox.setTooltip(new Tooltip("How the system's channel numbers map to frequencies.  " +
                "Most 800 MHz systems, including Massachusetts State Police, use the rebanded plan."));
            mBandPlanComboBox.getItems().addAll(SmartNetBandPlan.values());
            mBandPlanComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mBandPlanComboBox;
    }

    private Spinner<Integer> getTrafficChannelPoolSizeSpinner()
    {
        if(mTrafficChannelPoolSizeSpinner == null)
        {
            mTrafficChannelPoolSizeSpinner = new Spinner();
            mTrafficChannelPoolSizeSpinner.setDisable(true);
            mTrafficChannelPoolSizeSpinner.setTooltip(
                new Tooltip("Maximum number of calls followed at the same time"));
            mTrafficChannelPoolSizeSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            SpinnerValueFactory<Integer> svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 50);
            mTrafficChannelPoolSizeSpinner.setValueFactory(svf);
            mTrafficChannelPoolSizeSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mTrafficChannelPoolSizeSpinner;
    }

    private Spinner<Integer> getCallTimeoutSpinner()
    {
        if(mCallTimeoutSpinner == null)
        {
            mCallTimeoutSpinner = new Spinner<>();
            mCallTimeoutSpinner.setDisable(true);
            mCallTimeoutSpinner.setTooltip(new Tooltip("Longest a followed call may last.  Calls normally end when the control channel stops " +
                "announcing them."));
            mCallTimeoutSpinner.getStyleClass().add(Spinner.STYLE_CLASS_SPLIT_ARROWS_HORIZONTAL);
            var svf = new SpinnerValueFactory.IntegerSpinnerValueFactory(CALL_TIMEOUT_MINIMUM, CALL_TIMEOUT_MAXIMUM);
            mCallTimeoutSpinner.setValueFactory(svf);
            mCallTimeoutSpinner.getValueFactory().valueProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mCallTimeoutSpinner;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        getBandPlanComboBox().setDisable(config == null);
        getCallTimeoutSpinner().setDisable(config == null);
        getTrafficChannelPoolSizeSpinner().setDisable(config == null);

        if(config instanceof DecodeConfigSmartNet smartNet)
        {
            getBandPlanComboBox().getSelectionModel().select(smartNet.getBandPlan());
            getCallTimeoutSpinner().getValueFactory().setValue(smartNet.getCallTimeoutSeconds());
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(smartNet.getTrafficChannelPoolSize());
        }
        else if(config != null)
        {
            getBandPlanComboBox().getSelectionModel().select(SmartNetBandPlan.BAND_800_REBANDED);
            getCallTimeoutSpinner().getValueFactory().setValue(DecodeConfigSmartNet.CALL_TIMEOUT_DEFAULT_SECONDS);
            getTrafficChannelPoolSizeSpinner().getValueFactory().setValue(DecodeConfigSmartNet.TRAFFIC_CHANNEL_LIMIT_DEFAULT);
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigSmartNet config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigSmartNet existing)
        {
            config = existing;
        }
        else
        {
            config = new DecodeConfigSmartNet();
        }

        config.setBandPlan(getBandPlanComboBox().getSelectionModel().getSelectedItem());
        config.setCallTimeoutSeconds(getCallTimeoutSpinner().getValue());
        config.setTrafficChannelPoolSize(getTrafficChannelPoolSizeSpinner().getValue());
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
        //no-op
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        //no-op
    }

    @Override
    protected void setRecordConfiguration(RecordConfiguration config)
    {
        getRecordConfigurationEditor().setDisable(config == null);
        getRecordConfigurationEditor().setItem(config);
    }

    @Override
    protected void saveRecordConfiguration()
    {
        getRecordConfigurationEditor().save();
        RecordConfiguration config = getRecordConfigurationEditor().getItem();
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
