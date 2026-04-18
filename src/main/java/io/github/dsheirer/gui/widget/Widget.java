package io.github.dsheirer.gui.widget;

import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;

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

    private boolean mMinimized = false;

    public Widget(String id, String title, JComponent contentComponent, WidgetContainer container, int minHeight) {
        mId = id;
        mTitle = title;
        mContentComponent = contentComponent;
        mContainer = container;
        mMinHeight = minHeight;

        setLayout(new MigLayout("insets 0, fillx", "[grow,fill]", "[]0[grow,fill]"));
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

        // Apply minimum height to the content component
        if (minHeight > 0) {
            mContentComponent.setMinimumSize(new Dimension(0, minHeight));
        }

        add(mContentComponent, "grow");
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
