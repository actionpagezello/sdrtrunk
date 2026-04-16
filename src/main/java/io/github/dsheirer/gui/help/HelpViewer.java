package io.github.dsheirer.gui.help;

import net.miginfocom.swing.MigLayout;
import com.jidesoft.swing.JideSplitPane;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public class HelpViewer extends JPanel {
    private JideSplitPane splitPane;

    private JTree navigationTree;
    private JEditorPane contentPane;
    private JTextField searchField;

    public HelpViewer() {



        setLayout(new BorderLayout());
        initUI();
    }

    private void initUI() {
        splitPane = new JideSplitPane(JideSplitPane.HORIZONTAL_SPLIT);

        // Left side: Navigation and Search
        JPanel leftPanel = new JPanel(new MigLayout("insets 5, fill", "[grow,fill]", "[]5[grow,fill]"));

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search Help...");

        searchField.addActionListener(e -> {
            String query = searchField.getText().toLowerCase();
            if(query.isEmpty()) return;

            DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) navigationTree.getModel().getRoot();
            DefaultMutableTreeNode foundNode = searchNode(rootNode, query);
            if(foundNode != null) {
                javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(foundNode.getPath());
                navigationTree.setSelectionPath(path);
                navigationTree.scrollPathToVisible(path);
            }
        });

        leftPanel.add(searchField, "wrap");

        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Knowledge Base");
        createNodes(root);
        navigationTree = new JTree(new DefaultTreeModel(root));
        navigationTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) navigationTree.getLastSelectedPathComponent();
            if (node == null) return;
            updateContent(node.getUserObject().toString());
        });

        JScrollPane treeScrollPane = new JScrollPane(navigationTree);
        leftPanel.add(treeScrollPane);

        // Right side: Content Renderer
        contentPane = new JEditorPane();
        contentPane.setEditable(false);
        contentPane.setContentType("text/html");
        contentPane.setText("<html><body style='font-family: sans-serif; padding: 20px;'>" +
                "<h1>Welcome to SDRTrunk Help</h1>" +
                "<p>Select a topic from the left to view details.</p></body></html>");
        JScrollPane contentScrollPane = new JScrollPane(contentPane);

        splitPane.add(leftPanel);
        splitPane.add(contentScrollPane);
        splitPane.setProportionalLayout(true);
        splitPane.setProportions(new double[]{0.3});

        add(splitPane, BorderLayout.CENTER);
    }

    private DefaultMutableTreeNode searchNode(DefaultMutableTreeNode node, String query) {
        if(node.getUserObject().toString().toLowerCase().contains(query)) {
            return node;
        }
        for(int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode found = searchNode(child, query);
            if(found != null) return found;
        }
        return null;
    }

    private void createNodes(DefaultMutableTreeNode root) {
        DefaultMutableTreeNode dspNode = new DefaultMutableTreeNode("DSP Filters");
        dspNode.add(new DefaultMutableTreeNode("De-emphasis Filter"));
        dspNode.add(new DefaultMutableTreeNode("Squaring Filter"));
        dspNode.add(new DefaultMutableTreeNode("Decimation Filter"));
        root.add(dspNode);

        DefaultMutableTreeNode tuningNode = new DefaultMutableTreeNode("Tuner Settings");
        tuningNode.add(new DefaultMutableTreeNode("Gain Configuration"));
        tuningNode.add(new DefaultMutableTreeNode("Sample Rate"));
        root.add(tuningNode);

        DefaultMutableTreeNode dispatchNode = new DefaultMutableTreeNode("Dispatch & Operations");
        dispatchNode.add(new DefaultMutableTreeNode("Automated Audio Archiving"));
        dispatchNode.add(new DefaultMutableTreeNode("Streaming Setup"));
        root.add(dispatchNode);
    }

    private void updateContent(String topic) {
        String markdown = "# " + topic + "\n\n";

        switch (topic) {
            case "De-emphasis Filter":
                markdown += "**Purpose:** Restores natural voice balance by reversing the pre-emphasis applied at the transmitter.\n\n";
                markdown += "**Benefit:** Significantly reduces harsh high-frequency hiss, making dispatch audio much easier to listen to for extended periods.";
                break;
            case "Decimation Filter":
                 markdown += "**Purpose:** Reduces the sample rate of the signal to lower processing requirements.\n\n";
                 markdown += "**Benefit:** Vastly improves efficiency, allowing more channels to be decoded simultaneously on the same hardware.";
                break;
            case "Gain Configuration":
                markdown += "**Purpose:** Adjusts the RF and IF amplification levels of the SDR hardware.\n\n";
                markdown += "**Benefit:** Optimizes signal-to-noise ratio. Too little gain drops the signal into the noise floor; too much overloads the ADC and distorts digital decoding.";
                break;
            default:
                markdown += "Detailed documentation for this topic is being migrated from the legacy wiki.";
                break;
        }

        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        String htmlBody = renderer.render(document);

        String html = "<html><body style='font-family: sans-serif; padding: 20px;'>" + htmlBody + "</body></html>";
        contentPane.setText(html);
        contentPane.setCaretPosition(0);
    }
}
