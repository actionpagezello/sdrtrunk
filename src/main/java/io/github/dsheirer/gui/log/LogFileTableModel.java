package io.github.dsheirer.gui.log;

import javax.swing.table.AbstractTableModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class LogFileTableModel extends AbstractTableModel {
    private List<LogFile> logFiles;
    private final String[] columnNames = {"Date", "Name"};

    public LogFileTableModel() {
        this.logFiles = new ArrayList<>();
    }

    public void setLogFiles(List<LogFile> logFiles) {
        this.logFiles = logFiles;
        fireTableDataChanged();
    }

    public LogFile getLogFileAt(int row) {
        if (row >= 0 && row < logFiles.size()) {
            return logFiles.get(row);
        }
        return null;
    }

    @Override
    public int getRowCount() {
        return logFiles.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        if (columnIndex == 0) {
            return LocalDate.class;
        }
        return String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        LogFile logFile = logFiles.get(rowIndex);
        if (columnIndex == 0) {
            return logFile.getDate();
        } else if (columnIndex == 1) {
            return logFile.getName();
        }
        return null;
    }
}
