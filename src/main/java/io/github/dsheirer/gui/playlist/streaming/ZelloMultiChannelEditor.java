/*
 * DEPRECATED — This class is no longer used.
 * The multi-channel editor has been removed along with ZelloMultiChannelConfiguration.
 * Safe to delete when cleaning up the repository.
 */
package io.github.dsheirer.gui.playlist.streaming;

import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.audio.broadcast.zello.ZelloMultiChannelConfiguration;
import io.github.dsheirer.playlist.PlaylistManager;
import javafx.scene.layout.GridPane;

/**
 * @deprecated Removed in ap-14.9.32. No longer reachable from the UI.
 */
@Deprecated
public class ZelloMultiChannelEditor extends AbstractBroadcastEditor<ZelloMultiChannelConfiguration>
{
    public ZelloMultiChannelEditor(PlaylistManager playlistManager)
    {
        super(playlistManager);
    }

    @Override
    public void setItem(ZelloMultiChannelConfiguration item)
    {
        super.setItem(item);
    }

    @Override
    public void save() { }

    @Override
    public void dispose() { }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.ZELLO_WORK;
    }

    @Override
    protected GridPane getEditorPane()
    {
        return new GridPane();
    }
}
