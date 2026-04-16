package io.github.dsheirer.gui;

import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import io.github.dsheirer.gui.help.HelpViewer;

import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.Timer;
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

    private final Color BG_COLOR = new Color(240, 240, 240);
    private final Color HOVER_COLOR = new Color(220, 220, 220);
    private final Color ACTIVE_COLOR = new Color(200, 200, 200);
    private final Color TEXT_COLOR = Color.BLACK;

    private List<SidebarItem> mItems = new ArrayList<>();
    private JButton mToggleBtn;
    private Timer mAnimationTimer;

    public interface SidebarListener {
        void onItemSelected(String id);
        void onActionRequested(String actionId);
    }

    public SidebarPanel(SidebarListener listener) {
        mListener = listener;
        setBackground(BG_COLOR);
        setPreferredSize(new Dimension(250, 0));
        setLayout(new MigLayout("insets 10 5 10 5, gapy 5, wrap 1, fillx", "[grow, fill]", "[]"));

        mToggleBtn = new JButton(IconFontSwing.buildIcon(FontAwesome.TIMES, 20, TEXT_COLOR));
        mToggleBtn.setContentAreaFilled(false);
        mToggleBtn.setBorderPainted(false);
        mToggleBtn.setFocusPainted(false);
        mToggleBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        mToggleBtn.addActionListener(e -> {
            if (mAnimationTimer != null && mAnimationTimer.isRunning()) mAnimationTimer.stop();
            mCollapsed = !mCollapsed;
            mToggleBtn.setIcon(IconFontSwing.buildIcon(mCollapsed ? FontAwesome.BARS : FontAwesome.TIMES, 20, TEXT_COLOR));

            if (mCollapsed) {
                for (SidebarItem item : mItems) item.updateCollapsedState(true);
                render(); // Remove sub-items immediately when collapsing
            }

            mAnimationTimer = new Timer(15, ae -> {
                int target = mCollapsed ? 50 : 250;
                int current = getPreferredSize().width;
                if (current == target) {
                    ((Timer)ae.getSource()).stop();
                    if (!mCollapsed) {
                        for (SidebarItem item : mItems) item.updateCollapsedState(false);
                        render(); // Add sub-items back after expanding
                    }
                } else {
                    int step = mCollapsed ? -20 : 20;
                    int next = current + step;
                    if (mCollapsed && next < target) next = target;
                    if (!mCollapsed && next > target) next = target;
                    setPreferredSize(new Dimension(next, 0));
                    revalidate();
                    repaint();
                }
            });
            mAnimationTimer.start();
        });

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
        mItems.add(new SidebarItem(".bits Viewer", FontAwesome.FILE_CODE_O, "msg_viewer", true));
        mItems.add(new SidebarItem("User Preferences", FontAwesome.COGS, "user_prefs", true));
        mItems.add(new SidebarItem("Help & Docs", FontAwesome.QUESTION_CIRCLE, "help_viewer", true));

        mItems.add(new SidebarItem("Exit", FontAwesome.SIGN_OUT, "exit", true));
    }

    private void render() {
        removeAll();

        if (mCollapsed) {
            add(mToggleBtn, "align center");
        } else {
            add(mToggleBtn, "align right");
        }

        for (SidebarItem item : mItems) {
            add(item.getView(), "growx");
            if (item.isExpanded() && !mCollapsed && item.hasSubItems()) {
                for (SidebarItem.SubItem sub : item.getSubItems()) {
                    add(sub.getView(), "growx");
                }
            }
        }
        revalidate();
        repaint();
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

        private JPanel mTextWrapper;

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

        public void updateCollapsedState(boolean collapsed) {
            if (mTextWrapper != null) {
                mTextWrapper.setVisible(!collapsed);
            }
            mView.setToolTipText(collapsed ? mLabel : null);
            for (SubItem sub : mSubItems) sub.updateCollapsedState(collapsed);
            mView.revalidate();
            mView.repaint();
        }

        private void createView() {
            mView = new JPanel(new MigLayout("insets 8, gapx 10, hidemode 3", "[][grow]", "[]"));
            mView.setCursor(new Cursor(Cursor.HAND_CURSOR));

            mIconLabel = new JLabel(IconFontSwing.buildIcon(mIcon, 16, TEXT_COLOR));
            mTextLabel = new JLabel(mLabel);
            mTextLabel.setForeground(TEXT_COLOR);
            mTextLabel.setFont(mTextLabel.getFont().deriveFont(Font.BOLD));

            mTextWrapper = new JPanel(new MigLayout("insets 0", "[grow]", "[]"));
            mTextWrapper.setOpaque(false);
            mTextWrapper.add(mTextLabel, "growx");

            mView.add(mIconLabel);
            mView.add(mTextWrapper, "growx");
            mTextWrapper.setVisible(!mCollapsed);

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
            mView.setToolTipText(mCollapsed ? mLabel : null);
            mView.revalidate();
            mView.repaint();
        }

        private class SubItem {
            private String mLabel;
            private String mId;
            private boolean mActive = false;
            private JPanel mView;
            private JLabel mTextLabel;
            private JPanel mTextWrapper;

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

            public void updateCollapsedState(boolean collapsed) {
                if (mTextWrapper != null) {
                    mTextWrapper.setVisible(!collapsed);
                }
                mView.setToolTipText(collapsed ? mLabel : null);
                mView.revalidate();
                mView.repaint();
            }

            private void createView() {
                mView = new JPanel(new MigLayout("insets 6 30 6 6, hidemode 3", "[grow]", "[]"));
                mView.setCursor(new Cursor(Cursor.HAND_CURSOR));
                mTextLabel = new JLabel(mLabel);
                mTextLabel.setForeground(TEXT_COLOR);

                mTextWrapper = new JPanel(new MigLayout("insets 0", "[grow]", "[]"));
                mTextWrapper.setOpaque(false);
                mTextWrapper.add(mTextLabel, "growx");

                mView.add(mTextWrapper, "growx");
                mTextWrapper.setVisible(!mCollapsed);

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
                mView.setToolTipText(mCollapsed ? mLabel : null);
                mView.revalidate();
                mView.repaint();
            }
        }
    }
}
