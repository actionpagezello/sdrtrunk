package io.github.dsheirer.gui;

import io.github.dsheirer.preference.UserPreferences;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.DefaultListModel;
import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.FilenameFilter;

public class LogsPanel extends JPanel {

    private DefaultListModel<String> mAppListModel;
    private JList<String> mAppList;
    private DefaultListModel<String> mEventListModel;
    private JList<String> mEventList;
    private UserPreferences mUserPreferences;

    public LogsPanel(UserPreferences userPreferences) {
        mUserPreferences = userPreferences;

        setLayout(new BorderLayout());

        mAppListModel = new DefaultListModel<>();
        mAppList = new JList<>(mAppListModel);
        mAppList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        mAppList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openLog(mAppList.getSelectedValue(), mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog());
                }
            }
        });

        mEventListModel = new DefaultListModel<>();
        mEventList = new JList<>(mEventListModel);
        mEventList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        mEventList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openLog(mEventList.getSelectedValue(), mUserPreferences.getDirectoryPreference().getDirectoryEventLog());
                }
            }
        });

        loadLogs();

        JPanel appPanel = new JPanel(new BorderLayout());
        appPanel.add(new JLabel("Application Logs", JLabel.CENTER), BorderLayout.NORTH);
        appPanel.add(new JScrollPane(mAppList), BorderLayout.CENTER);

        JPanel eventPanel = new JPanel(new BorderLayout());
        eventPanel.add(new JLabel("Channel Event Logs", JLabel.CENTER), BorderLayout.NORTH);
        eventPanel.add(new JScrollPane(mEventList), BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, appPanel, eventPanel);
        splitPane.setResizeWeight(0.5);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadLogs());

        JPanel btnPanel = new JPanel();
        btnPanel.add(refreshBtn);

        add(splitPane, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
    }

    private void openLog(String selected, Path dir) {
        if (selected != null) {
            File logFile = new File(dir.toFile(), selected);
            try {
                Desktop.getDesktop().open(logFile);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void loadLogs() {
        mAppListModel.clear();
        mEventListModel.clear();

        File appDir = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog().toFile();
        if (appDir.exists() && appDir.isDirectory()) {
            File[] files = appDir.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    mAppListModel.addElement(file.getName());
                }
            }
        }

        File eventDir = mUserPreferences.getDirectoryPreference().getDirectoryEventLog().toFile();
        if (eventDir.exists() && eventDir.isDirectory()) {
            File[] files = eventDir.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    mEventListModel.addElement(file.getName());
                }
            }
        }
    }
}
