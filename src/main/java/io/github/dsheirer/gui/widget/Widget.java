package io.github.dsheirer.gui.widget;

import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

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

        setOpaque(false);
        setLayout(new MigLayout("insets 0, fillx, hidemode 3", "[grow,fill]", "[]0[grow,fill]0[4!]"));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        mHeaderPanel = new JPanel(new MigLayout("insets 2 5 2 2, fillx", "[grow][]", "[]"));
        mHeaderPanel.setOpaque(false);

        mTitleLabel = new JLabel(title);
        mTitleLabel.setFont(mTitleLabel.getFont().deriveFont(Font.BOLD));
        mTitleLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        mHeaderPanel.add(mTitleLabel, "growx");

        mMinimizeButton = createHeaderButton();
        mMinimizeButton.setToolTipText("Minimize");
        mMinimizeButton.getAccessibleContext().setAccessibleName("Minimize Widget");
        mMinimizeButton.getAccessibleContext().setAccessibleDescription("Minimizes or expands the widget");
        mMinimizeButton.addActionListener(e -> toggleMinimized());
        mHeaderPanel.add(mMinimizeButton);

        mCloseButton = createHeaderButton();
        mCloseButton.setToolTipText("Close");
        mCloseButton.getAccessibleContext().setAccessibleName("Close Widget");
        mCloseButton.getAccessibleContext().setAccessibleDescription("Closes the widget");
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
        add(mResizeHandle, "growx, h 4!");
    }

    private JButton createHeaderButton() {
        JButton button = new JButton();
        button.setMargin(new Insets(0, 4, 0, 4));
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    public void setCloseButtonVisible(boolean visible) {
        mCloseButton.setVisible(visible);
    }

    public void setMinimizeButtonVisible(boolean visible) {
        mMinimizeButton.setVisible(visible);
    }

    public boolean isMinimizeButtonVisible() {
        return mMinimizeButton.isVisible();
    }

    public String getId() {
        return mId;
    }

    public JPanel getHeaderPanel() {
        return mHeaderPanel;
    }

    public void ensureContentComponentParent() {
        if (mContentComponent != null && mContentComponent.getParent() != this) {
            add(mContentComponent, "grow, wrap", 1);
            revalidate();
            repaint();
        }
    }

    public void setDragging(boolean dragging) {
        if (dragging) {
            mHeaderPanel.setBackground(UIManager.getColor("Component.focusColor"));
            mHeaderPanel.setOpaque(true);
        } else {
            mHeaderPanel.setOpaque(false);
            setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor"), 1));
        }
        repaint();
    }

    public void setMinimized(boolean minimized) {
        mMinimized = minimized;
        mContentComponent.setVisible(!minimized);
        updateIcons();
        mMinimizeButton.setToolTipText(minimized ? "Expand" : "Minimize");
        mMinimizeButton.getAccessibleContext().setAccessibleName(minimized ? "Expand Widget" : "Minimize Widget");
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

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int arc = 12;
        Shape cardShape = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);

        g2.setColor(UIManager.getColor("Panel.background"));
        g2.fill(cardShape);

        g2.setColor(new Color(0, 0, 0, 25)); // 10% opacity black
        g2.setStroke(new BasicStroke(1.0f));
        g2.draw(cardShape);

        g2.dispose();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        updateIcons();
    }

    private void updateIcons() {
        if (mMinimizeButton != null) mMinimizeButton.setIcon(IconFontSwing.buildIcon(mMinimized ? FontAwesome.PLUS : FontAwesome.MINUS, 12, UIManager.getColor("Label.disabledForeground")));
        if (mCloseButton != null) mCloseButton.setIcon(IconFontSwing.buildIcon(FontAwesome.TIMES, 12, UIManager.getColor("Label.disabledForeground")));
    }
}
