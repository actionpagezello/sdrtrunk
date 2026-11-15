/*
 * DEPRECATED — This class is no longer used.
 * The multi-channel parent/child auto-generation feature has been removed.
 * This file is kept as an empty stub to avoid build errors if referenced
 * by old playlist XML files (Jackson will ignore unknown subtypes gracefully).
 * Safe to delete when cleaning up the repository.
 */
package io.github.dsheirer.audio.broadcast.zello;

import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;

/**
 * @deprecated Removed in ap-14.9.32. Use individual ZelloConfiguration entries instead.
 */
@Deprecated
public class ZelloMultiChannelConfiguration extends BroadcastConfiguration
{
    public ZelloMultiChannelConfiguration()
    {
    }

    public ZelloMultiChannelConfiguration(BroadcastFormat format)
    {
        super(format);
    }

    @Override
    public BroadcastServerType getBroadcastServerType()
    {
        return BroadcastServerType.ZELLO_WORK;
    }

    @Override
    public BroadcastConfiguration copyOf()
    {
        return new ZelloMultiChannelConfiguration();
    }
}
