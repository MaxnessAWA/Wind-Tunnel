package io.github.windtunnel.content;

import io.github.windtunnel.client.HologramProjectorWorldState;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores the latest client-side hologram snapshot for a world-space projector.
 */
public class HologramProjectorBlockEntity extends BlockEntity {
    public static final int CAPTURE_INTERVAL_TICKS = 4;
    public static final double CAPTURE_RADIUS = 32.0D;
    public static final int MAX_RENDERED_ARROWS = 96;

    private List<HologramForceArrow> forceArrows = List.of();
    private List<UUID> renderedSubLevelIds = List.of();
    private long lastSyncTick = Long.MIN_VALUE;
    private long nextCaptureGameTime;

    public HologramProjectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.HOLOGRAM_PROJECTOR.get(), pos, blockState);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            HologramProjectorService.register(this);
        } else if (level != null && level.isClientSide) {
            HologramProjectorWorldState.markKnown(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel) {
            HologramProjectorService.unregister(this);
        } else if (level != null && level.isClientSide) {
            HologramProjectorWorldState.remove(worldPosition);
        }
        super.setRemoved();
    }

    public boolean shouldCapture(long gameTime) {
        return gameTime >= nextCaptureGameTime;
    }

    public void markCaptureQueued(long gameTime) {
        nextCaptureGameTime = gameTime + CAPTURE_INTERVAL_TICKS;
    }

    public List<HologramForceArrow> getForceArrows() {
        return forceArrows;
    }

    public List<UUID> getRenderedSubLevelIds() {
        return renderedSubLevelIds;
    }

    public void updateHologram(List<HologramForceArrow> forceArrows, List<UUID> renderedSubLevelIds, long gameTime) {
        this.forceArrows = forceArrows == null ? List.of() : List.copyOf(forceArrows);
        this.renderedSubLevelIds = renderedSubLevelIds == null ? List.of() : List.copyOf(renderedSubLevelIds);
        this.lastSyncTick = gameTime;
    }

    public long getLastSyncTick() {
        return lastSyncTick;
    }
}
