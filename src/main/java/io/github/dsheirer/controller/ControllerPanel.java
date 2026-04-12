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
package io.github.dsheirer.controller;

import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.audio.playback.AudioPanel;
import io.github.dsheirer.audio.playback.AudioPlaybackManager;
import io.github.dsheirer.channel.metadata.NowPlayingPanel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.ViewPlaylistRequest;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.map.MapPanel;
import io.github.dsheirer.map.MapService;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.ui.TunerViewPanel;
import java.awt.Color;
import java.awt.Dimension;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JPanel;
import javax.swing.JList;
import javax.swing.ListSelectionModel;
import javax.swing.JScrollPane;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.DefaultListModel;
import java.awt.CardLayout;
import java.awt.BorderLayout;


public class ControllerPanel extends JPanel
{
    private final static Logger mLog = LoggerFactory.getLogger(ControllerPanel.class);
    private static final long serialVersionUID = 1L;
    private int mSettingsTabIndex = -1;

    private AudioPanel mAudioPanel;
    private NowPlayingPanel mNowPlayingPanel;
    private MapPanel mMapPanel;
    private TunerViewPanel mTunerManagerPanel;

    private JSplitPane mSplitPane;
    private JPanel mCardPanel;
    private CardLayout mCardLayout;
    private JList<String> mSidebarList;

    public ControllerPanel(PlaylistManager playlistManager, AudioPlaybackManager audioPlaybackManager,
                           IconModel iconModel, MapService mapService, SettingsManager settingsManager,
                           TunerManager tunerManager, UserPreferences userPreferences, boolean detailTabsVisible)
    {
        mAudioPanel = new AudioPanel(iconModel, userPreferences, settingsManager, audioPlaybackManager,
            playlistManager.getAliasModel());
        mNowPlayingPanel = new NowPlayingPanel(playlistManager, iconModel, userPreferences, settingsManager, tunerManager, detailTabsVisible);
        mMapPanel = new MapPanel(mapService, playlistManager.getAliasModel(), iconModel, settingsManager);
        mTunerManagerPanel = new TunerViewPanel(tunerManager, userPreferences);

        init();
    }

    /**
     * Now playing panel.
     */
    public NowPlayingPanel getNowPlayingPanel()
    {
        return mNowPlayingPanel;
    }

    private void init()
    {
        setLayout(new BorderLayout());

        add(mAudioPanel, BorderLayout.NORTH);

        mCardLayout = new CardLayout();
        mCardPanel = new JPanel(mCardLayout);
        
        mCardPanel.add(mNowPlayingPanel, "Monitoring - Now Playing");
        mCardPanel.add(mMapPanel, "Monitoring - Map");
        mCardPanel.add(new JLabel("Playlist Manager (Click link below)"), "Configuration - Playlist Editor");
        
        DefaultListModel<String> listModel = new DefaultListModel<>();
        listModel.addElement("Monitoring - Now Playing");
        listModel.addElement("Monitoring - Map");
        listModel.addElement("Configuration - Playlist Editor");
        
        mSidebarList = new JList<>(listModel);
        mSidebarList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mSidebarList.setSelectedIndex(0);
        
        mSidebarList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = mSidebarList.getSelectedValue();
                if ("Configuration - Playlist Editor".equals(selected)) {
                    MyEventBus.getGlobalEventBus().post(new ViewPlaylistRequest());
                    mSidebarList.setSelectedIndex(0); // revert selection
                } else if (selected != null) {
                    mCardLayout.show(mCardPanel, selected);
                }
            }
        });

        JScrollPane sidebarScroll = new JScrollPane(mSidebarList);
        
        // Tuners panel is persistently visible below the card panel
        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mCardPanel, mTunerManagerPanel);
        centerSplit.setResizeWeight(0.8);
        centerSplit.setDividerLocation(400);

        mSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarScroll, centerSplit);
        mSplitPane.setDividerLocation(200);
        
        add(mSplitPane, BorderLayout.CENTER);
    }
}
