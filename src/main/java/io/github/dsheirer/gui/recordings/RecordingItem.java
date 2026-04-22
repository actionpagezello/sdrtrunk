package io.github.dsheirer.gui.recordings;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;

public class RecordingItem {
    private Path file;
    private StringProperty date = new SimpleStringProperty("");
    private StringProperty time = new SimpleStringProperty("");
    private StringProperty channel = new SimpleStringProperty("");
    private StringProperty toAlias = new SimpleStringProperty("");
    private StringProperty fromAlias = new SimpleStringProperty("");
    private StringProperty length = new SimpleStringProperty("");

    public RecordingItem(Path file) {
        this.file = file;
    }

    public Path getFile() { return file; }

    public String getDate() { return date.get(); }
    public StringProperty dateProperty() { return date; }
    public void setDate(String date) { this.date.set(date); }

    public String getTime() { return time.get(); }
    public StringProperty timeProperty() { return time; }
    public void setTime(String time) { this.time.set(time); }

    public String getChannel() { return channel.get(); }
    public StringProperty channelProperty() { return channel; }
    public void setChannel(String channel) { this.channel.set(channel); }

    public String getToAlias() { return toAlias.get(); }
    public StringProperty toAliasProperty() { return toAlias; }
    public void setToAlias(String toAlias) { this.toAlias.set(toAlias); }

    public String getFromAlias() { return fromAlias.get(); }
    public StringProperty fromAliasProperty() { return fromAlias; }
    public void setFromAlias(String fromAlias) { this.fromAlias.set(fromAlias); }

    public String getLength() { return length.get(); }
    public StringProperty lengthProperty() { return length; }
    public void setLength(String length) { this.length.set(length); }
}
