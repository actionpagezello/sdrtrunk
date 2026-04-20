/*******************************************************************************
 * sdrtrunk
 * Copyright (C) 2014-2017 Dennis Sheirer
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
 *
 ******************************************************************************/
package io.github.dsheirer.channel.details;

import io.github.dsheirer.channel.state.DecoderState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DecimalFormat;
import java.util.List;

public class ChannelDetailPanel extends JPanel implements Listener<ProcessingChain>
{
    private static final String EMPTY_DETAILS = "Please select a channel to view details";

    private JLabel mSystemLabel;
    private JLabel mSiteLabel;
    private JLabel mNameLabel;
    private JTextArea mDetailTextPane;

    private ChannelProcessingManager mChannelProcessingManager;
    private ProcessingChain mProcessingChain;

    public ChannelDetailPanel(ChannelProcessingManager channelProcessingManager)
    {
        mChannelProcessingManager = channelProcessingManager;

        init();
    }

    private void init()
    {
        setLayout(new MigLayout("insets 0 0 0 0", "[grow,fill]", "[]0[grow,fill]"));

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new MigLayout("insets 1 1 1 1", "[][grow,fill][][grow,fill][][grow,fill][]", ""));

        buttonPanel.add(new JLabel("System:"));
        mSystemLabel = new JLabel(" ");
        buttonPanel.add(mSystemLabel);

        buttonPanel.add(new JLabel("Site:"));
        mSiteLabel = new JLabel(" ");
        buttonPanel.add(mSiteLabel);

        buttonPanel.add(new JLabel("Channel Name:"));
        mNameLabel = new JLabel(" ");
        buttonPanel.add(mNameLabel);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                receive(mProcessingChain);
            }
        });
        buttonPanel.add(refreshButton);

        add(buttonPanel, "wrap");

        mDetailTextPane = new JTextArea(EMPTY_DETAILS);
        DefaultCaret caret = (DefaultCaret)mDetailTextPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        add(new JScrollPane(mDetailTextPane), "grow");
    }

    private static final DecimalFormat FREQUENCY_FORMAT = new DecimalFormat("#.00000");

    @Override
    public void receive(ProcessingChain processingChain)
    {
        mProcessingChain = processingChain;

        Channel channel = mChannelProcessingManager.getChannel(processingChain);

        final String system = channel != null ? channel.getSystem() : null;
        final String site = channel != null ? channel.getSite() : null;
        final String name = channel != null ?
            (channel.getChannelType() == Channel.ChannelType.TRAFFIC ? "Traffic Channel" : channel.getName()) : null;

        final String details;

        if(processingChain != null)
        {
            StringBuilder sb = new StringBuilder();

            //Add channel configuration header with useful context
            if(channel != null)
            {
                sb.append("Channel Configuration\n");

                if(channel.getDecodeConfiguration() != null)
                {
                    sb.append("  Decoder: ").append(channel.getDecodeConfiguration().getDecoderType()).append("\n");
                }

                SourceConfiguration sourceConfig = channel.getSourceConfiguration();
                if(sourceConfig instanceof SourceConfigTuner)
                {
                    long freq = ((SourceConfigTuner)sourceConfig).getFrequency();
                    if(freq > 0)
                    {
                        sb.append("  Frequency: ").append(FREQUENCY_FORMAT.format(freq / 1e6d)).append(" MHz\n");
                    }
                }
                else if(sourceConfig instanceof SourceConfigTunerMultipleFrequency)
                {
                    List<Long> frequencies = ((SourceConfigTunerMultipleFrequency)sourceConfig).getFrequencies();
                    if(frequencies != null && !frequencies.isEmpty())
                    {
                        sb.append("  Frequencies: ");
                        for(int i = 0; i < frequencies.size(); i++)
                        {
                            if(i > 0) sb.append(", ");
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

            for(DecoderState decoderState : processingChain.getDecoderStates())
            {
                sb.append(decoderState.getActivitySummary());
            }

            details = sb.toString();
        }
        else
        {
            details = EMPTY_DETAILS;
        }

        EventQueue.invokeLater(new Runnable()
        {
            @Override
            public void run()
            {
                mSystemLabel.setText(system);
                mSiteLabel.setText(site);
                mNameLabel.setText(name);
                mDetailTextPane.setText(details);
            }
        });
    }
}
