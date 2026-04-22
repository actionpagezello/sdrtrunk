package io.github.dsheirer.gui;

import io.github.dsheirer.gui.log.LogFile;
import io.github.dsheirer.gui.log.LogFileTableModel;
import io.github.dsheirer.preference.UserPreferences;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;

public class LogsPanel extends JPanel {

    private UserPreferences mUserPreferences;

    private LogFileTableModel mAppListModel;
    private JTable mAppTable;
    private TableRowSorter<LogFileTableModel> mAppSorter;

    private LogFileTableModel mEventListModel;
    private JTable mEventTable;
    private TableRowSorter<LogFileTableModel> mEventSorter;

    private LogFileTableModel mTwoToneListModel;
    private JTable mTwoToneTable;
    private TableRowSorter<LogFileTableModel> mTwoToneSorter;

    private JTextField mAppSearchField;
    private JTextField mEventSearchField;
    private JTextField mTwoToneSearchField;

    public LogsPanel(UserPreferences userPreferences) {
        mUserPreferences = userPreferences;

        setLayout(new BorderLayout());

        mAppListModel = new LogFileTableModel();
        mAppTable = createLogTable(mAppListModel);
        mAppSorter = new TableRowSorter<>(mAppListModel);
        mAppTable.setRowSorter(mAppSorter);

        mEventListModel = new LogFileTableModel();
        mEventTable = createLogTable(mEventListModel);
        mEventSorter = new TableRowSorter<>(mEventListModel);
        mEventTable.setRowSorter(mEventSorter);

        mTwoToneListModel = new LogFileTableModel();
        mTwoToneTable = createLogTable(mTwoToneListModel);
        mTwoToneSorter = new TableRowSorter<>(mTwoToneListModel);
        mTwoToneTable.setRowSorter(mTwoToneSorter);

        loadLogs();

        mAppSearchField = createSearchField(mAppSorter);
        mEventSearchField = createSearchField(mEventSorter);
        mTwoToneSearchField = createSearchField(mTwoToneSorter);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Application Logs", createTabPanel(mAppTable, mAppSearchField));
        tabbedPane.addTab("Channel Event Logs", createTabPanel(mEventTable, mEventSearchField));
        tabbedPane.addTab("Two-Tone Logs", createTabPanel(mTwoToneTable, mTwoToneSearchField));

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadLogs());

        JPanel btnPanel = new JPanel();
        btnPanel.add(refreshBtn);

        add(new JLabel("Double-click a log file to open it in your text editor", JLabel.CENTER), BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
    }

    private JTable createLogTable(LogFileTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        // Setup date column renderer
        table.setDefaultRenderer(LocalDate.class, new DefaultTableCellRenderer() {
            private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof LocalDate) {
                    setText(((LocalDate) value).format(formatter));
                }
                return this;
            }
        });

        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() != -1) {
                    int viewRow = table.getSelectedRow();
                    int modelRow = table.convertRowIndexToModel(viewRow);
                    LogFile logFile = model.getLogFileAt(modelRow);
                    openLog(logFile.getFile());
                }
            }
        });

        return table;
    }

    private JPanel createTabPanel(JTable table, JTextField searchField) {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel header = new JPanel(new BorderLayout());

        JPanel searchPanel = new JPanel(new BorderLayout(5, 0));
        searchPanel.add(new JLabel("Filter by Date or Name:"), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        header.add(searchPanel, BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JTextField createSearchField(TableRowSorter<LogFileTableModel> sorter) {
        JTextField searchField = new JTextField();
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFilter(); }
            public void removeUpdate(DocumentEvent e) { updateFilter(); }
            public void changedUpdate(DocumentEvent e) { updateFilter(); }

            private void updateFilter() {
                String text = searchField.getText();
                if (text.trim().isEmpty()) {
                    sorter.setRowFilter(null);
                } else {
                    try {
                        // Filter by date or name
                        sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
                    } catch (PatternSyntaxException e) {
                        // Ignore invalid regex
                    }
                }
            }
        });
        return searchField;
    }

    private void openLog(File logFile) {
        if (logFile != null && logFile.exists()) {
            try {
                Desktop.getDesktop().open(logFile);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void loadLogs() {
        List<LogFile> appLogs = new ArrayList<>();
        List<LogFile> twoToneLogs = new ArrayList<>();
        List<LogFile> eventLogs = new ArrayList<>();

        File appDir = mUserPreferences.getDirectoryPreference().getDirectoryApplicationLog().toFile();
        if (appDir.exists() && appDir.isDirectory()) {
            File[] files = appDir.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    LogFile logFile = new LogFile(file, appDir.toPath());
                    if (file.getName().contains("sdrtrunk_twotone")) {
                        twoToneLogs.add(logFile);
                    } else {
                        appLogs.add(logFile);
                    }
                }
            }
        }

        File eventDir = mUserPreferences.getDirectoryPreference().getDirectoryEventLog().toFile();
        if (eventDir.exists() && eventDir.isDirectory()) {
            File[] files = eventDir.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    eventLogs.add(new LogFile(file, eventDir.toPath()));
                }
            }
        }

        mAppListModel.setLogFiles(appLogs);
        mTwoToneListModel.setLogFiles(twoToneLogs);
        mEventListModel.setLogFiles(eventLogs);
    }
}
