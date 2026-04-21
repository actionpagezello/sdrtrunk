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
        root.add(new DefaultMutableTreeNode("What's New"));

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

        DefaultMutableTreeNode decodingNode = new DefaultMutableTreeNode("Decoding");
        decodingNode.add(new DefaultMutableTreeNode("P25 Phase I"));
        decodingNode.add(new DefaultMutableTreeNode("P25 Phase II"));
        decodingNode.add(new DefaultMutableTreeNode("DMR"));
        root.add(decodingNode);

        DefaultMutableTreeNode systemNode = new DefaultMutableTreeNode("System Configuration");
        systemNode.add(new DefaultMutableTreeNode("Aliases"));
        systemNode.add(new DefaultMutableTreeNode("Playlists"));
        root.add(systemNode);
    }




    private void updateContent(String topic) {
        String markdown = "# " + topic + "\n\n";

        switch (topic) {
            case "What's New":
                markdown += "This edition is a specialized Dispatch & Efficiency fork of the original SDRTrunk software. It includes features and enhancements designed specifically for automated dispatch and operator efficiency.\n\n";
                markdown += "### 📡 Streaming & Integrations\n";
                markdown += "* **Zello Streaming:** Directly stream dispatch audio to Zello with automatic reconnection and detailed troubleshooting logs.\n";
                markdown += "* **ThinLine Radio Support:** Built-in streaming directly to ThinLine Radio with out-of-the-box debug logging.\n";
                markdown += "* **Rdio Scanner Improvements:** Enhanced integration with Rdio Scanner to easily manage API keys and verify audio streams.\n\n";
                markdown += "### 🎧 Audio Quality & Tuning\n";
                markdown += "* **Analog Hiss Reduction:** New background noise and hiss reduction filters for NBFM (analog) channels.\n";
                markdown += "* **Anti-Clipping:** Audio filters tuned by default to prevent loud transmissions from distorting.\n";
                markdown += "* **P25 Audio Enhancements:** Specialized tuning for clearer digital audio on complex P25 systems.\n";
                markdown += "* **Virtual Audio Cable (VAC) Routing:** Route specific radio aliases directly to Virtual Audio Cables for external application use.\n\n";
                markdown += "### 🎛️ Advanced Filtering & Control\n";
                markdown += "* **Ignore Unwanted Talkgroups:** Option to ignore talkgroups not saved in your alias list.\n";
                markdown += "* **CTCSS / DCS / NAC Filtering:** Filter out unwanted analog or digital interference using specific tones and codes.\n";
                markdown += "* **P25 NAC Override:** Advanced control for overriding and filtering P25 system NAC codes.\n\n";
                markdown += "### 💻 User Interface Improvements\n";
                markdown += "* **Persistent Layouts:** Channels tab remembers column widths and sorting after restarts.\n";
                markdown += "* **Alphabetical Sorting:** Alias list can now be sorted alphabetically.\n";
                markdown += "* **Mute/Unmute from Waterfall:** Quickly mute/unmute audio directly from the spectral waterfall display.\n";
                markdown += "* **Radio Reference Import:** Streamlined process for importing radio system data.\n\n";
                markdown += "### 🛠️ System & Diagnostics\n";
                markdown += "* **Live Diagnostics Panel:** New \"Diagnostics (Logging)\" settings panel to toggle detailed troubleshooting logs on the fly.\n";
                markdown += "* **Hardware & Decoding Fixes:** Various stability fixes for DMR decoding and SDR hardware tuning.\n";
                break;
            case "De-emphasis Filter":
                markdown += "**Purpose:** Restores natural voice balance by reversing the pre-emphasis applied at the transmitter. Pre-emphasis boosts high frequencies before transmission to overcome noise.\n\n";
                markdown += "**Benefit:** Significantly reduces harsh high-frequency hiss, making dispatch audio much easier to listen to for extended periods, reducing operator fatigue.\n\n";
                markdown += "**Usage:** Typically enabled automatically for analog FM voice channels to ensure standard audio fidelity.";
                break;
            case "Squaring Filter":
                markdown += "**Purpose:** Used in timing recovery and carrier frequency estimation by squaring the signal to generate a harmonic at twice the carrier frequency.\n\n";
                markdown += "**Benefit:** Helps in synchronizing the receiver with the incoming digital signal, especially in low signal-to-noise ratio (SNR) environments.\n\n";
                markdown += "**Usage:** An internal DSP component vital for reliable digital demodulation.";
                break;
            case "Decimation Filter":
                 markdown += "**Purpose:** Reduces the sample rate of the signal to lower processing requirements. It acts as a low-pass filter followed by downsampling.\n\n";
                 markdown += "**Benefit:** Vastly improves efficiency, allowing more channels to be decoded simultaneously on the same hardware without overloading the CPU.\n\n";
                 markdown += "**Usage:** Automatically configured by SDRTrunk based on the target channel bandwidth.";
                break;
            case "Gain Configuration":
                markdown += "**Purpose:** Adjusts the RF and IF amplification levels of the SDR hardware to optimize signal reception.\n\n";
                markdown += "**Benefit:** Optimizes signal-to-noise ratio. Too little gain drops the signal into the noise floor; too much overloads the Analog-to-Digital Converter (ADC) and distorts digital decoding, causing errors or complete signal loss.\n\n";
                markdown += "**Usage:** Adjust sliders in the Tuners tab until the signal peaks are clearly visible above the noise floor in the spectrum display without clipping.";
                break;
            case "Sample Rate":
                markdown += "**Purpose:** Determines the bandwidth of the radio spectrum captured by the SDR hardware (e.g., 2.4 MSPS captures 2.4 MHz of bandwidth).\n\n";
                markdown += "**Benefit:** A higher sample rate allows monitoring a wider frequency range and more channels simultaneously, but increases CPU and USB bus load.\n\n";
                markdown += "**Usage:** Select a rate high enough to cover your target frequencies but low enough to maintain system stability.";
                break;
            case "Automated Audio Archiving":
                markdown += "**Purpose:** Automatically records and saves audio transmissions to disk as standard audio files (e.g., MP3 or WAV).\n\n";
                markdown += "**Benefit:** Enables playback of past transmissions, which is extremely useful for record-keeping, auditing, and reviewing missed calls.\n\n";
                markdown += "**Usage:** Configured via the User Preferences and individual Alias settings to specify which talkgroups or IDs should be recorded.";
                break;
            case "Streaming Setup":
                markdown += "**Purpose:** Configures the software to send decoded audio to external services like Icecast, Broadcastify, or Zello.\n\n";
                markdown += "**Benefit:** Allows sharing dispatch audio with remote users or integrating with web-based listening platforms seamlessly.\n\n";
                markdown += "**Usage:** Setup streaming profiles in the Playlist Editor and assign them to specific aliases or channels.";
                break;
            case "P25 Phase I":
                markdown += "**Purpose:** A standard for digital public safety radio communications, using FDMA (Frequency Division Multiple Access).\n\n";
                markdown += "**Benefit:** Provides clear digital voice and data communications. SDRTrunk can decode this protocol natively.\n\n";
                break;
            case "P25 Phase II":
                markdown += "**Purpose:** An evolution of the P25 standard using TDMA (Time Division Multiple Access) to double the channel capacity.\n\n";
                markdown += "**Benefit:** Allows two voice paths on a single 12.5 kHz channel, improving spectral efficiency. SDRTrunk handles the TDMA slot decoding automatically.\n\n";
                break;
            case "DMR":
                markdown += "**Purpose:** Digital Mobile Radio, a standard primarily used for commercial and business radio systems.\n\n";
                markdown += "**Benefit:** Offers cost-effective digital communication with features like text messaging. SDRTrunk supports basic DMR decoding.\n\n";
                break;
            case "Aliases":
                markdown += "**Purpose:** Assigns human-readable names, colors, and actions to specific talkgroups and radio IDs.\n\n";
                markdown += "**Benefit:** Makes it easier to identify who is speaking, displaying \"Dispatch\" instead of \"TG 1001\". Aliases can also trigger actions like recording or streaming.\n\n";
                markdown += "**Usage:** Manage aliases in the Playlist Editor under the Aliases tab.";
                break;
            case "Playlists":
                markdown += "**Purpose:** Organizes channels, aliases, and system settings into easily loadable configuration files.\n\n";
                markdown += "**Benefit:** Allows quickly switching between different monitoring setups, locations, or configurations without reconfiguring the software from scratch.\n\n";
                markdown += "**Usage:** Create and switch playlists using the Playlist Editor.";
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
