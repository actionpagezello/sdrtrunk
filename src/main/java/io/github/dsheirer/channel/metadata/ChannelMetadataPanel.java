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
import io.github.dsheirer.gui.control.SafeTableRowSorter;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;

public class ChannelMetadataPanel extends JPanel implements ListSelectionListener
{
    private final static Logger mLog = LoggerFactory.getLogger(ChannelMetadataPanel.class);

    private static final String TABLE_PREFERENCE_KEY = "channel.metadata.panel";

    /**
     * Name prefix applied by every traffic channel manager when creating a traffic channel from a
     * parent channel.  Kept in sync with P25/DMR/NXDN/MPT1327TrafficChannelManager.
     */
    private static final String TRAFFIC_CHANNEL_NAME_PREFIX = "T-";

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

        //Add a row sorter for clickable column header sorting with case-insensitive string comparison.
        //SafeTableRowSorter is used rather than TableRowSorter because every column in this table is
        //updated live by decoder threads. Removing a channel triggers a full re-sort, and values can
        //change mid-sort, which makes TimSort throw and — left uncaught on the event dispatch thread —
        //freezes the entire GUI while decoding continues in the background. See SafeTableRowSorter.
        SafeTableRowSorter<ChannelMetadataModel> rowSorter =
            new SafeTableRowSorter<>(mChannelProcessingManager.getChannelMetadataModel());
        rowSorter.setComparator(ChannelMetadataModel.COLUMN_CONFIGURATION_CHANNEL, (o1, o2) -> {
            String s1 = o1 != null ? o1.toString() : "";
            String s2 = o2 != null ? o2.toString() : "";
            return s1.compareToIgnoreCase(s2);
        });
        rowSorter.setComparator(ChannelMetadataModel.COLUMN_DECODER_LOGICAL_CHANNEL_NAME, (o1, o2) -> {
            String s1 = o1 != null ? o1.toString() : "";
            String s2 = o2 != null ? o2.toString() : "";
            return s1.compareToIgnoreCase(s2);
        });
        mTable.setRowSorter(rowSorter);

        //Add a table column width monitor to store/restore column widths, order, and sort state
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
     * Resolves the single alias whose Listen setting governs a channel's audio, or null when no
     * single alias can be identified.
     *
     * Channel mute in the Now Playing window is a shortcut to the alias editor's Listen toggle, so
     * it must target exactly the alias the user would have toggled by hand.  Getting this wrong is
     * what broke ap-15.9 and earlier: the old code fell back to every alias in the channel's alias
     * list, so muting one channel wrote DO_NOT_MONITOR across all of them and silenced every other
     * channel sharing that list.
     *
     * Resolution, most specific first:
     *
     * 1. The live TO identifier's alias, when it resolves to exactly one.  On a trunked system this
     *    is the talkgroup actually producing the audio.
     * 2. The live FROM identifier's alias, when it resolves to exactly one.
     * 3. The only alias in the channel's configured alias list, when that list holds exactly one.
     *    This is the conventional-channel case and is unambiguous by definition.
     *
     * Anything else is ambiguous and returns null.  A channel with no live identifiers whose alias
     * list holds several aliases offers no way to tell which one the user means, and guessing is the
     * defect this replaces.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to resolve for
     * @return the governing alias, or null when it cannot be identified unambiguously
     */
    private Alias resolveChannelAlias(ChannelMetadata metadata, Channel channel)
    {
        List<Alias> toAliases = metadata.getToIdentifierAliases();

        if(toAliases != null && toAliases.size() == 1)
        {
            return toAliases.get(0);
        }

        List<Alias> fromAliases = metadata.getFromIdentifierAliases();

        if(fromAliases != null && fromAliases.size() == 1)
        {
            return fromAliases.get(0);
        }

        String aliasListName = channel.getAliasListName();

        if(aliasListName != null && !aliasListName.isEmpty())
        {
            Alias only = null;

            for(Alias alias : mPlaylistManager.getAliasModel().getAliases())
            {
                if(alias.hasList() && aliasListName.equalsIgnoreCase(alias.getAliasListName()))
                {
                    if(only != null)
                    {
                        //More than one alias in the list - ambiguous
                        return null;
                    }

                    only = alias;
                }
            }

            return only;
        }

        return null;
    }

    /**
     * Builds the identity under which a channel's own mute state is stored, for channels that have
     * no alias to carry it.
     *
     * Channel.getChannelID() cannot serve: it is assigned from an incrementing counter at
     * construction, so it changes on every run and every traffic channel receives a fresh one.
     * System, site and name come from the playlist and do not.
     *
     * Traffic channels are created as "T-" plus the parent channel's name, carrying the parent's
     * system and site, so stripping that prefix folds a channel and its traffic channels onto one
     * identity.  The prefix is only stripped from channels of type TRAFFIC, so a conventional
     * channel whose name genuinely begins with "T-" is unaffected.
     *
     * @param channel to identify
     * @return mute identity for the channel, never null
     */
    private static String getChannelMuteKey(Channel channel)
    {
        String name = channel.getName() != null ? channel.getName() : "";

        if(channel.isTrafficChannel() && name.startsWith(TRAFFIC_CHANNEL_NAME_PREFIX))
        {
            name = name.substring(TRAFFIC_CHANNEL_NAME_PREFIX.length());
        }

        String system = channel.getSystem() != null ? channel.getSystem() : "";
        String site = channel.getSite() != null ? channel.getSite() : "";

        return (system + "|" + site + "|" + name).toLowerCase();
    }

    /**
     * Indicates if a channel's audio is muted to the local speakers.
     *
     * Exactly one store is consulted, chosen by whether the channel has an identifiable alias.  When
     * it does, that alias's playback priority is the state - the same state the alias editor's
     * Listen toggle shows - and the per-channel store is not consulted at all.  When it does not,
     * the per-channel store is the state.  Because the two are never both authoritative for one
     * channel, they cannot disagree, which is the defect that broke every earlier version of this.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to check
     * @return true if muted
     */
    private boolean isChannelMuted(ChannelMetadata metadata, Channel channel)
    {
        Alias alias = resolveChannelAlias(metadata, channel);

        if(alias != null && alias.getPlaybackPriority() == Priority.DO_NOT_MONITOR)
        {
            return true;
        }

        //Also consult the per-channel store even when an alias resolved.  A channel can be muted
        //while no alias is identifiable - a trunked channel between calls, for instance - and then
        //acquire one when the next call arrives.  Reading only the alias in that moment would report
        //the channel as unmuted while its audio modules were still silenced, and the menu would
        //offer "Mute" on a channel the user cannot hear.  Reading the union keeps the menu honest;
        //setChannelMuted() clears both stores on unmute, so the state cannot get stuck.
        return mUserPreferences.getNowPlayingPreference().isChannelMuted(getChannelMuteKey(channel));
    }

    /**
     * Mutes or unmutes a channel's audio to the local speakers.
     *
     * Note that this affects local playback only.  Zello and ThinLine streaming do not consult
     * monitor priority at all, so a muted channel continues to stream - which is the intended
     * behaviour here, not an oversight.
     *
     * @param metadata for the channel's live identifiers
     * @param channel to mute or unmute
     * @param mute true to mute
     */
    private void setChannelMuted(ChannelMetadata metadata, Channel channel, boolean mute)
    {
        Alias alias = resolveChannelAlias(metadata, channel);

        if(alias != null)
        {
            //setAliasMuted() also clears any sticky module-level mute on every chain governed by
            //this alias, so the alias priority becomes the only thing gating the audio.
            setAliasMuted(alias, mute);

            //A channel may have been muted while no alias was identifiable and acquired one since -
            //a trunked channel muted between calls, for instance.  Migrate rather than leaving a
            //second copy behind: the alias holds the state now, so discard the per-channel entry.
            mUserPreferences.getNowPlayingPreference().setChannelMuted(getChannelMuteKey(channel), false);
        }
        else
        {
            String muteKey = getChannelMuteKey(channel);
            mUserPreferences.getNowPlayingPreference().setChannelMuted(muteKey, mute);

            for(Map.Entry<Channel,ProcessingChain> entry : mChannelProcessingManager.getProcessingChains().entrySet())
            {
                if(getChannelMuteKey(entry.getKey()).equals(muteKey))
                {
                    setProcessingChainMuted(entry.getValue(), mute);
                }
            }

            mLog.info("Channel [{}] (no alias) {} to local speakers from the Now Playing window",
                    muteKey, mute ? "muted" : "unmuted");
        }
    }

    /**
     * Toggles the Listen setting on the alias governing a channel.  This is the same state the alias
     * editor's Listen toggle writes.
     *
     * A segment's monitor priority is resolved from its aliases when the segment is created, so the
     * priority change alone would not affect a transmission already in progress.  Every running
     * processing chain governed by this same alias therefore has its audio modules cleared of any
     * sticky module-level mute and its current segment ended, which makes the change audible
     * immediately instead of at the end of the current call.
     *
     * @param alias governing the channel, as returned by resolveChannelAlias
     * @param mute true to mute (Listen off), false to unmute
     */
    private void setAliasMuted(Alias alias, boolean mute)
    {
        alias.setCallPriority(mute ? Priority.DO_NOT_MONITOR : Priority.DEFAULT_PRIORITY);
        mPlaylistManager.schedulePlaylistSave();

        //Keeps the alias editor's Listen toggle in step in real time
        MyEventBus.getGlobalEventBus().post(new AliasPriorityChangedEvent(alias));

        for(Map.Entry<Channel,ProcessingChain> entry : mChannelProcessingManager.getProcessingChains().entrySet())
        {
            ChannelMetadata chainMetadata = getFirstMetadata(entry.getKey());

            if(chainMetadata != null && resolveChannelAlias(chainMetadata, entry.getKey()) == alias)
            {
                //Clearing the module mute both releases any sticky mute left from a no-alias period
                //and ends the segment in progress, so the alias priority is the only thing gating
                //this channel's audio from here on.
                setProcessingChainMuted(entry.getValue(), false);
            }
        }

        mLog.info("Alias [{}] in list [{}] {} from the Now Playing window", alias.getName(),
                alias.getAliasListName(), mute ? "muted" : "unmuted");
    }

    /**
     * Applies a module-level mute to every audio module in a processing chain.  Used for channels
     * with no alias, where there is no alias priority to carry the state.  AbstractAudioModule ends
     * the current audio segment on both mute and unmute, so the change takes effect immediately.
     *
     * @param processingChain to apply to, may be null
     * @param mute true to mute
     */
    private static void setProcessingChainMuted(ProcessingChain processingChain, boolean mute)
    {
        if(processingChain == null)
        {
            return;
        }

        for(Module module : processingChain.getModules())
        {
            if(module instanceof AbstractAudioModule)
            {
                ((AbstractAudioModule)module).setMuted(mute);
            }
        }
    }

    /**
     * Returns any one channel metadata for a channel, used to resolve that channel's governing
     * alias while iterating running processing chains.
     *
     * @param channel to look up
     * @return one of the channel's metadata entries, or null if it has none
     */
    private ChannelMetadata getFirstMetadata(Channel channel)
    {
        ChannelMetadataModel model = mChannelProcessingManager.getChannelMetadataModel();

        for(int row = 0; row < model.getRowCount(); row++)
        {
            ChannelMetadata candidate = model.getChannelMetadata(row);

            if(candidate != null && channel.equals(model.getChannelFromMetadata(candidate)))
            {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Attempts to show the tuner serving a channel in the main spectral display (waterfall).
     *
     * Resolution order matters here. A channel's configured preferred tuner is only a request - if
     * that tuner has no spare bandwidth, or the channel's frequency falls outside its current tuned
     * range, the tuner manager silently sources the channel from a different tuner ("Unable to
     * source channel [x] from preferred tuner [y] - searching for another tuner"). Trusting the
     * preferred tuner name in that case displays a tuner that is not carrying the channel, which
     * shows an unrelated noise floor and no channel column at the requested frequency.
     *
     * So the live processing chain's tuner channel source is authoritative whenever the channel is
     * running. The configured preferred tuner is used only as a fallback for a channel that is not
     * currently decoding, and even then only when that tuner's tuned range actually covers the
     * channel frequency.
     *
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
        SourceConfiguration sourceConfig = channel.getSourceConfiguration();
        Tuner tuner = null;
        long channelFrequency = 0;

        //First: the live source. This is the only authoritative answer for a running channel, both
        //for which tuner is carrying it and for which frequency it is actually on - a trunked
        //traffic channel or a multiple-frequency channel is often not on the configured frequency.
        ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);

        if(processingChain != null)
        {
            Source source = processingChain.getSource();

            if(source instanceof TunerChannelSource)
            {
                channelFrequency = ((TunerChannelSource)source).getFrequency();
                tuner = getTunerServingFrequency(discoveredTunerModel, channelFrequency);
            }
        }

        //Fall back to the configured frequency when the channel isn't running.
        if(channelFrequency == 0)
        {
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
        }

        //Second: the configured preferred tuner, accepted only if it can actually show the channel.
        if(tuner == null)
        {
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
                    if(channelFrequency == 0 || isFrequencyWithinTunerBandwidth(discoveredTuner.getTuner(),
                            channelFrequency))
                    {
                        tuner = discoveredTuner.getTuner();
                    }
                    else
                    {
                        mLog.debug("Preferred tuner [" + preferredTunerName + "] is not currently tuned to cover " +
                                "channel frequency [" + channelFrequency + "] - looking for the tuner that is");
                    }
                }
            }
        }

        //Third: any tuner whose current tuned range covers the channel frequency.
        if(tuner == null && channelFrequency > 0)
        {
            tuner = getTunerServingFrequency(discoveredTunerModel, channelFrequency);
        }

        if(tuner != null)
        {
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
            mLog.debug("Unable to identify a tuner currently covering channel [" + channel.getName() +
                    "] frequency [" + channelFrequency + "] - nothing to show in the waterfall");
        }
    }

    /**
     * Identifies the tuner whose current center frequency and sample rate span the specified
     * frequency.
     *
     * @param discoveredTunerModel containing the available tuners
     * @param frequency to locate, in hertz
     * @return the tuner covering the frequency, or null
     */
    private Tuner getTunerServingFrequency(DiscoveredTunerModel discoveredTunerModel, long frequency)
    {
        if(frequency <= 0)
        {
            return null;
        }

        for(DiscoveredTuner discoveredTuner : discoveredTunerModel.getAvailableTuners())
        {
            if(discoveredTuner.hasTuner() && isFrequencyWithinTunerBandwidth(discoveredTuner.getTuner(), frequency))
            {
                return discoveredTuner.getTuner();
            }
        }

        return null;
    }

    /**
     * Indicates if the specified frequency falls within the tuner's currently tuned bandwidth.
     *
     * @param tuner to check
     * @param frequency to test, in hertz
     * @return true if the tuner is presently able to display the frequency
     */
    private boolean isFrequencyWithinTunerBandwidth(Tuner tuner, long frequency)
    {
        try
        {
            long tunerFrequency = tuner.getTunerController().getFrequency();
            double sampleRate = tuner.getTunerController().getSampleRate();
            long halfBandwidth = (long)(sampleRate / 2.0);

            return frequency >= tunerFrequency - halfBandwidth && frequency <= tunerFrequency + halfBandwidth;
        }
        catch(Exception ex)
        {
            mLog.error("Error checking tuner frequency", ex);
            return false;
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

                                // Mute/Unmute menu item. Mutes local speaker audio only; the
                                // channel keeps streaming. When the channel has an identifiable
                                // alias this is a shortcut to that alias's Listen toggle; when it
                                // has none it is a per-channel mute, which is what makes this work
                                // on a bare channel being auditioned before it gets an alias.
                                Alias governingAlias = resolveChannelAlias(metadata, channel);
                                boolean isMuted = isChannelMuted(metadata, channel);
                                String target = governingAlias != null
                                    ? "alias: " + governingAlias.getName()
                                    : "channel: " + channel.getShortTitle();
                                JMenuItem muteItem = new JMenuItem((isMuted ? "Unmute " : "Mute ") + target);
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

            //Re-apply mute for channels that have NO alias.  An alias-backed mute needs nothing
            //here, because AudioSegment resolves monitor priority from its aliases every time a
            //segment is created, so new chains and restarts pick it up unaided.  A channel with no
            //alias has no such mechanism - its mute lives at the audio module, which is new with
            //this processing chain - so it has to be re-applied.
            ChannelMetadata first = getFirstMetadata(channel);

            if(first != null && resolveChannelAlias(first, channel) == null
                && mUserPreferences.getNowPlayingPreference().isChannelMuted(getChannelMuteKey(channel)))
            {
                setProcessingChainMuted(mChannelProcessingManager.getProcessingChain(channel), true);
            }
        }
    }
}
