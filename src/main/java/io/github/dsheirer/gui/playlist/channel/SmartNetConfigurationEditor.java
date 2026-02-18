/*
 * *****************************************************************************
 * Copyright (C) 2024 SDR Trunk Contributors
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
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SmartNet/SmartZone channel configuration editor
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
    private ComboBox<String> mBandPlanComboBox;

    /**
     * Constructs an instance
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
            mDecoderPane.setText("Decoder: SmartNet / SmartZone (3600 baud)");
            mDecoderPane.setExpanded(true);

            GridPane gridPane = new GridPane();
            gridPane.setPadding(new Insets(10, 10, 10, 10));
            gridPane.setHgap(10);
            gridPane.setVgap(10);

            Label bandPlanLabel = new Label("Band Plan");
            GridPane.setHalignment(bandPlanLabel, HPos.RIGHT);
            GridPane.setConstraints(bandPlanLabel, 0, 0);
            gridPane.getChildren().add(bandPlanLabel);

            GridPane.setConstraints(getBandPlanComboBox(), 1, 0);
            gridPane.getChildren().add(getBandPlanComboBox());

            Label helpLabel = new Label("800 Standard: Most non-rebanded 800 MHz sites\n" +
                "800 Rebanded: Sites that have been rebanded (866-869 MHz)\n" +
                "900 MHz: 900 MHz systems\n" +
                "Custom: VHF/UHF or other non-standard band plans");
            helpLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
            helpLabel.setWrapText(true);
            GridPane.setConstraints(helpLabel, 0, 1, 2, 1);
            gridPane.getChildren().add(helpLabel);

            mDecoderPane.setContent(gridPane);
        }

        return mDecoderPane;
    }

    private ComboBox<String> getBandPlanComboBox()
    {
        if(mBandPlanComboBox == null)
        {
            mBandPlanComboBox = new ComboBox<>(FXCollections.observableArrayList(
                "800 MHz Standard",
                "800 MHz Rebanded",
                "800 MHz Splinter",
                "900 MHz",
                "Custom"
            ));
            mBandPlanComboBox.getSelectionModel().select(0);
            mBandPlanComboBox.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mBandPlanComboBox;
    }

    private String getBandPlanValue()
    {
        String selected = getBandPlanComboBox().getSelectionModel().getSelectedItem();
        if(selected == null) return "800_standard";

        switch(selected)
        {
            case "800 MHz Rebanded": return "800_rebanded";
            case "800 MHz Splinter": return "800_splinter";
            case "900 MHz": return "900";
            case "Custom": return "custom";
            default: return "800_standard";
        }
    }

    private void setBandPlanFromConfig(String bandPlan)
    {
        if(bandPlan == null) bandPlan = "800_standard";

        switch(bandPlan.toLowerCase())
        {
            case "800_rebanded":
            case "800_reband":
                getBandPlanComboBox().getSelectionModel().select("800 MHz Rebanded");
                break;
            case "800_splinter":
                getBandPlanComboBox().getSelectionModel().select("800 MHz Splinter");
                break;
            case "900":
                getBandPlanComboBox().getSelectionModel().select("900 MHz");
                break;
            case "custom":
            case "400":
            case "400_custom":
                getBandPlanComboBox().getSelectionModel().select("Custom");
                break;
            default:
                getBandPlanComboBox().getSelectionModel().select("800 MHz Standard");
                break;
        }
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
            mEventLogConfigurationEditor.setPadding(new Insets(5, 5, 5, 5));
            mEventLogConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mEventLogConfigurationEditor;
    }

    private RecordConfigurationEditor getRecordConfigurationEditor()
    {
        if(mRecordConfigurationEditor == null)
        {
            List<RecorderType> types = new ArrayList<>();
            types.add(RecorderType.BASEBAND);
            mRecordConfigurationEditor = new RecordConfigurationEditor(types);
            mRecordConfigurationEditor.setDisable(true);
            mRecordConfigurationEditor.modifiedProperty()
                .addListener((observable, oldValue, newValue) -> modifiedProperty().set(true));
        }

        return mRecordConfigurationEditor;
    }

    @Override
    protected void setDecoderConfiguration(DecodeConfiguration config)
    {
        if(config instanceof DecodeConfigSmartNet smartNetConfig)
        {
            setBandPlanFromConfig(smartNetConfig.getBandPlan());
        }
        else
        {
            getBandPlanComboBox().getSelectionModel().select("800 MHz Standard");
        }
    }

    @Override
    protected void saveDecoderConfiguration()
    {
        DecodeConfigSmartNet config;

        if(getItem().getDecodeConfiguration() instanceof DecodeConfigSmartNet)
        {
            config = (DecodeConfigSmartNet) getItem().getDecodeConfiguration();
        }
        else
        {
            config = new DecodeConfigSmartNet();
        }

        config.setBandPlan(getBandPlanValue());
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
        //No aux decoders for SmartNet control channel
    }

    @Override
    protected void saveAuxDecoderConfiguration()
    {
        //No aux decoders for SmartNet control channel
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
