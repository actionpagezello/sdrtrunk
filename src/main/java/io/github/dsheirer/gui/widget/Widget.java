package io.github.dsheirer.gui.widget;

import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class Widget extends JPanel {

    private final String mId;
    private final String mTitle;
    private final JComponent mContentComponent;
    private final JPanel mHeaderPanel;
    private final JLabel mTitleLabel;
    private final JButton mMinimizeButton;
    private final JButton mCloseButton;
    private final WidgetContainer mContainer;
    private final int mMinHeight;
    private final JPanel mResizeHandle;

    private boolean mMinimized = false;

    public Widget(String id, String title, JComponent contentComponent, WidgetContainer container, int minHeight) {
        mId = id;
        mTitle = title;
        mContentComponent = contentComponent;
        mContainer = container;
        mMinHeight = minHeight;

        setLayout(new MigLayout("insets 0, fillx, hidemode 3", "[grow,fill]", "[]0[grow,fill]0[]"));
        setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));

        mHeaderPanel = new JPanel(new MigLayout("insets 2 5 2 2, fillx", "[grow][]", "[]"));
        mHeaderPanel.setBackground(UIManager.getColor("TableHeader.background"));

        mTitleLabel = new JLabel(title);
        mTitleLabel.setFont(mTitleLabel.getFont().deriveFont(Font.BOLD));
        mHeaderPanel.add(mTitleLabel, "growx");

        mMinimizeButton = createHeaderButton("-");
        mMinimizeButton.addActionListener(e -> toggleMinimized());
        mHeaderPanel.add(mMinimizeButton);

        mCloseButton = createHeaderButton("x");
        mCloseButton.addActionListener(e -> closeWidget());
        mHeaderPanel.add(mCloseButton);

        add(mHeaderPanel, "wrap");

        int savedHeight = minHeight;
        if (container != null && container.getPreference() != null) {
            savedHeight = container.getPreference().getWidgetHeight(id, minHeight);
        }
        if (savedHeight > 0) {
            mContentComponent.setPreferredSize(new Dimension(0, savedHeight));
        }
        if (minHeight > 0) {
            mContentComponent.setMinimumSize(new Dimension(0, minHeight));
        }
        add(mContentComponent, "grow, wrap");
        mResizeHandle = new JPanel();
        mResizeHandle.setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));
        mResizeHandle.setBackground(UIManager.getColor("Component.borderColor"));
        mResizeHandle.setPreferredSize(new Dimension(0, 4));
        MouseAdapter resizeAdapter = new MouseAdapter() {
            int startY;
            int startHeight;
            @Override
            public void mousePressed(MouseEvent e) {
                startY = e.getYOnScreen();
                startHeight = mContentComponent.getHeight();
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                int delta = e.getYOnScreen() - startY;
                int newHeight = Math.max(mMinHeight, startHeight + delta);
                mContentComponent.setPreferredSize(new Dimension(mContentComponent.getWidth(), newHeight));
                revalidate();
                if (mContainer != null) mContainer.revalidate();
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (mContainer != null && mContainer.getPreference() != null) {
                    mContainer.getPreference().setWidgetHeight(mId, mContentComponent.getHeight());
                }
            }
        };
        mResizeHandle.addMouseListener(resizeAdapter);
        mResizeHandle.addMouseMotionListener(resizeAdapter);
        add(mResizeHandle, "growx");
    }

    private JButton createHeaderButton(String text) {
        JButton button = new JButton(text);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    public void setHeaderButtonsVisible(boolean visible) {
        mMinimizeButton.setVisible(visible);
        mCloseButton.setVisible(visible);
    }

    public String getId() {
        return mId;
    }

    public JPanel getHeaderPanel() {
        return mHeaderPanel;
    }

    public void setDragging(boolean dragging) {
        if (dragging) {
            mHeaderPanel.setBackground(UIManager.getColor("Component.focusColor"));
            setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.focusColor"), 2));
        } else {
            mHeaderPanel.setBackground(UIManager.getColor("TableHeader.background"));
            setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));
        }
    }

    public void setMinimized(boolean minimized) {
        mMinimized = minimized;
        mContentComponent.setVisible(!minimized);
        mMinimizeButton.setText(minimized ? "+" : "-");
        revalidate();
        repaint();
    }

    public boolean isMinimized() {
        return mMinimized;
    }

    private void toggleMinimized() {
        setMinimized(!mMinimized);
        if (mContainer != null) {
            mContainer.onWidgetStateChanged(this);
        }
    }

    private void closeWidget() {
        if (mContainer != null) {
            mContainer.hideWidget(mId);
        }
    }
}
