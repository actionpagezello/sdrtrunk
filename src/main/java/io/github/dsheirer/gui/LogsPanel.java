package io.github.dsheirer.gui;

import io.github.dsheirer.gui.log.LogFile;
import io.github.dsheirer.gui.log.LogFileTableModel;
import io.github.dsheirer.module.log.ai.AILogAnalyzer;
import io.github.dsheirer.preference.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;


public class LogsPanel extends JPanel {
    private static final Logger mLog = LoggerFactory.getLogger(LogsPanel.class);

    private java.util.Timer mHealthTimer;

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
    private JTabbedPane mTabbedPane;
    private JButton mAnalyzeBtn;



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

        mTabbedPane = new JTabbedPane();
        if (mUserPreferences.getAIPreference().isAIEnabled() && mUserPreferences.getAIPreference().isSystemHealthAdvisorEnabled()) {
            JPanel performancePanel = new JPanel(new BorderLayout());
            JLabel performanceLabel = new JLabel("<html><div style='text-align: center; padding: 20px;'><h1>System Health & Performance Advisor</h1><p>Status: Monitoring...</p><p>CPU Usage: OK</p><p>Memory Usage: OK</p><br><p><i>Optimization Suggestions:</i></p><p>No suggestions at this time.</p></div></html>");
            performanceLabel.setHorizontalAlignment(SwingConstants.CENTER);
            performancePanel.add(performanceLabel, BorderLayout.CENTER);
            mTabbedPane.addTab("System Health", performancePanel);

            mHealthTimer = new java.util.Timer(true);
            mHealthTimer.scheduleAtFixedRate(new SystemHealthAdvisorTask(performanceLabel), 0, 5000);
        }
        mTabbedPane.addTab("Application Logs", createTabPanel(mAppTable, mAppSearchField));
        mTabbedPane.addTab("Channel Event Logs", createTabPanel(mEventTable, mEventSearchField));
        mTabbedPane.addTab("Two-Tone Logs", createTabPanel(mTwoToneTable, mTwoToneSearchField));

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> loadLogs());

                mAnalyzeBtn = new JButton("Analyze Error");
        mAnalyzeBtn.addActionListener(e -> analyzeSelectedLog());

        updateAnalyzeButtonState();
        mAppTable.getSelectionModel().addListSelectionListener(e -> updateAnalyzeButtonState());
        mEventTable.getSelectionModel().addListSelectionListener(e -> updateAnalyzeButtonState());
        mTwoToneTable.getSelectionModel().addListSelectionListener(e -> updateAnalyzeButtonState());
        mTabbedPane.addChangeListener(e -> updateAnalyzeButtonState());

        JPanel btnPanel = new JPanel();
        btnPanel.add(refreshBtn);
        btnPanel.add(mAnalyzeBtn);

        add(new JLabel("Double-click a log file to open it in your text editor", JLabel.CENTER), BorderLayout.NORTH);
        add(mTabbedPane, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
    }


    private void updateAnalyzeButtonState() {
        boolean aiEnabled = mUserPreferences.getAIPreference().isAIEnabled() && mUserPreferences.getAIPreference().isAILogAnalysisEnabled();
        boolean hasApiKey = !mUserPreferences.getAIPreference().getGeminiApiKey().trim().isEmpty();
        boolean hasSelection = getSelectedLogFile() != null;

        mAnalyzeBtn.setEnabled(aiEnabled && hasApiKey && hasSelection);

        if (!aiEnabled) {
            mAnalyzeBtn.setToolTipText("Enable AI and Log Analysis in User Preferences.");
        } else if (!hasApiKey) {
            mAnalyzeBtn.setToolTipText("Gemini API Key is missing in User Preferences.");
        } else if (!hasSelection) {
            mAnalyzeBtn.setToolTipText("Select a log file to analyze.");
        } else {
            mAnalyzeBtn.setToolTipText("Analyze selected log file using AI.");
        }
    }

    private LogFile getSelectedLogFile() {
        int selectedIndex = mTabbedPane.getSelectedIndex();
        if (selectedIndex == 0) {
            return getLogFileFromTable(mAppTable, mAppListModel);
        } else if (selectedIndex == 1) {
            return getLogFileFromTable(mEventTable, mEventListModel);
        } else if (selectedIndex == 2) {
            return getLogFileFromTable(mTwoToneTable, mTwoToneListModel);
        }
        return null;
    }

    private LogFile getLogFileFromTable(JTable table, LogFileTableModel model) {
        int viewRow = table.getSelectedRow();
        if (viewRow != -1) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            return model.getLogFileAt(modelRow);
        }
        return null;
    }

    private void analyzeSelectedLog() {
        LogFile logFile = getSelectedLogFile();
        if (logFile == null || !logFile.getFile().exists()) {
            return;
        }

        mAnalyzeBtn.setEnabled(false);
        mAnalyzeBtn.setText("Analyzing...");

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                // Read last N lines or all if small
                String content;
                try {
                    List<String> lines = Files.readAllLines(logFile.getFile().toPath());
                    int maxLines = 500; // avoid huge payload
                    if (lines.size() > maxLines) {
                        lines = lines.subList(lines.size() - maxLines, lines.size());
                    }
                    content = String.join("\n", lines);
                } catch (IOException e) {
                    return "Error reading log file: " + e.getMessage();
                }

                AILogAnalyzer analyzer = new AILogAnalyzer(mUserPreferences);
                return analyzer.analyze(content);
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    JTextArea textArea = new JTextArea(result);
                    textArea.setEditable(false);
                    textArea.setLineWrap(true);
                    textArea.setWrapStyleWord(true);
                    JScrollPane scrollPane = new JScrollPane(textArea);
                    scrollPane.setPreferredSize(new Dimension(600, 400));
                    JOptionPane.showMessageDialog(LogsPanel.this, scrollPane, "AI Log Analysis", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(LogsPanel.this, "Error analyzing log:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    mAnalyzeBtn.setText("Analyze Error");
                    updateAnalyzeButtonState();
                }
            }
        };
        worker.execute();
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
                mLog.error("Error opening log file: {}", logFile.getAbsolutePath(), ex);
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
