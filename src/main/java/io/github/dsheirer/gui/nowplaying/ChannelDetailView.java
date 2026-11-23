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
package io.github.dsheirer.gui.nowplaying;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import java.text.DecimalFormat;
import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Activity summary details for the selected channel.
 */
public class ChannelDetailView extends VBox implements Listener<ProcessingChain>
{
    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("#.00000");
    private static final String EMPTY_DETAILS = "Please select a channel to view details";
    private final ChannelProcessingManager mChannelProcessingManager;
    private final Label mSystemLabel = new Label();
    private final Label mSiteLabel = new Label();
    private final Label mNameLabel = new Label();
    private final TextArea mDetailTextArea = new TextArea(EMPTY_DETAILS);
    private ProcessingChain mProcessingChain;

    /**
     * Constructs an instance.  Construct on the JavaFX application thread.
     * @param channelProcessingManager for the channel of the processing chain
     */
    public ChannelDetailView(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = channelProcessingManager;

        Button refreshButton = new Button("Refresh");
        refreshButton.setTooltip(new Tooltip("Update the channel details"));
        refreshButton.setOnAction(event -> refresh());

        HBox labels = new HBox(5, new Label("System:"), grow(mSystemLabel), new Label("Site:"), grow(mSiteLabel),
            new Label("Channel Name:"), grow(mNameLabel), refreshButton);
        labels.setAlignment(Pos.CENTER_LEFT);
        labels.setPadding(new Insets(3, 2, 3, 2));

        mDetailTextArea.setEditable(false);
        //Activity summaries are column-aligned plain text and require a fixed-width font
        mDetailTextArea.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(mDetailTextArea, Priority.ALWAYS);
        getChildren().addAll(labels, mDetailTextArea);
    }

    /**
     * Updates the details for the currently selected channel.  Call on the JavaFX application thread.
     */
    public void refresh()
    {
        receive(mProcessingChain);
    }

    /**
     * AP-fork: decoder, frequency and alias list of the channel, ahead of the activity summary.
     */
    private static void appendConfiguration(StringBuilder sb, Channel channel)
    {
        sb.append("Channel Configuration\n");

        if(channel.getDecodeConfiguration() != null)
        {
            sb.append("  Decoder: ").append(channel.getDecodeConfiguration().getDecoderType()).append("\n");
        }

        SourceConfiguration sourceConfig = channel.getSourceConfiguration();

        if(sourceConfig instanceof SourceConfigTuner tuner)
        {
            if(tuner.getFrequency() > 0)
            {
                sb.append("  Frequency: ").append(FREQUENCY_FORMAT.format(tuner.getFrequency() / 1e6d)).append(" MHz\n");
            }
        }
        else if(sourceConfig instanceof SourceConfigTunerMultipleFrequency multiple)
        {
            List<Long> frequencies = multiple.getFrequencies();

            if(frequencies != null && !frequencies.isEmpty())
            {
                sb.append("  Frequencies: ");

                for(int i = 0; i < frequencies.size(); i++)
                {
                    if(i > 0)
                    {
                        sb.append(", ");
                    }

                    sb.append(FREQUENCY_FORMAT.format(frequencies.get(i) / 1e6d));
                }

                sb.append(" MHz\n");
            }
        }

        String aliasList = channel.getAliasListName();

        if(aliasList != null && !aliasList.isEmpty())
        {
            sb.append("  Alias List: ").append(aliasList).append("\n");
        }

        sb.append("\n");
    }

    private static Label grow(Label label)
    {
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
    }

    /**
     * Shows the details for the processing chain.  Call on the JavaFX application thread.
     * @param processingChain to show, or null to clear the view
     */
    @Override
    public void receive(ProcessingChain processingChain)
    {
        mProcessingChain = processingChain;

        Channel channel = mChannelProcessingManager.getChannel(processingChain);
        mSystemLabel.setText(channel != null ? channel.getSystem() : null);
        mSiteLabel.setText(channel != null ? channel.getSite() : null);
        mNameLabel.setText(channel != null ?
            (channel.getChannelType() == Channel.ChannelType.TRAFFIC ? "Traffic Channel" : channel.getName()) : null);

        if(processingChain != null)
        {
            StringBuilder sb = new StringBuilder();

            //AP-fork: channel configuration header
            if(channel != null)
            {
                appendConfiguration(sb, channel);
            }

            boolean listed = false;

            for(DecoderState decoderState: processingChain.getDecoderStates())
            {
                sb.append(decoderState.getActivitySummary());
                listed |= decoderState.hasOwnCallSummary();
            }

            //AP-fork (edacs-25): calls by talkgroup with alias names for every kind of channel.  EDACS, Motorola
            //Type II and P25 list them in their own summary.
            if(!listed)
            {
                AliasList aliasList = null;

                if(channel != null && processingChain.getAliasModel() != null && channel.getAliasListName() != null)
                {
                    aliasList = processingChain.getAliasModel().getAliasList(channel.getAliasListName());
                }

                //ap-18: in the activity summary layout (section heading, aligned table)
                io.github.dsheirer.module.decode.event.ActivitySummary summary =
                    new io.github.dsheirer.module.decode.event.ActivitySummary(null);
                processingChain.getCallSummary().addTo(summary, aliasList);
                sb.append(summary);
            }

            //Keep the scroll position, so that a refresh doesn't move the user back to the top
            double scrollTop = mDetailTextArea.getScrollTop();
            mDetailTextArea.setText(sb.toString());
            mDetailTextArea.setScrollTop(scrollTop);
        }
        else
        {
            mDetailTextArea.setText(EMPTY_DETAILS);
        }
    }
}
