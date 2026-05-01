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

import com.jidesoft.swing.JideTabbedPane;
import io.github.dsheirer.channel.details.ChannelDetailPanel;
import io.github.dsheirer.gui.channel.ChannelSpectrumPanel;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.module.decode.event.DecodeEventPanel;
import io.github.dsheirer.module.decode.event.MessageActivityPanel;
import io.github.dsheirer.gui.widget.Widget;
import io.github.dsheirer.gui.widget.WidgetContainer;
import io.github.dsheirer.preference.NowPlayingPreference;
import io.github.dsheirer.playlist.PlaylistManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.settings.SettingsManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.awt.Color;
import net.miginfocom.swing.MigLayout;

import javax.swing.JPanel;
import java.awt.Cursor;
import java.awt.Insets;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JToolBar;
import javax.swing.JScrollPane;
import javax.swing.JPopupMenu;
import javax.swing.JCheckBoxMenuItem;
import io.github.dsheirer.gui.VisibilityListener;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.module.ProcessingChain;
import java.awt.EventQueue;

import javax.swing.event.ChangeListener;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import javax.swing.JLabel;

/**
 * Swing panel for Now Playing channels table and channel details tab set.
 */
public class NowPlayingPanel extends JPanel implements Listener<ProcessingChain>
{
    private final ChannelMetadataPanel mChannelMetadataPanel;
    private final ChannelDetailPanel mChannelDetailPanel;
    private final DecodeEventPanel mDecodeEventPanel;
    private final MessageActivityPanel mMessageActivityPanel;
    private final ChannelSpectrumPanel mChannelSpectrumSquelchPanel;
    private JideTabbedPane mTabbedPane;
    private javax.swing.JComponent mBroadcastStatusPanel;
    private javax.swing.JComponent mResourceStatusPanel;
    private javax.swing.JComponent mSpectralPanel;

    private VisibilityListener mVisibilityListener;
    private ChangeListener mTabbedPaneChangeListener;
    private WidgetContainer mWidgetContainer;
    private JScrollPane mScrollPane;
    private NowPlayingPreference mNowPlayingPreference;
    private JButton mManageWidgetsBtn;

    /**
     * GUI panel that combines the currently decoding channels metadata table and viewers for channel details,
     * messages, events, and spectral view.
     */
    public NowPlayingPanel(PlaylistManager playlistManager, IconModel iconModel, UserPreferences userPreferences,
                           SettingsManager settingsManager, TunerManager tunerManager, boolean detailTabsVisible, VisibilityListener visibilityListener)
    {
        mVisibilityListener = visibilityListener;
        mChannelDetailPanel = new ChannelDetailPanel(playlistManager.getChannelProcessingManager());
        mDecodeEventPanel = new DecodeEventPanel(iconModel, userPreferences, playlistManager.getAliasModel(), playlistManager.getChannelProcessingManager());
        mMessageActivityPanel = new MessageActivityPanel(userPreferences);
        mChannelMetadataPanel = new ChannelMetadataPanel(playlistManager, iconModel, userPreferences, tunerManager);
        mChannelSpectrumSquelchPanel = new ChannelSpectrumPanel(playlistManager, settingsManager);
        mNowPlayingPreference = new NowPlayingPreference();

        init();
    }

    /**
     * Dispose method to clean up listeners
     */
        @Override
    public void receive(ProcessingChain processingChain) {
        EventQueue.invokeLater(() -> {
            JideTabbedPane pane = getTabbedPane();

            if (processingChain == null) {
                // Remove all tabs except Events
                if (pane.indexOfComponent(mChannelDetailPanel) >= 0) {
                    pane.remove(mChannelDetailPanel);
                }
                if (pane.indexOfComponent(mMessageActivityPanel) >= 0) {
                    pane.remove(mMessageActivityPanel);
                }
                if (pane.indexOfComponent(mChannelSpectrumSquelchPanel) >= 0) {
                    pane.remove(mChannelSpectrumSquelchPanel);
                }
            } else {
                // Restore all tabs in correct order: Details, Events, Messages, Channel
                pane.removeAll();
                pane.addTab("Details", mChannelDetailPanel);
                pane.addTab("Events", mDecodeEventPanel);
                pane.addTab("Messages", mMessageActivityPanel);
                pane.addTab("Channel", mChannelSpectrumSquelchPanel);
            }
        });
    }

    public void dispose()
    {
        if(mTabbedPane != null && mTabbedPaneChangeListener != null)
        {
            mTabbedPane.removeChangeListener(mTabbedPaneChangeListener);
        }
    }

    public void setComponents(javax.swing.JComponent spectralPanel, javax.swing.JComponent broadcastStatusPanel, javax.swing.JComponent resourceStatusPanel) {
        boolean initialized = (mSpectralPanel != null);
        mSpectralPanel = spectralPanel;
        mBroadcastStatusPanel = broadcastStatusPanel;
        mResourceStatusPanel = resourceStatusPanel;

        if (!initialized) {
            setupWidgets();
        } else {
            mWidgetContainer.ensureComponentInWidget("spectrum");
        }
    }





    public void setSpectralPanelVisible(boolean visible) {
        if (mWidgetContainer != null) {
            mWidgetContainer.setWidgetVisible("spectrum", visible);
        }
    }

    public void setResourceStatusPanelVisible(boolean visible) {
        if (mWidgetContainer != null) {
            mWidgetContainer.setWidgetVisible("resource", visible);
        }
    }

    public void setBroadcastStatusPanelVisible(boolean visible) {
        if (mWidgetContainer != null) {
            mWidgetContainer.setWidgetVisible("streaming", visible);
        }
    }

    public void setDetailTabsVisible(boolean visible) {
        if (mWidgetContainer != null) {
            mWidgetContainer.setWidgetVisible("details", visible);
        }
    }

    public void setBroadcastStatusPanel(javax.swing.JComponent panel) {
        // Kept for backward compatibility if called elsewhere, actual injection happens in setComponents
        mBroadcastStatusPanel = panel;
    }

    private JideTabbedPane getTabbedPane()
    {
        if(mTabbedPane == null)
        {
            mTabbedPane = new JideTabbedPane();
            mTabbedPane.addTab("Details", mChannelDetailPanel);
            mTabbedPane.addTab("Events", mDecodeEventPanel);
            mTabbedPane.addTab("Messages", mMessageActivityPanel);
            mTabbedPane.addTab("Channel", mChannelSpectrumSquelchPanel);
            mTabbedPane.setFont(this.getFont());
            mTabbedPane.setForeground(Color.BLACK);
            //Register state change listener to toggle visibility state for channel tab to turn-on/off FFT processing
            mTabbedPaneChangeListener = e -> mChannelSpectrumSquelchPanel.setPanelVisible(getTabbedPane().getSelectedIndex() == getTabbedPane()
                    .indexOfComponent(mChannelSpectrumSquelchPanel));
            mTabbedPane.addChangeListener(mTabbedPaneChangeListener);
        }

        return mTabbedPane;
    }

    public JButton getManageWidgetsButton() {
        if (mManageWidgetsBtn == null) {
            mManageWidgetsBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.COG, 14, Color.BLACK));
            mManageWidgetsBtn.setToolTipText("Manage Widgets");
            mManageWidgetsBtn.getAccessibleContext().setAccessibleName("Manage Widgets");
            mManageWidgetsBtn.getAccessibleContext().setAccessibleDescription("Opens a menu to manage visible widgets");
            mManageWidgetsBtn.addActionListener(e -> showManageWidgetsPopup(mManageWidgetsBtn));
            mManageWidgetsBtn.setFocusPainted(false);
            mManageWidgetsBtn.setContentAreaFilled(false);
            mManageWidgetsBtn.setBorderPainted(false);
            mManageWidgetsBtn.setMargin(new Insets(0, 4, 0, 4));
            mManageWidgetsBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        return mManageWidgetsBtn;
    }

    private void init()
    {
        setLayout( new MigLayout( "insets 0 0 0 0", "[grow,fill]", "[grow,fill][]") );

        mChannelMetadataPanel.addProcessingChainSelectionListener(mChannelDetailPanel);
        mChannelMetadataPanel.addProcessingChainSelectionListener(mDecodeEventPanel);
        mChannelMetadataPanel.addProcessingChainSelectionListener(mMessageActivityPanel);
        mChannelMetadataPanel.addProcessingChainSelectionListener(mChannelSpectrumSquelchPanel);
        mChannelMetadataPanel.addProcessingChainSelectionListener(this);

        mWidgetContainer = new WidgetContainer(mNowPlayingPreference);
        mScrollPane = new JScrollPane(mWidgetContainer);
        mScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        mScrollPane.setBorder(null);
        mScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        mScrollPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                mWidgetContainer.revalidate();
            }
        });
        add(mScrollPane, "grow, wrap, w 100%");
    }

    private void setupWidgets() {
        mWidgetContainer.removeAll();

        if (mSpectralPanel != null) {
            Widget spectrumWidget = new Widget("spectrum", "Spectrum/Waterfall", mSpectralPanel, mWidgetContainer, 150);
            mWidgetContainer.addWidget(spectrumWidget, false);
        }

        if (mChannelMetadataPanel != null) {
            Widget channelTableWidget = new Widget("channel_table", "Channel Table", mChannelMetadataPanel, mWidgetContainer, 150);
            mWidgetContainer.addWidget(channelTableWidget, false);
        }

        if (getTabbedPane() != null) {
            Widget detailsWidget = new Widget("details", "Channel Details", getTabbedPane(), mWidgetContainer, 200);
            mWidgetContainer.addWidget(detailsWidget, false);
        }

        if (mBroadcastStatusPanel != null) {
            Widget streamingWidget = new Widget("streaming", "Streaming Status", mBroadcastStatusPanel, mWidgetContainer, 70);
            mWidgetContainer.addWidget(streamingWidget, false);
        }

        if (mResourceStatusPanel != null) {
            Widget resourceWidget = new Widget("resource", "Resource Status", mResourceStatusPanel, mWidgetContainer, 30);
            resourceWidget.setMinimizeButtonVisible(false);
            mWidgetContainer.addWidget(resourceWidget, true); // Pinned to bottom
        }

        mWidgetContainer.layoutWidgets("resource");
    }

    private void showManageWidgetsPopup(JButton source) {
        JPopupMenu popup = new JPopupMenu();

        if (mSpectralPanel != null) addPopupItem(popup, "Spectrum/Waterfall", "spectrum");
        if (mChannelMetadataPanel != null) addPopupItem(popup, "Channel Table", "channel_table");
        if (getTabbedPane() != null) addPopupItem(popup, "Channel Details", "details");
        if (mBroadcastStatusPanel != null) addPopupItem(popup, "Streaming Status", "streaming");
        if (mResourceStatusPanel != null) addPopupItem(popup, "Resource Status", "resource");

        popup.show(source, 0, source.getHeight());
    }

    private void addPopupItem(JPopupMenu popup, String label, String widgetId) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(label);
        item.setSelected(mNowPlayingPreference.isWidgetVisible(widgetId, true));
        item.addActionListener(e -> mWidgetContainer.setWidgetVisible(widgetId, item.isSelected()));
        popup.add(item);
    }
}
