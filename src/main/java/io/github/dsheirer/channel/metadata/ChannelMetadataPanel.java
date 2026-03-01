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

package io.github.dsheirer.channel.metadata;

import com.google.common.base.Joiner;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.priority.Priority;
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
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.identifier.TalkgroupFormatPreference;
import io.github.dsheirer.preference.swing.JTableColumnWidthMonitor;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerEvent;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
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
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.util.Comparator;

public class ChannelMetadataPanel extends JPanel implements ListSelectionListener
{
    private final static Logger mLog = LoggerFactory.getLogger(ChannelMetadataPanel.class);

    private static final String TABLE_PREFERENCE_KEY = "channel.metadata.panel";
    private ChannelModel mChannelModel;
    private ChannelProcessingManager mChannelProcessingManager;
    private PlaylistManager mPlaylistManager;
    private IconModel mIconModel;
    private UserPreferences mUserPreferences;
    private DiscoveredTunerModel mDiscoveredTunerModel;
    private JTable mTable;
    private Broadcaster<ProcessingChain> mSelectedProcessingChainBroadcaster = new Broadcaster<>();
    private Map<State,Color> mBackgroundColors = new EnumMap<>(State.class);
    private Map<State,Color> mForegroundColors = new EnumMap<>(State.class);
    private JTableColumnWidthMonitor mTableColumnMonitor;
    private Channel mUserSelectedChannel;

    /**
     * Table view for currently decoding channel metadata (backwards-compatible constructor).
     * Show in Waterfall feature will not be available without DiscoveredTunerModel.
     */
    public ChannelMetadataPanel(PlaylistManager playlistManager, IconModel iconModel, UserPreferences userPreferences)
    {
        this(playlistManager, iconModel, userPreferences, null);
    }

    /**
     * Table view for currently decoding channel metadata
     *
     * @param playlistManager for channel model, processing manager, and playlist save
     * @param iconModel for alias icons
     * @param userPreferences for column width persistence and talkgroup formatting
     * @param discoveredTunerModel for Show in Waterfall tuner lookup (nullable)
     */
    public ChannelMetadataPanel(PlaylistManager playlistManager, IconModel iconModel,
                                UserPreferences userPreferences, DiscoveredTunerModel discoveredTunerModel)
    {
        mPlaylistManager = playlistManager;
        mChannelModel = playlistManager.getChannelModel();
        mChannelProcessingManager = playlistManager.getChannelProcessingManager();
        mIconModel = iconModel;
        mUserPreferences = userPreferences;
        mDiscoveredTunerModel = discoveredTunerModel;
        init();
    }

    /**
     * Sets the DiscoveredTunerModel for Show in Waterfall feature.
     * Can be called after construction if the model is not available at construction time.
     */
    public void setDiscoveredTunerModel(DiscoveredTunerModel discoveredTunerModel)
    {
        mDiscoveredTunerModel = discoveredTunerModel;
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

        //Enable column sorting via click on column headers
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(mTable.getModel());
        Comparator<Object> toStringComparator = (o1, o2) -> {
            String s1 = o1 != null ? o1.toString() : "";
            String s2 = o2 != null ? o2.toString() : "";
            return s1.compareToIgnoreCase(s2);
        };
        for(int i = 0; i < mTable.getColumnCount(); i++)
        {
            sorter.setComparator(i, toStringComparator);
        }
        mTable.setRowSorter(sorter);

        //Add column layout monitor AFTER sorter is set, so it can attach sort listener
        //and so sorter setup doesn't trigger save of default widths
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
     * Finds the tuner that is currently serving the given frequency by checking all available
     * tuners in the discovered tuner model.
     *
     * @param frequency in Hz to find the serving tuner for
     * @return the Tuner serving that frequency, or null if not found
     */
    private Tuner findTunerForFrequency(long frequency)
    {
        if(mDiscoveredTunerModel != null)
        {
            for(DiscoveredTuner discoveredTuner : mDiscoveredTunerModel.getAvailableTuners())
            {
                if(discoveredTuner.hasTuner())
                {
                    Tuner tuner = discoveredTuner.getTuner();

                    try
                    {
                        long center = tuner.getTunerController().getFrequency();
                        int sampleRate = (int)tuner.getTunerController().getSampleRate();
                        long halfBand = sampleRate / 2;

                        if(frequency >= (center - halfBand) && frequency <= (center + halfBand))
                        {
                            return tuner;
                        }
                    }
                    catch(Exception e)
                    {
                        mLog.debug("Error checking tuner frequency range", e);
                    }
                }
            }
        }

        return null;
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
                                //View/Edit channel
                                JMenuItem viewChannel = new JMenuItem("View/Edit: " + channel.getShortTitle());
                                viewChannel.addActionListener(e2 -> MyEventBus.getGlobalEventBus().post(new ViewChannelRequest(channel)));
                                popupMenu.add(viewChannel);
                                populated = true;

                                //Show in Waterfall - find serving tuner and switch spectral display
                                FrequencyConfigurationIdentifier freqId = metadata.getFrequencyConfigurationIdentifier();

                                if(freqId != null && mDiscoveredTunerModel != null)
                                {
                                    long channelFrequency = freqId.getValue();

                                    if(channelFrequency > 0)
                                    {
                                        JMenuItem showInWaterfall = new JMenuItem("Show in Waterfall");
                                        showInWaterfall.addActionListener(e2 ->
                                        {
                                            mLog.info("Show in Waterfall: looking for tuner at frequency " + channelFrequency + " Hz");
                                            Tuner tuner = findTunerForFrequency(channelFrequency);

                                            if(tuner != null)
                                            {
                                                mLog.info("Show in Waterfall: found tuner [" + tuner + "], broadcasting REQUEST_MAIN_SPECTRAL_DISPLAY with zoom to " + channelFrequency + " Hz");
                                                mDiscoveredTunerModel.broadcast(
                                                    new TunerEvent(tuner, TunerEvent.Event.REQUEST_MAIN_SPECTRAL_DISPLAY, channelFrequency));
                                            }
                                            else
                                            {
                                                mLog.warn("Show in Waterfall: no tuner found for frequency " + channelFrequency + " Hz");
                                            }
                                        });
                                        popupMenu.add(showInWaterfall);
                                    }
                                }

                                //Mute/Unmute
                                popupMenu.addSeparator();

                                //Find the alias to use: prefer active TO alias, fall back to channel's alias list
                                Alias muteAlias = null;
                                List<Alias> toAliases = metadata.getToIdentifierAliases();

                                if(toAliases != null && !toAliases.isEmpty())
                                {
                                    muteAlias = toAliases.get(0);
                                }
                                else
                                {
                                    //No active call - look up alias from channel's configured alias list
                                    String aliasListName = channel.getAliasListName();

                                    if(aliasListName != null && !aliasListName.isEmpty())
                                    {
                                        for(Alias candidate : mPlaylistManager.getAliasModel().getAliases())
                                        {
                                            if(aliasListName.equals(candidate.getAliasListName()))
                                            {
                                                muteAlias = candidate;
                                                break;
                                            }
                                        }
                                    }
                                }

                                //Build mute menu item
                                final Alias alias = muteAlias;
                                boolean channelMuted = channel.isMuted();
                                boolean aliasMuted = (alias != null && alias.getPlaybackPriority() == Priority.DO_NOT_MONITOR);
                                boolean isMuted = channelMuted || aliasMuted;

                                String muteLabel;
                                if(alias != null)
                                {
                                    muteLabel = (isMuted ? "Unmute: " : "Mute: ") + alias.getName();
                                }
                                else
                                {
                                    muteLabel = isMuted ? "Unmute" : "Mute";
                                }

                                JMenuItem muteItem = new JMenuItem(muteLabel);
                                muteItem.addActionListener(e2 ->
                                {
                                    boolean newMuteState = !isMuted;

                                    //Update alias priority if available
                                    if(alias != null)
                                    {
                                        if(isMuted)
                                        {
                                            alias.setCallPriority(Priority.DEFAULT_PRIORITY);
                                        }
                                        else
                                        {
                                            alias.setCallPriority(Priority.DO_NOT_MONITOR);
                                        }
                                    }

                                    //Set channel-level mute flag
                                    channel.setMuted(newMuteState);

                                    //Direct path: immediately update audio modules in the processing chain
                                    ProcessingChain processingChain = mChannelProcessingManager.getProcessingChain(channel);
                                    if(processingChain != null)
                                    {
                                        int audioModuleCount = 0;
                                        for(Module module : processingChain.getModules())
                                        {
                                            if(module instanceof AbstractAudioModule)
                                            {
                                                ((AbstractAudioModule)module).setMuted(newMuteState);
                                                audioModuleCount++;
                                            }
                                        }
                                    }

                                    mPlaylistManager.schedulePlaylistSave();
                                });
                                popupMenu.add(muteItem);
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
            if(mUserSelectedChannel != null &&
               mUserSelectedChannel.getChannelID() == channelAndMetadata.getChannel().getChannelID())
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
        }
    }
}
