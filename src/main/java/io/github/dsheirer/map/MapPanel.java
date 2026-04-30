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
package io.github.dsheirer.map;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.icon.IconModel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.settings.MapViewSetting;
import io.github.dsheirer.settings.SettingsManager;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import net.miginfocom.swing.MigLayout;
import java.awt.BorderLayout;
import org.jdesktop.swingx.JXMapViewer;
import org.jdesktop.swingx.OSMTileFactoryInfo;
import org.jdesktop.swingx.input.PanKeyListener;
import org.jdesktop.swingx.input.ZoomMouseWheelListenerCursor;
import org.jdesktop.swingx.mapviewer.DefaultTileFactory;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.jdesktop.swingx.mapviewer.TileFactoryInfo;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * Swing map panel.
 */
public class MapPanel extends JPanel implements IPlottableUpdateListener
{
    private static final long serialVersionUID = 1L;

    private static final int ZOOM_MINIMUM = 1;
    private static final int ZOOM_MAXIMUM = 16;

    private static final String FOLLOW = "Follow";
    private static final String UNFOLLOW = "Unfollow";
    private static final String SELECT_A_TRACK = "(select a track)";
    private static final String NO_SYSTEM_NAME = "(no system name)";
    private SettingsManager mSettingsManager;
    private MapService mMapService;
    private JXMapViewer mMapViewer;
    private PlottableEntityPainter mMapPainter;
    private TrackGenerator mTrackGenerator;
    private JToggleButton mTrackGeneratorToggle;
    private JTable mPlottedTracksTable;
    private JButton mClearMapButton;
    private JButton mReplotAllTracksButton;
    private JButton mDeleteAllTracksButton;
    private JButton mDeleteTrackButton;
    private JButton mFollowButton;
    private JLabel mFollowedEntityLabel;
    private JCheckBox mCenterOnSelectedCheckBox;
    private PlottableEntityHistory mFollowedTrack;
    private JComboBox<Integer> mTrackHistoryLengthComboBox;
    private JTable mTrackHistoryTable;
    private JLabel mSelectedTrackSystemLabel;
    private final TrackHistoryModel EMPTY_HISTORY = new TrackHistoryModel();

    /**
     * Constructs an instance
     * @param mapService for accessing entities to plot
     * @param aliasModel for alias lookup
     * @param iconModel for icon lookup
     * @param settingsManager for user specified options/settings.
     */
    public MapPanel(MapService mapService, AliasModel aliasModel, IconModel iconModel, SettingsManager settingsManager)
    {
        mSettingsManager = settingsManager;
        mMapService = mapService;
        mMapPainter = new PlottableEntityPainter(aliasModel, iconModel);

        init();
    }

    private void init()
    {
        setLayout(new BorderLayout());
        mMapService.addListener(this);

        // Sidebar (Master-Detail)
        JPanel sidebar = new JPanel(new MigLayout("insets 0, gap 0", "[grow,fill]", "[][grow,fill][][grow,fill][]"));
        sidebar.setBackground(new java.awt.Color(242, 242, 247)); // Apple grouped background

        JLabel header1 = new JLabel(" Plotted Tracks");
        header1.setFont(header1.getFont().deriveFont(java.awt.Font.BOLD, 11f));
        header1.setForeground(new java.awt.Color(142, 142, 147));
        sidebar.add(header1, "wrap, pad 10 5 5 5");

        JTable tracksTable = getPlottedTracksTable();
        tracksTable.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        JScrollPane tracksScroll = new JScrollPane(tracksTable);
        tracksScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        sidebar.add(tracksScroll, "wrap");

        JPanel detailPanel = new JPanel(new MigLayout("insets 10, gap 5", "[grow,fill]", "[][][grow,fill][]"));
        detailPanel.setBackground(java.awt.Color.WHITE);
        detailPanel.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 0, 0, 0, new java.awt.Color(200, 200, 200)));

        detailPanel.add(new JLabel("Selected System:"), "split 2");
        detailPanel.add(getSelectedTrackSystemLabel(), "wrap");

        JLabel header2 = new JLabel("Track History");
        header2.setFont(header2.getFont().deriveFont(java.awt.Font.BOLD, 11f));
        header2.setForeground(new java.awt.Color(142, 142, 147));
        detailPanel.add(header2, "wrap");

        JTable historyTable = getTrackHistoryTable();
        historyTable.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        JScrollPane historyScroll = new JScrollPane(historyTable);
        historyScroll.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(230, 230, 230)));
        detailPanel.add(historyScroll, "wrap");

        JPanel settingsPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]10[]", ""));
        settingsPanel.setOpaque(false);
        settingsPanel.add(new JLabel("History Length:"));
        settingsPanel.add(getTrackHistoryLengthComboBox(), "wrap");
        settingsPanel.add(getCenterOnSelectedCheckBox(), "wrap");
        settingsPanel.add(getTrackGeneratorToggle(), "span 2");
        detailPanel.add(settingsPanel);

        sidebar.add(detailPanel, "growx");

        // Map Area
        JXMapViewer map = getMapViewer();
        map.setLayout(new MigLayout("insets 20", "[grow,right]", "[grow,bottom]"));

        JPanel floatingControls = new JPanel(new MigLayout("insets 8, gap 8", "[center]", "[][][][][]")) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new java.awt.Color(255, 255, 255, 220)); // Translucent white
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.setColor(new java.awt.Color(0, 0, 0, 40)); // Subtle shadow/border
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        floatingControls.setOpaque(false);

        JButton btnZoomIn = createFloatingButton(jiconfont.icons.font_awesome.FontAwesome.PLUS, "Zoom In");
        btnZoomIn.addActionListener(e -> adjustZoom(-1));

        JButton btnZoomOut = createFloatingButton(jiconfont.icons.font_awesome.FontAwesome.MINUS, "Zoom Out");
        btnZoomOut.addActionListener(e -> adjustZoom(1));

        JButton btnFollow = createFloatingButton(jiconfont.icons.font_awesome.FontAwesome.LOCATION_ARROW, "Follow Selected");
        btnFollow.addActionListener(e -> {
            if(mFollowedTrack == null) {
                follow(getSelected());
            } else {
                follow(null);
            }
        });

        JButton btnOptions = createFloatingButton(jiconfont.icons.font_awesome.FontAwesome.COG, "Map Options");
        javax.swing.JPopupMenu optionsMenu = new javax.swing.JPopupMenu();

        javax.swing.JMenuItem clearItem = new javax.swing.JMenuItem("Clear Map");
        clearItem.addActionListener(e -> getClearMapButton().doClick());

        javax.swing.JMenuItem deleteItem = new javax.swing.JMenuItem("Delete Selected");
        deleteItem.addActionListener(e -> getDeleteTrackButton().doClick());

        javax.swing.JMenuItem deleteAllItem = new javax.swing.JMenuItem("Delete All");
        deleteAllItem.addActionListener(e -> getDeleteAllTracksButton().doClick());

        javax.swing.JMenuItem replotItem = new javax.swing.JMenuItem("Replot All");
        replotItem.addActionListener(e -> getReplotAllTracksButton().doClick());

        optionsMenu.add(clearItem);
        optionsMenu.add(deleteItem);
        optionsMenu.add(deleteAllItem);
        optionsMenu.addSeparator();
        optionsMenu.add(replotItem);

        btnOptions.addActionListener(e -> optionsMenu.show(btnOptions, 0, btnOptions.getHeight()));

        floatingControls.add(btnZoomIn, "wrap");
        floatingControls.add(btnZoomOut, "wrap");

        javax.swing.JSeparator sep = new javax.swing.JSeparator();
        sep.setForeground(new java.awt.Color(200, 200, 200));
        floatingControls.add(sep, "growx, wrap");

        floatingControls.add(btnFollow, "wrap");
        floatingControls.add(btnOptions, "wrap");

        map.add(floatingControls, "");

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(300);
        splitPane.setLeftComponent(sidebar);
        splitPane.setRightComponent(map);
        splitPane.setBorder(javax.swing.BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        // Hide legacy UI components but keep them initialized for background state changes
        getFollowButton();
        getFollowedEntityLabel();
        getDeleteTrackButton();
    }

    private JButton createFloatingButton(jiconfont.icons.font_awesome.FontAwesome icon, String tooltip) {
        JButton btn = new JButton(jiconfont.swing.IconFontSwing.buildIcon(icon, 16, new java.awt.Color(0, 122, 255)));
        btn.setToolTipText(tooltip);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
        return btn;
    }



        private void setSelected(PlottableEntityHistory selected)
    {
        if(selected != null)
        {
            getTrackHistoryTable().setModel(selected.getTrackHistoryModel());

            Identifier system = selected.getIdentifierCollection().getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);

            if(system != null)
            {
                getSelectedTrackSystemLabel().setText(system.toString());
            }
            else
            {
                getSelectedTrackSystemLabel().setText(NO_SYSTEM_NAME);
            }

            if(mMapPainter.addEntity(getSelected()))
            {
                getMapViewer().repaint();
            }
        }
        else
        {
            getTrackHistoryTable().setModel(EMPTY_HISTORY);
            getSelectedTrackSystemLabel().setText(SELECT_A_TRACK);
        }

        if(getCenterOnSelectedCheckBox().isSelected())
        {
            centerOn(selected);
        }
    }

    /**
     * Replots all tracks to the map (after a clear operation).
     * @return button
     */
    private JButton getReplotAllTracksButton()
    {
        if(mReplotAllTracksButton == null)
        {
            mReplotAllTracksButton = new JButton("Replot All");
            mReplotAllTracksButton.addActionListener(e ->
            {
                boolean added = mMapPainter.addAll(mMapService.getPlottableEntityModel().getAll());

                if(added)
                {
                    getMapViewer().repaint();
                }
            });
        }

        return mReplotAllTracksButton;
    }

    private JLabel getSelectedTrackSystemLabel()
    {
        if(mSelectedTrackSystemLabel == null)
        {
            mSelectedTrackSystemLabel = new JLabel(SELECT_A_TRACK);
        }

        return mSelectedTrackSystemLabel;
    }

    private JTable getTrackHistoryTable()
    {
        if(mTrackHistoryTable == null)
        {
            mTrackHistoryTable = new JTable(EMPTY_HISTORY);
            mTrackHistoryTable.setFillsViewportHeight(true);
            mTrackHistoryTable.getSelectionModel().addListSelectionListener(e ->
            {
                if(getCenterOnSelectedCheckBox().isSelected())
                {
                    int modelIndex = getTrackHistoryTable().convertRowIndexToModel(getTrackHistoryTable().getSelectedRow());
                    TimestampedGeoPosition geo = ((TrackHistoryModel)(getTrackHistoryTable().getModel())).get(modelIndex);

                    if(geo != null)
                    {
                        mMapViewer.setCenterPosition(geo);
                    }
                }
            });
        }

        return mTrackHistoryTable;
    }

    /**
     * Plotted track history trail length selection combo box.
     */
    private JComboBox<Integer> getTrackHistoryLengthComboBox()
    {
        if(mTrackHistoryLengthComboBox == null)
        {
            List<Integer> lengths = new ArrayList<>();
            for(int length = 1; length <= 10; length++)
            {
                lengths.add(length);
            }

            mTrackHistoryLengthComboBox = new JComboBox<>(lengths.toArray(new Integer[]{lengths.size()}));
            mTrackHistoryLengthComboBox.setSelectedItem(mMapPainter.getTrackHistoryLength());

            mTrackHistoryLengthComboBox.addActionListener(e ->
            {
                int length = (int)getTrackHistoryLengthComboBox().getSelectedItem();
                mMapPainter.setTrackHistoryLength(length);
            });
        }

        return mTrackHistoryLengthComboBox;
    }

    /**
     * Label to show the followed entity
     */
    private JLabel getFollowedEntityLabel()
    {
        if(mFollowedEntityLabel == null)
        {
            mFollowedEntityLabel = new JLabel(" ");
        }

        return mFollowedEntityLabel;
    }

    /**
     * Toggles the following state for an entity.
     */
    private JButton getFollowButton()
    {
        if(mFollowButton == null)
        {
            mFollowButton = new JButton(FOLLOW);
            mFollowButton.setEnabled(false);
            mFollowButton.addActionListener(e ->
            {
                if(getFollowButton().getText().equals(FOLLOW))
                {
                    follow(getSelected());
                }
                else
                {
                    follow(null);
                }
            });
        }

        return mFollowButton;
    }

    private JButton getClearMapButton()
    {
        if(mClearMapButton == null)
        {
            mClearMapButton = new JButton("Clear Map");
            mClearMapButton.addActionListener(e ->
            {
                mMapPainter.clearAllEntities();
                repaint();
            });
        }

        return mClearMapButton;
    }

    /**
     * Toggles the behavior of centering on the selected track when a user selects a track in the table.
     * @return check box.
     */
    private JToggleButton getCenterOnSelectedCheckBox()
    {
        if(mCenterOnSelectedCheckBox == null)
        {
            mCenterOnSelectedCheckBox = new JCheckBox("Center on Selection");
            mCenterOnSelectedCheckBox.setSelected(true);
        }

        return mCenterOnSelectedCheckBox;
    }

    private JButton getDeleteAllTracksButton()
    {
        if(mDeleteAllTracksButton == null)
        {
            mDeleteAllTracksButton = new JButton("Delete All");
            mDeleteAllTracksButton.addActionListener(e -> {
                mMapService.getPlottableEntityModel().deleteAllTracks();
                mMapPainter.clearAllEntities();
                //Clear followed entity
                follow(null);
                getMapViewer().repaint();
            });
        }

        return mDeleteAllTracksButton;
    }

    private JButton getDeleteTrackButton()
    {
        if(mDeleteTrackButton == null)
        {
            mDeleteTrackButton = new JButton("Delete");
            mDeleteTrackButton.setEnabled(false);
            mDeleteTrackButton.addActionListener(e -> {

                List<PlottableEntityHistory> toDelete = new ArrayList<>();
                int[] selectedIndices = getPlottedTracksTable().getSelectionModel().getSelectedIndices();

                for(int selectedIndex : selectedIndices)
                {
                    int modelIndex = getPlottedTracksTable().convertRowIndexToModel(selectedIndex);
                    PlottableEntityHistory entity = mMapService.getPlottableEntityModel().get(modelIndex);
                    if(entity != null)
                    {
                        toDelete.add(entity);

                        //Clear followed entity if it's being deleted
                        if(entity.equals(mFollowedTrack))
                        {
                            follow(null);
                        }
                    }
                }
                mMapService.getPlottableEntityModel().delete(toDelete);
                mMapPainter.clearEntities(toDelete);
                getMapViewer().repaint();
            });
        }

        return mDeleteTrackButton;
    }

    /**
     * Access the selected entity history.
     * @return selected entity or null of one is not selected.
     */
    private PlottableEntityHistory getSelected()
    {
        if(getPlottedTracksTable().getSelectedRow() >= 0)
        {
            int modelIndex = getPlottedTracksTable().convertRowIndexToModel(getPlottedTracksTable().getSelectedRow());
            return mMapService.getPlottableEntityModel().get(modelIndex);
        }

        return null;
    }

    /**
     * Centers on the plottable entity.
     * @param entityHistory to center on
     */
    private void centerOn(PlottableEntityHistory entityHistory)
    {
        if(entityHistory != null)
        {
            GeoPosition geoPosition = entityHistory.getLatestPosition();

            if(geoPosition != null)
            {
                mMapViewer.setCenterPosition(geoPosition);
            }
        }
    }

    /**
     * Follow or unfollow an entity.
     * @param entityHistory to follow or null to unfollow.
     */
    private void follow(PlottableEntityHistory entityHistory)
    {
        mFollowedTrack = entityHistory;

        if(mFollowedTrack != null)
        {
            centerOn(mFollowedTrack);
            getFollowButton().setText(UNFOLLOW);
            getFollowButton().setEnabled(true);
            getFollowedEntityLabel().setText("Following: " + mFollowedTrack.getIdentifier());
            getCenterOnSelectedCheckBox().setEnabled(false); //Disabled while we're following
        }
        else
        {
            getFollowButton().setText(FOLLOW);
            getFollowButton().setEnabled(getSelected() != null);
            getFollowedEntityLabel().setText(null);
            getCenterOnSelectedCheckBox().setEnabled(true);
        }
    }

    private JTable getPlottedTracksTable()
    {
        if(mPlottedTracksTable == null)
        {
            mPlottedTracksTable = new JTable(mMapService.getPlottableEntityModel());
            mPlottedTracksTable.setFillsViewportHeight(true);
            mPlottedTracksTable.setAutoCreateRowSorter(true);

            //Register selection listener to update button/label states
            mPlottedTracksTable.getSelectionModel().addListSelectionListener(e ->
            {
                //Toggle the enabled state of the delete (single) track button
                int count = mPlottedTracksTable.getSelectionModel().getSelectedItemsCount();
                getDeleteTrackButton().setEnabled(count > 0);

                PlottableEntityHistory selected = getSelected();
                setSelected(selected);

                //Refresh the followed entity button/label states
                follow(mFollowedTrack);
            });
        }

        return mPlottedTracksTable;
    }

    private JToggleButton getTrackGeneratorToggle()
    {
        if(mTrackGeneratorToggle == null)
        {
            mTrackGeneratorToggle = new JToggleButton("Track Generator");
            mTrackGeneratorToggle.addActionListener(e -> {
                if(mTrackGeneratorToggle.isSelected())
                {
                    getTrackGenerator().start();
                }
                else
                {
                    getTrackGenerator().stop();
                }
            });
        }

        return mTrackGeneratorToggle;
    }

    /**
     * Optional test track generator
     */
    private TrackGenerator getTrackGenerator()
    {
        if(mTrackGenerator == null)
        {
            mTrackGenerator = new TrackGenerator(mMapService);
        }

        return mTrackGenerator;
    }

    public JXMapViewer getMapViewer()
    {
        if(mMapViewer == null)
        {
            mMapViewer = new JXMapViewer();

            /**
             * Set the entity painter as the overlay painter and register this panel to receive new messages (plots)
             */
            mMapViewer.setOverlayPainter(mMapPainter);

            /**
             * Map image source
             */
            TileFactoryInfo info = new OSMTileFactoryInfo();
            DefaultTileFactory tileFactory = new DefaultTileFactory(info);
            mMapViewer.setTileFactory(tileFactory);

            /**
             * Defines how many threads will be used to fetch the background map tiles (graphics)
             */
            tileFactory.setThreadPoolSize(8);

            /**
             * Set initial location and zoom for the map upon display
             */
            GeoPosition syracuse = new GeoPosition(43.048, -76.147);
            int zoom = 7;

            MapViewSetting view = mSettingsManager.getSettingsModel().getMapViewSetting("Default", syracuse, zoom);

            mMapViewer.setAddressLocation(view.getGeoPosition());
            mMapViewer.setZoom(view.getZoom());

            /**
             * Add a mouse adapter for panning and scrolling
             */
            MapMouseListener listener = new MapMouseListener(mMapViewer, mSettingsManager);
            mMapViewer.addMouseListener(listener);
            mMapViewer.addMouseMotionListener(listener);

            /* Map zoom listener */
            mMapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(this));

            /* Keyboard panning listener */
            mMapViewer.addKeyListener(new PanKeyListener(mMapViewer));

            /**
             * Add a selection listener
             */
            SelectionAdapter sa = new SelectionAdapter(mMapViewer);
            mMapViewer.addMouseListener(sa);
            mMapViewer.addMouseMotionListener(sa);
        }

        return mMapViewer;
    }

    /**
     * Changes the zoom level by the specified value.
     * @param adjustment zoom value.
     */
    public void adjustZoom(int adjustment)
    {
        int currentZoom = getMapViewer().getZoom();
        int updatedZoom = currentZoom + adjustment;

        if(ZOOM_MINIMUM <= updatedZoom && updatedZoom <= ZOOM_MAXIMUM)
        {
            getMapViewer().setZoom(updatedZoom);
        }
    }

    @Override
    public void entitiesUpdated()
    {
        EventQueue.invokeLater(() -> mMapViewer.repaint());
    }

    @Override
    public void addPlottableEntity(PlottableEntityHistory entity)
    {
        mMapPainter.addEntity(entity);
        entitiesUpdated();
    }

    @Override
    public void removePlottableEntity(PlottableEntityHistory entity)
    {
        mMapPainter.removeEntity(entity);
        entitiesUpdated();
    }
}
