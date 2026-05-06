package io.github.windtunnel.content;

import net.minecraft.core.BlockPos;

/**
 * Client screens that can receive a server-sent force diagram snapshot.
 */
public interface WindTunnelDiagramReceiver {
    BlockPos windtunnel$getDiagramPos();

    void windtunnel$updateDiagramData(WindTunnelMountDiagramData data);
}
