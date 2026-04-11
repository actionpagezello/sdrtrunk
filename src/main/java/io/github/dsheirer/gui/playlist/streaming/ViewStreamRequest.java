package io.github.dsheirer.gui.playlist.streaming;

import io.github.dsheirer.gui.playlist.PlaylistEditorRequest;

public class ViewStreamRequest extends PlaylistEditorRequest {
    private String streamName;

    public ViewStreamRequest(String streamName) {
        this.streamName = streamName;
    }

    public String getStreamName() {
        return streamName;
    }

    @Override
    public TabName getTabName() {
        return TabName.STREAM;
    }
}
