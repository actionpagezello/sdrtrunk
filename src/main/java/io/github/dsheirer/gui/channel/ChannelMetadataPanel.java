/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.channel.metadata;

import com.google.common.base.Joiner;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.channel.ViewChannelRequest;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.identifier.TalkgroupFormatPreference;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.DiscoveredTunerModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;

public class ChannelMetadataPanel extends JPanel implements ListSelectionListener
{
    private final static Logger mLog = LoggerFactory.getLogger(ChannelMetadataPanel.class);

    private static final String TABLE_PREFERENCE_KEY = "channel.metadata.panel";
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private IconModel mIconModel;
    private UserPreferences mUserPreferences;
    private JTable mTable;
    private Broadcaster<ProcessingChain> mSelectedProcessingChainBroadcaster = new Broadcaster<>();
    private Map<State,Color> mBackgroundColors = new EnumMap<>(State.class);
    private Map<State,Color> mForegroundColors = new EnumMap<>(State.class);
    private JTableColumnWidthMonitor mTableColumnMonitor;
    private Channel mUserSelectedChannel;
    private TunerManager mTunerManager;
    private PlaylistManager mPlaylistManager;
    private Set<Integer> mMutedChannelIds = new HashSet<>();

    /**
     * Table view for currently decoding channel metadata
     */
    public ChannelMetadataPanel(PlaylistManager playlistManager, IconModel iconModel, UserPreferences userPreferences,
                                TunerManager tunerManager)
    {
        mPlaylistManager = playlistManager;
        mChannelModel = playlistManager.getChannelModel();
        mChannelProcessingManager = playlistManager.getChannelProcessingManager();
        mIconModel = iconModel;
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        init();
    }

    /**
     * Initializes the panel
     */
    private void init()
    {
        setLayout( new MigLayout( "insets 0 0 0 0", "[grow,fill]", "[grow,fill]") );

        mTable = new JTable(mChannelProcessingManager.getChannelMetadataModel());
        mChannelProcessingManager.getChannelMetadataModel().setChannelAddListener(new ChannelAddListener());

        DefaultTableCellRenderer renderer = (DefaultTableCellRenderer)mTable.getDefaultRenderer(String.class);
        renderer.setHorizontalAlignment(SwingConstants.CENTER);

        mTable.getSelectionModel().addListSelectionListener(this);
        mTable.addMouseListener(new MouseSupport());

        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_DECODER_STATE)
            .setCellRenderer(new ColoredStateCellRenderer());
        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_USER_FROM)
            .setCellRenderer(new FromCellRenderer(mUserPreferences.getTalkgroupFormatPreference()));
        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_USER_TO)
            .setCellRenderer(new ToCellRenderer(mUserPreferences.getTalkgroupFormatPreference()));
        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_USER_FROM_ALIAS)
            .setCellRenderer(new AliasCellRenderer());
        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_USER_TO_ALIAS)
            .setCellRenderer(new AliasCellRenderer());
        mTable.getColumnModel().getColumn(ChannelMetadataModel.COLUMN_CONFIGURATION_FREQUENCY)
            .setCellRenderer(new FrequencyCellRenderer());

        //Add a table column width monitor to store/restore column widths
        mTableColumnMonitor = new JTableColumnWidthMonitor(mUserPreferences, mTable, TABLE_PREFERENCE_KEY);

        JScrollPane scrollPane = new JScrollPane(mTable, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        add(scrollPane);

        setColors();
    }

    /**
     * Setup the background and foreground color palette for the various channel states.
     */
    private void setColors()
    {
        mBackgroundColors.put(State.ACTIVE, Color.CYAN);
        mForegroundColors.put(State.ACTIVE, Color.BLUE);
        mBackgroundColors.put(State.CALL, Color.BLUE);
        mForegroundColors.put(State.CALL, Color.YELLOW);
        mBackgroundColors.put(State.CONTROL, Color.ORANGE);
        mForegroundColors.put(State.CONTROL, Color.BLUE);
        mBackgroundColors.put(State.DATA, Color.GREEN);
        mForegroundColors.put(State.DATA, Color.BLUE);
        mBackgroundColors.put(State.ENCRYPTED, Color.MAGENTA);
        mForegroundColors.put(State.ENCRYPTED, Color.WHITE);
        mBackgroundColors.put(State.FADE, Color.LIGHT_GRAY);
        mForegroundColors.put(State.FADE, Color.DARK_GRAY);
        mBackgroundColors.put(State.IDLE, Color.WHITE);
        mForegroundColors.put(State.IDLE, Color.DARK_GRAY);
        mBackgroundColors.put(State.RESET, Color.PINK);
        mForegroundColors.put(State.RESET, Color.YELLOW);
        mBackgroundColors.put(State.TEARDOWN, Color.DARK_GRAY);
        mForegroundColors.put(State.TEARDOWN, Color.WHITE);
    }

    @Override
    public void valueChanged(ListSelectionEvent e)
    {
        if(!mTable.getSelectionModel().getValueIsAdjusting())
        {
            ProcessingChain processingChain = null;

            int selectedViewRow = mTable.getSelectedRow();

            if(selectedViewRow >= 0)
            {
                int selectedModelRow = mTable.convertRowIndexToModel(selectedViewRow);

                ChannelMetadata selectedMetadata = mChannelProcessingManager.getChannelMetadataModel()
                    .getChannelMetadata(selectedModelRow);

                if(selectedMetadata != null)
                {
                    mUserSelectedChannel = mChannelProcessingManager.getChannelMetadataModel()
                        .getChannelFromMetadata(selectedMetadata);

                    processingChain = mChannelProcessingManager.getProcessingChain(mUserSelectedChannel);
                }
            }

            mSelectedProcessingChainBroadcaster.broadcast(processingChain);
        }
    }

    /**
     * Adds the listener to receive the processing chain associated with the metadata selected in the
     * metadata table.
     */
    public void addProcessingChainSelectionListener(Listener<ProcessingChain> listener)
    {
        mSelectedProcessingChainBroadcaster.addListener(listener);
    }

    /**
     * Removes the listener from receiving processing chain selection events.
     */
    public void removeProcessingChainSelectionListener(Listener<ProcessingChain> listener)
    {
        mSelectedProcessingChainBroadcaster.removeListener(listener);
    }

    /**
     * Cell renderer for frequency values
     */
    public class FrequencyCellRenderer extends DefaultTableCellRenderer
    {
        private final DecimalFormat FREQUENCY_FORMATTER = new DecimalFormat( "#.00000" );

        public FrequencyCellRenderer()
        {
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof FrequencyConfigurationIdentifier)
            {
                long frequency = ((FrequencyConfigurationIdentifier)value).getValue();
                label.setText(FREQUENCY_FORMATTER.format(frequency / 1e6d));
            }
            else
            {
                label.setText(null);
            }

            return label;
        }
    }

    /**
     * Alias cell renderer
     */
    public class AliasCellRenderer extends DefaultTableCellRenderer
    {
        public AliasCellRenderer()
        {
            setHorizontalAlignment(JLabel.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof List<?>)
            {
                List<Alias> aliases = (List<Alias>)value;

                if(!aliases.isEmpty())
                {
                    label.setText(Joiner.on(", ").skipNulls().join(aliases));
                    label.setIcon(mIconModel.getIcon(aliases.get(0).getIconName(), IconModel.DEFAULT_ICON_SIZE));
                    label.setForeground(aliases.get(0).getDisplayColor());
                }
                else
                {
                    label.setText(null);
                    label.setIcon(null);
                    label.setForeground(table.getForeground());
                }
            }
            else
            {
                label.setText(null);
                label.setIcon(null);
                label.setForeground(table.getForeground());
            }

            return label;
        }
    }

    /**
     * Abstract cell renderer for identifiers
     */
    public abstract class IdentifierCellRenderer extends DefaultTableCellRenderer
    {
        private final static String EMPTY_VALUE = "-----";
        private TalkgroupFormatPreference mTalkgroupFormatPreference;

        public IdentifierCellRenderer(TalkgroupFormatPreference talkgroupFormatPreference)
        {
            mTalkgroupFormatPreference = talkgroupFormatPreference;
            setHorizontalAlignment(JLabel.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(value instanceof ChannelMetadata)
            {
                ChannelMetadata channelMetadata = (ChannelMetadata)value;
                Identifier identifier = getIdentifier(channelMetadata);
                String text = mTalkgroupFormatPreference.format(identifier);
                if(text == null || text.isEmpty())
                {
                    text = EMPTY_VALUE;
                }
                else if(hasAdditionalIdentifier(channelMetadata))
                {
                    text = text + " " + getAdditionalIdentifier(channelMetadata);
                }

                label.setText(text);
            }
            else
            {
                label.setText(EMPTY_VALUE);
            }

            return label;
        }

        public abstract Identifier getIdentifier(ChannelMetadata channelMetadata);
        public abstract boolean hasAdditionalIdentifier(ChannelMetadata channelMetadata);
        public abstract Identifier getAdditionalIdentifier(ChannelMetadata channelMetadata);
    }

    /**
     * Cell renderer for the FROM identifier
     */
    public class FromCellRenderer extends IdentifierCellRenderer
    {
        public FromCellRenderer(TalkgroupFormatPreference talkgroupFormatPreference)
        {
            super(talkgroupFormatPreference);
        }

        @Override
        public Identifier getIdentifier(ChannelMetadata channelMetadata)
        {
            return channelMetadata.getFromIdentifier();
        }

        @Override
        public Identifier getAdditionalIdentifier(ChannelMetadata channelMetadata)
        {
            return channelMetadata.getTalkerAliasIdentifier();
        }

        @Override
        public boolean hasAdditionalIdentifier(ChannelMetadata channelMetadata)
        {
            return channelMetadata.hasTalkerAliasIdentifier();
        }
    }

    /**
     * Cell renderer for the TO identifier
     */
    public class ToCellRenderer extends IdentifierCellRenderer
    {
        public ToCellRenderer(TalkgroupFormatPreference talkgroupFormatPreference)
        {
            super(talkgroupFormatPreference);
        }

        @Override
        public Identifier getIdentifier(ChannelMetadata channelMetadata)
        {
            return channelMetadata.getToIdentifier();
        }

        @Override
        public Identifier getAdditionalIdentifier(ChannelMetadata channelMetadata) {return null;}
        @Override
        public boolean hasAdditionalIdentifier(ChannelMetadata channelMetadata) {return false;}
    }

    public class ColoredStateCellRenderer extends DefaultTableCellRenderer
    {
        public ColoredStateCellRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            JLabel label = (JLabel)super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            Color background = table.getBackground();
            Color foreground = table.getForeground();

            if(value instanceof ChannelStateIdentifier)
            {
                State state = ((ChannelStateIdentifier)value).getValue();
                label.setText(state.getDisplayValue());

                if(mBackgroundColors.containsKey(state))
                {
                    background = mBackgroundColors.get(state);
                }

                if(mForegroundColors.containsKey(state))
                {
                    foreground = mForegroundColors.get(state);
                }
            }
            else
            {
                setText("----");
            }

            setBackground(background);
            setForeground(foreground);

            return label;
        }
    }

    /**
     * Returns the aliases associated with a channel's metadata, preferring TO (talkgroup) aliases,
     * falling back to FROM (radio) aliases, and finally falling back to the channel's configured
     * alias list (for conventional channels like NBFM that may not have active TO/FROM identifiers
     * between transmissions).
     *
     * @param metadata to get aliases from
     * @param channel to get alias list name from (used as fallback)
     * @return list of aliases, or null if none found
     */
    private List<Alias> getChannelAliases(ChannelMetadata metadata, Channel channel)
    {
        //First: check live metadata TO aliases (populated during active calls/transmissions)
        List<Alias> toAliases = metadata.getToIdentifierAliases();

        if(toAliases != null && !toAliases.isEmpty())
        {
            return toAliases;
        }

        //Second: check live metadata FROM aliases
        List<Alias> fromAliases = metadata.getFromIdentifierAliases();

        if(fromAliases != null && !fromAliases.isEmpty())
        {
            return fromAliases;
        }

        //Third: fallback to the channel's configured alias list.
        //This handles conventional channels (NBFM) where TO/FROM identifiers are only
        //present during active transmissions but the channel is conceptually tied to aliases.
        String aliasListName = channel.getAliasListName();

        if(aliasListName != null && !aliasListName.isEmpty())
        {
            List<Alias> aliasListAliases = new ArrayList<>();

            for(Alias alias : mPlaylistManager.getAliasModel().getAliases())
            {
                if(alias.hasList() && alias.getAliasListName().equalsIgnoreCase(aliasListName))
                {
                    aliasListAliases.add(alias);
                }
            }

            if(!aliasListAliases.isEmpty())
            {
                return aliasListAliases;
            }
        }

        return null;
    }

    /**
     * Toggles mute state for a channel.
     *
     * If the channel is tied to an alias, the alias listen/DO_NOT_MONITOR toggle is used so that
     * the mute state is reflected in the alias configuration and applies across all traffic channels
     * for the same talkgroup.
     *
     * If the channel has no alias, mute is applied independently via the audio module and tracked
     * by channel ID so it can be re-applied when new processing chains are created.
     *
     * In both cases the current audio segment is force-closed for immediate effect.
     *
     * @param metadata containing alias information
     * @param channel to mute/unmute
     * @param mute true to mute, false to unmute
     */
    private void setChannelMuted(ChannelMetadata metadata, Channel channel, boolean mute)
    {
        List<Alias> aliases = getChannelAliases(metadata, channel);

        if(aliases != null)
        {
            //Alias path: toggle DO_NOT_MONITOR on the alias so it persists and applies globally
            for(Alias alias : aliases)
            {
                alias.setCallPriority(mute ? Priority.DO_NOT_MONITOR : Priority.DEFAULT_PRIORITY);
            }

            //Persist alias change to playlist
            mPlaylistManager.schedulePlaylistSave();

            //Notify alias editor to refresh its Listen toggle in real time
            for(Alias alias : aliases)
            {
                MyEventBus.getGlobalEventBus().post(new AliasPriorityChangedEvent(alias));
            }
        }
        else
        {
            //No alias path: track independently by channel ID
            if(mute)
            {
                mMutedChannelIds.add(channel.getChannelID());
            }
            else
            {
                mMutedChannelIds.remove(channel.getChannelID());
            }

        }

        //Force-close current audio segment for immediate effect on already-playing audio
        ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);

        if(processingChain != null)
        {
            for(Module module : processingChain.getModules())
            {
                if(module instanceof AbstractAudioModule)
                {
                    ((AbstractAudioModule)module).setMuted(mute);
                }
            }
        }
    }

    /**
     * Checks if a channel is currently muted.
     *
     * For channels with aliases, checks the alias DO_NOT_MONITOR priority.
     * For channels without aliases, checks the independent mMutedChannelIds set.
     *
     * @param metadata to check alias state
     * @param channel to check independent mute state
     * @return true if the channel is muted
     */
    private boolean isChannelMuted(ChannelMetadata metadata, Channel channel)
    {
        List<Alias> aliases = getChannelAliases(metadata, channel);

        if(aliases != null)
        {
            for(Alias alias : aliases)
            {
                if(alias.getPlaybackPriority() == Priority.DO_NOT_MONITOR)
                {
                    return true;
                }
            }

            return false;
        }

        //No alias - check independent mute tracking
        return mMutedChannelIds.contains(channel.getChannelID());
    }

    /**
     * Attempts to show the tuner serving a channel in the main spectral display (waterfall).
     * Finds the tuner by checking the channel's source configuration for a preferred tuner name,
     * or by matching against the processing chain's current tuner channel source.
     * @param channel to show in waterfall
     */
    private void showChannelInWaterfall(Channel channel)
    {
        if(mTunerManager == null)
        {
            mLog.warn("TunerManager not available - cannot show channel in waterfall");
            return;
        }

        DiscoveredTunerModel discoveredTunerModel = mTunerManager.getDiscoveredTunerModel();
        Tuner tuner = null;

        // First try: find tuner via preferred tuner name from source config
        SourceConfiguration sourceConfig = channel.getSourceConfiguration();

        String preferredTunerName = null;

        if(sourceConfig instanceof SourceConfigTuner)
        {
            preferredTunerName = ((SourceConfigTuner)sourceConfig).getPreferredTuner();
        }
        else if(sourceConfig instanceof SourceConfigTunerMultipleFrequency)
        {
            preferredTunerName = ((SourceConfigTunerMultipleFrequency)sourceConfig).getPreferredTuner();
        }

        if(preferredTunerName != null)
        {
            DiscoveredTuner discoveredTuner = mTunerManager.getDiscoveredTuner(preferredTunerName);

            if(discoveredTuner != null && discoveredTuner.hasTuner())
            {
                tuner = discoveredTuner.getTuner();
            }
        }

        // Second try: find tuner via the processing chain's source
        if(tuner == null)
        {
            ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);

            if(processingChain != null)
            {
                Source source = processingChain.getSource();

                if(source instanceof TunerChannelSource)
                {
                    long channelFrequency = ((TunerChannelSource)source).getFrequency();

                    // Find which tuner is serving this frequency
                    for(DiscoveredTuner discoveredTuner : discoveredTunerModel.getAvailableTuners())
                    {
                        if(discoveredTuner.hasTuner())
                        {
                            try
                            {
                                long tunerFreq = discoveredTuner.getTuner().getTunerController().getFrequency();
                                double sampleRate = discoveredTuner.getTuner().getTunerController().getSampleRate();
                                long halfBandwidth = (long)(sampleRate / 2.0);

                                if(channelFrequency >= tunerFreq - halfBandwidth &&
                                   channelFrequency <= tunerFreq + halfBandwidth)
                                {
                                    tuner = discoveredTuner.getTuner();
                                    break;
                                }
                            }
                            catch(Exception ex)
                            {
                                mLog.error("Error checking tuner frequency", ex);
                            }
                        }
                    }
                }
            }
        }

        if(tuner != null)
        {
            // Determine the channel frequency to center on
            long channelFrequency = 0;

            if(sourceConfig instanceof SourceConfigTuner)
            {
                channelFrequency = ((SourceConfigTuner)sourceConfig).getFrequency();
            }
            else if(sourceConfig instanceof SourceConfigTunerMultipleFrequency)
            {
                List<Long> frequencies = ((SourceConfigTunerMultipleFrequency)sourceConfig).getFrequencies();

                if(frequencies != null && !frequencies.isEmpty())
                {
                    channelFrequency = frequencies.get(0);
                }
            }

            // Fall back to the live tuner channel source frequency if config frequency is 0
            if(channelFrequency == 0)
            {
                ProcessingChain pc = mChannelProcessingManager.getProcessingChain(channel);

                if(pc != null && pc.getSource() instanceof TunerChannelSource)
                {
                    channelFrequency = ((TunerChannelSource)pc.getSource()).getFrequency();
                }
            }

            if(channelFrequency > 0)
            {
                discoveredTunerModel.broadcast(new TunerEvent(tuner,
                    TunerEvent.Event.REQUEST_MAIN_SPECTRAL_DISPLAY, channelFrequency));
            }
            else
            {
                discoveredTunerModel.broadcast(new TunerEvent(tuner,
                    TunerEvent.Event.REQUEST_MAIN_SPECTRAL_DISPLAY));
            }
        }
        else
        {
        }
    }

    public class MouseSupport extends MouseAdapter
    {
        @Override
        public void mouseClicked(MouseEvent e)
        {
            if(e.getButton() == MouseEvent.BUTTON3) //Right click for context
            {
                JPopupMenu popupMenu = new JPopupMenu();

                boolean populated = false;

                int viewRowIndex = mTable.rowAtPoint(e.getPoint());

                if(viewRowIndex >= 0)
                {
                    int modelRowIndex = mTable.convertRowIndexToModel(viewRowIndex);

                    if(modelRowIndex >= 0)
                    {
                        ChannelMetadata metadata = mChannelProcessingManager.getChannelMetadataModel().getChannelMetadata(modelRowIndex);

                        if(metadata != null)
                        {
                            Channel channel = mChannelProcessingManager.getChannelMetadataModel()
                                .getChannelFromMetadata(metadata);

                            if(channel != null)
                            {
                                // View/Edit menu item
                                JMenuItem viewChannel = new JMenuItem("View/Edit: " + channel.getShortTitle());
                                viewChannel.addActionListener(e2 -> MyEventBus.getGlobalEventBus().post(new ViewChannelRequest(channel)));
                                popupMenu.add(viewChannel);
                                populated = true;

                                // Mute/Unmute menu item
                                boolean isMuted = isChannelMuted(metadata, channel);
                                String muteLabel = isMuted ? "Unmute: " + channel.getShortTitle()
                                                          : "Mute: " + channel.getShortTitle();
                                JMenuItem muteItem = new JMenuItem(muteLabel);
                                muteItem.addActionListener(e2 -> setChannelMuted(metadata, channel, !isMuted));
                                popupMenu.add(muteItem);

                                // Show in Waterfall menu item - only show if channel has a tuner source
                                SourceConfiguration sourceConfig = channel.getSourceConfiguration();

                                if(sourceConfig instanceof SourceConfigTuner ||
                                   sourceConfig instanceof SourceConfigTunerMultipleFrequency)
                                {
                                    JMenuItem waterfallItem = new JMenuItem("Show in Waterfall");
                                    waterfallItem.addActionListener(e2 -> showChannelInWaterfall(channel));
                                    popupMenu.add(waterfallItem);
                                }
                            }
                        }
                    }
                }

                if(!populated)
                {
                    popupMenu.add(new JMenuItem("No Actions Available"));
                }

                popupMenu.show(mTable, e.getX(), e.getY());
            }
        }
    }

    /**
     * Listener to be notified when a channel and associated channel metadata(s) are added to the underlying
     * channel metadata model.
     *
     * When a channel is added, it is compared the the last user selected channel and if they are the same, it
     * invokes a selection event on the channel metadata row so that the channel metadata is re-selected.  This is
     * primarily a hack to counter-act the DMR Capacity+ REST channel rotation where a channel is converted to a
     * traffic channel and the previous channel is restarted.  The UI effect is that the user selected channel row
     * in the Now Playing window continually loses selection over the channel and causes the user to perpetually
     * chase the channel row.
     */
    public class ChannelAddListener implements Listener<ChannelAndMetadata>
    {
        @Override
        public void receive(ChannelAndMetadata channelAndMetadata)
        {
            Channel channel = channelAndMetadata.getChannel();

            if(mUserSelectedChannel != null &&
               mUserSelectedChannel.getChannelID() == channel.getChannelID())
            {
                List<ChannelMetadata> metadata = channelAndMetadata.getChannelMetadata();

                if(metadata.size() > 0)
                {
                    int modelRow = mChannelProcessingManager.getChannelMetadataModel().getRow(metadata.get(0));

                    if(modelRow >= 0)
                    {
                        int tableRow = mTable.convertRowIndexToView(modelRow);
                        mTable.getSelectionModel().setSelectionInterval(tableRow, tableRow);
                    }
                }
            }

            //Re-apply independent mute for non-alias channels when new processing chains are created
            if(mMutedChannelIds.contains(channel.getChannelID()))
            {
                ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);

                if(processingChain != null)
                {
                    for(Module module : processingChain.getModules())
                    {
                        if(module instanceof AbstractAudioModule)
                        {
                            ((AbstractAudioModule)module).setMuted(true);
                        }
                    }
                }

            }
        }
    }
}
