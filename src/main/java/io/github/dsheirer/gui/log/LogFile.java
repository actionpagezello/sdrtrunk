package io.github.dsheirer.gui.log;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Instant;
import java.time.ZoneId;

public class LogFile {
    private File file;
    private String name;
    private LocalDate date;
    private Path dir;

    public LogFile(File file, Path dir) {
        this.file = file;
        this.dir = dir;
        parseFileName();
    }

    private void parseFileName() {
        String filename = file.getName();
        // Check for yyyyMMdd_ prefix
        if (filename.length() > 9 && filename.charAt(8) == '_') {
            String datePart = filename.substring(0, 8);
            try {
                this.date = LocalDate.parse(datePart, DateTimeFormatter.ofPattern("yyyyMMdd"));
                this.name = filename.substring(9);
            } catch (DateTimeParseException e) {
                // Not a valid date prefix, fallback
                this.name = filename;
                this.date = LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault());
            }
        } else {
            this.name = filename;
            // Get last modified date if no date prefix
            this.date = LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault());
        }
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public LocalDate getDate() {
        return date;
    }

    public Path getDir() {
        return dir;
    }
}
