package io.github.dsheirer.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class SidebarPanel extends JPanel {
    private boolean mCollapsed = false;
    private SidebarListener mListener;

    private final Color BG_COLOR = new Color(30, 30, 30);
    private final Color HOVER_COLOR = new Color(50, 50, 50);
    private final Color ACTIVE_COLOR = new Color(70, 70, 70);
    private final Color TEXT_COLOR = Color.WHITE;

    private List<SidebarItem> mItems = new ArrayList<>();

    public interface SidebarListener {
        void onItemSelected(String id);
        void onActionRequested(String actionId);
    }

    public SidebarPanel(SidebarListener listener) {
        mListener = listener;
        setBackground(BG_COLOR);
        setPreferredSize(new Dimension(250, 0));
        setLayout(new MigLayout("insets 10 5 10 5, gapy 5, wrap 1, fillx", "[grow, fill]", "[]"));

        initItems();
        render();
    }

    private void initItems() {
        mItems.add(new SidebarItem("Now Playing", FontAwesome.PLAY, "now_playing", true));
        mItems.add(new SidebarItem("Map", FontAwesome.MAP, "map", true));
        mItems.add(new SidebarItem("Playlist Editor", FontAwesome.LIST, "playlist_editor", true));
        mItems.add(new SidebarItem("Tuners", FontAwesome.SLIDERS, "tuners", true));

        mItems.add(new SidebarItem("Logs", FontAwesome.FILE_TEXT, "logs", true));

        mItems.add(new SidebarItem("Audio Recordings", FontAwesome.MICROPHONE, "audio_recordings", false));
        mItems.add(new SidebarItem("Icon Manager", FontAwesome.PICTURE_O, "icon_manager", true));
        mItems.add(new SidebarItem(".bits Viewer", FontAwesome.FILE_CODE_O, "msg_viewer", true));
        mItems.add(new SidebarItem("User Preferences", FontAwesome.COGS, "user_prefs", true));

        mItems.add(new SidebarItem("Exit", FontAwesome.SIGN_OUT, "exit", true));
    }

    private void render() {
        removeAll();

        // Toggle Button
        JButton toggleBtn = new JButton(IconFontSwing.buildIcon(mCollapsed ? FontAwesome.CHEVRON_RIGHT : FontAwesome.CHEVRON_LEFT, 16, TEXT_COLOR));
        toggleBtn.setContentAreaFilled(false);
        toggleBtn.setBorderPainted(false);
        toggleBtn.setFocusPainted(false);
        toggleBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        toggleBtn.addActionListener(e -> {
            mCollapsed = !mCollapsed;
            setPreferredSize(new Dimension(mCollapsed ? 50 : 250, 0));
            render();
            revalidate();
            repaint();
        });

        if (mCollapsed) {
            add(toggleBtn, "align center");
        } else {
            add(toggleBtn, "align right");
        }

        for (SidebarItem item : mItems) {
            add(item.getView(), "growx");
            if (item.isExpanded() && !mCollapsed && item.hasSubItems()) {
                for (SidebarItem.SubItem sub : item.getSubItems()) {
                    add(sub.getView(), "growx");
                }
            }
        }
    }

    public void setActive(String id) {
        for (SidebarItem item : mItems) {
            item.setActive(item.getId().equals(id));
            if (item.hasSubItems()) {
                for (SidebarItem.SubItem sub : item.getSubItems()) {
                    sub.setActive(sub.getId().equals(id));
                }
            }
        }
    }

    private class SidebarItem {
        private String mLabel;
        private FontAwesome mIcon;
        private String mId;
        private boolean mIsSelectable;
        private boolean mActive = false;
        private boolean mExpanded = false;
        private List<SubItem> mSubItems = new ArrayList<>();
        private JPanel mView;
        private JLabel mIconLabel;
        private JLabel mTextLabel;

        public SidebarItem(String label, FontAwesome icon, String id, boolean isSelectable) {
            mLabel = label;
            mIcon = icon;
            mId = id;
            mIsSelectable = isSelectable;
            createView();
        }

        public void addSubItem(String label, String id) {
            mSubItems.add(new SubItem(label, id));
        }

        public boolean hasSubItems() {
            return !mSubItems.isEmpty();
        }

        public boolean isExpanded() {
            return mExpanded;
        }

        public String getId() {
            return mId;
        }

        public List<SubItem> getSubItems() {
            return mSubItems;
        }

        public void setActive(boolean active) {
            mActive = active;
            updateStyle();
        }

        public JPanel getView() {
            return mView;
        }

        private void createView() {
            mView = new JPanel(new MigLayout("insets 8, gapx 10", "[][grow]", "[]"));
            mView.setCursor(new Cursor(Cursor.HAND_CURSOR));

            mIconLabel = new JLabel(IconFontSwing.buildIcon(mIcon, 16, TEXT_COLOR));
            mIconLabel.setToolTipText(mLabel);
            mTextLabel = new JLabel(mLabel);
            mTextLabel.setForeground(TEXT_COLOR);
            mTextLabel.setFont(mTextLabel.getFont().deriveFont(Font.BOLD));

            mView.add(mIconLabel);
            if (!mCollapsed) {
                mView.add(mTextLabel);
            } else {
                mView.setLayout(new MigLayout("insets 8", "[center, grow]", "[]"));
            }

            updateStyle();

            mView.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (!mActive) mView.setBackground(HOVER_COLOR);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (!mActive) mView.setBackground(BG_COLOR);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (mIsSelectable) {
                        mListener.onItemSelected(mId);
                    } else if (hasSubItems()) {
                        if (mCollapsed) {
                            showPopupMenu(mView);
                        } else {
                            mExpanded = !mExpanded;
                            SidebarPanel.this.render();
                            SidebarPanel.this.revalidate();
                            SidebarPanel.this.repaint();
                        }
                    } else {
                        mListener.onActionRequested(mId);
                    }
                }
            });
        }

        private void showPopupMenu(JPanel invoker) {
            JPopupMenu popup = new JPopupMenu();
            for (SubItem sub : mSubItems) {
                JMenuItem item = new JMenuItem(sub.getLabel());
                item.addActionListener(e -> mListener.onItemSelected(sub.getId()));
                popup.add(item);
            }
            popup.show(invoker, invoker.getWidth(), 0);
        }

        public void updateStyle() {
            mView.setBackground(mActive ? ACTIVE_COLOR : BG_COLOR);
            if (!mCollapsed && mView.getComponentCount() == 1) {
                mView.add(mTextLabel);
            } else if (mCollapsed && mView.getComponentCount() > 1) {
                mView.remove(mTextLabel);
            }
        }

        private class SubItem {
            private String mLabel;
            private String mId;
            private boolean mActive = false;
            private JPanel mView;

            public SubItem(String label, String id) {
                mLabel = label;
                mId = id;
                createView();
            }

            public String getLabel() { return mLabel; }
            public String getId() { return mId; }

            public void setActive(boolean active) {
                mActive = active;
                updateStyle();
            }

            public JPanel getView() { return mView; }

            private void createView() {
                mView = new JPanel(new MigLayout("insets 6 30 6 6", "[grow]", "[]"));
                mView.setCursor(new Cursor(Cursor.HAND_CURSOR));
                mView.setToolTipText(mLabel);

                JLabel textLabel = new JLabel(mLabel);
                textLabel.setForeground(TEXT_COLOR);
                mView.add(textLabel);

                updateStyle();

                mView.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        if (!mActive) mView.setBackground(HOVER_COLOR);
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        if (!mActive) mView.setBackground(BG_COLOR);
                    }


                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (mId.startsWith("vis_")) {
                            mListener.onActionRequested(mId);
                        } else {
                            mListener.onItemSelected(mId);
                        }
                    }

                });
            }

            private void updateStyle() {
                mView.setBackground(mActive ? ACTIVE_COLOR : BG_COLOR);
            }
        }
    }
}
