package io.github.dsheirer.gui.help;

import jiconfont.swing.IconFontSwing;
import jiconfont.icons.font_awesome.FontAwesome;
import javax.swing.JLabel;
import javax.swing.ToolTipManager;
import java.awt.Color;

public class HelpIconLabel extends JLabel {
    public HelpIconLabel(String htmlHelpText) {
        super(IconFontSwing.buildIcon(FontAwesome.INFO_CIRCLE, 14, Color.GRAY));
        setToolTipText(htmlHelpText);

    }
}
