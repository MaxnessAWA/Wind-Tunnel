package io.github.windtunnel.content;

import io.github.windtunnel.config.WindTunnelConfig;
import io.github.windtunnel.network.SyncWindTunnelControllerPayload;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Computes connected controller+tunnel clusters via flood-fill and pushes merged settings into
 * every member tunnel segment.
 * <p>
 * This avoids each block polling the world independently every tick. Instead, state changes
 * (placement, removal, redstone, GUI edits) trigger a targeted rescan.
 * <p>
 * Merge rules for multi-controller clusters:
 * <ul>
 * <li><b>Active state</b> — Any controller that is ENABLED or POWERED makes the entire cluster
 *     active (logical OR).</li>
 * <li><b>Length / Airspeed</b> — The maximum value across all controllers wins.</li>
 * <li><b>Fan spin</b> — Any controller requesting spin keeps the blades rotating.</li>
 * </ul>
 * <p>
 * After computing the merged state, the network syncs the controller state to nearby chunk
 * trackers and pushes the merged values to every connected tunnel segment.
 */
@SuppressWarnings("null")
public final class WindTunnelNetwork {
    private WindTunnelNetwork() {
    }

    /** Rescans starting from a controller position. */
    public static void refreshFromController(Level level, BlockPos controllerPos) {
        refreshNetwork(level, controllerPos);
    }

    /** Called when a controller is removed — rescans all adjacent networks. */
    public static void refreshAfterControllerRemoved(Level level, BlockPos controllerPos) {
        refreshAdjacentNetworks(level, controllerPos);
    }

    /** Rescans the cluster containing the given tunnel position. */
    public static void refreshConnectedTunnelCluster(Level level, BlockPos originTunnelPos) {
        refreshNetwork(level, originTunnelPos);
    }

    /** Convenience alias for {@link #refreshConnectedTunnelCluster}. */
    public static void refreshTunnel(Level level, BlockPos tunnelPos) {
        refreshNetwork(level, tunnelPos);
    }

    /**
     * Rescans all networks adjacent to a removed block. Each adjacent position may start an
     * independent flood-fill, and the same network should not be scanned twice.
     */
    public static void refreshAdjacentNetworks(Level level, BlockPos originPos) {
        Set<BlockPos> refreshed = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = originPos.relative(direction);
            if (refreshed.contains(neighborPos)) {
                continue;
            }

            NetworkSnapshot network = scanNetwork(level, neighborPos);
            if (network.isEmpty()) {
                continue;
            }

            refreshed.addAll(network.controllers);
            refreshed.addAll(network.tunnels);
            applyNetworkState(level, network);
        }
    }

    /**
     * Scans and applies a network state starting from an origin position. If the origin is not a
     * valid network block, this is a no-op.
     */
    private static void refreshNetwork(Level level, BlockPos originPos) {
        NetworkSnapshot network = scanNetwork(level, originPos);
        if (network.isEmpty()) {
            return;
        }

        applyNetworkState(level, network);
    }

    /**
     * Computes the merged cluster state from all controllers in the network and pushes it to
     * every tunnel segment. Controllers also get a server-authenticated sync so nearby clients
     * converge on the same state.
     */
    private static void applyNetworkState(Level level, NetworkSnapshot network) {
        // Controllers contribute one merged state to the whole connected cluster.
        boolean controllerActive = false;
        boolean hasControllerLength = false;
        boolean hasControllerAirspeed = false;
        boolean spinFanBlades = false;
        int targetLength = Mth.clamp(
                WindTunnelControllerBlockEntity.DEFAULT_LENGTH,
                1,
                WindTunnelConfig.maxRange()
        );
        double targetAirspeed = Mth.clamp(
                WindTunnelConfig.baseAirspeed(),
                0.0D,
                WindTunnelConfig.maxAirspeed()
        );

        for (BlockPos controllerPos : network.controllers) {
            BlockState state = level.getBlockState(controllerPos);
            if (!(state.getBlock() instanceof WindTunnelControllerBlock)) {
                continue;
            }

            // Any ENABLED or POWERED controller makes the entire cluster active.
            if (state.getValue(WindTunnelControllerBlock.ENABLED)
                    || state.getValue(WindTunnelControllerBlock.POWERED)) {
                controllerActive = true;
            }

            BlockEntity blockEntity = level.getBlockEntity(controllerPos);
            if (blockEntity instanceof WindTunnelControllerBlockEntity controllerBlockEntity) {
                int controllerLength = controllerBlockEntity.getConfiguredTargetLength();
                double controllerAirspeed = controllerBlockEntity.getConfiguredTargetAirspeed();
                // Sync to nearby chunk trackers so open GUI screens converge on one state.
                syncController(level, controllerPos, controllerBlockEntity, state.getValue(WindTunnelControllerBlock.ENABLED));

                // Merge: use the maximum length/airspeed across all controllers.
                targetLength = hasControllerLength
                        ? Math.max(targetLength, controllerLength)
                        : controllerLength;
                targetAirspeed = hasControllerAirspeed
                        ? Math.max(targetAirspeed, controllerAirspeed)
                        : controllerAirspeed;
                // If multiple controllers are chained together, keep the blades spinning whenever
                // any controller in the cluster requests it.
                spinFanBlades |= controllerBlockEntity.shouldSpinFanBlades();
                hasControllerLength = true;
                hasControllerAirspeed = true;
            }
        }

        for (BlockPos tunnelPos : network.tunnels) {
            applyTunnelState(level, tunnelPos, controllerActive, targetLength, targetAirspeed, spinFanBlades);
        }
    }

    /**
     * Pushes the merged cluster state to a single tunnel segment.
     * Sets POWERED based on local redstone OR controller active status, and forwards
     * controller settings to the block entity.
     */
    private static void applyTunnelState(Level level, BlockPos tunnelPos, boolean controllerActive, int targetLength, double targetAirspeed,
                                         boolean spinFanBlades) {
        int clampedLength = Mth.clamp(targetLength, 1, WindTunnelConfig.maxRange());
        double clampedAirspeed = Mth.clamp(targetAirspeed, 0.0D, WindTunnelConfig.maxAirspeed());
        BlockState state = level.getBlockState(tunnelPos);
        if (!(state.getBlock() instanceof WindTunnelBlock)) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(tunnelPos);
        boolean settingsChanged = false;
        if (blockEntity instanceof WindTunnelBlockEntity windTunnelBlockEntity) {
            settingsChanged = !windTunnelBlockEntity.matchesControllerSettings(clampedLength, clampedAirspeed, spinFanBlades);
            if (settingsChanged) {
                windTunnelBlockEntity.applyControllerSettings(clampedLength, clampedAirspeed, spinFanBlades);
            }
        }

        // POWERED = local redstone OR controller active (logical OR).
        boolean shouldBePowered = level.hasNeighborSignal(tunnelPos) || controllerActive;
        if (state.getValue(WindTunnelBlock.POWERED) != shouldBePowered) {
            level.setBlock(tunnelPos, state.setValue(WindTunnelBlock.POWERED, shouldBePowered), Block.UPDATE_CLIENTS);
            if (blockEntity instanceof WindTunnelBlockEntity windTunnelBlockEntity) {
                windTunnelBlockEntity.onTunnelStateChanged();
            }
        } else if (settingsChanged && blockEntity instanceof WindTunnelBlockEntity windTunnelBlockEntity) {
            windTunnelBlockEntity.onTunnelStateChanged();
        }
    }

    /**
     * Flood-fill scan from an origin position to discover all connected controller and tunnel
     * blocks. Returns {@link NetworkSnapshot#EMPTY} if the origin is not a network block.
     */
    private static NetworkSnapshot scanNetwork(Level level, BlockPos originPos) {
        BlockState originState = level.getBlockState(originPos);
        if (!isNetworkBlock(originState)) {
            return NetworkSnapshot.EMPTY;
        }

        // A simple flood fill is enough because the network is purely face-connected blocks with
        // no directional graph rules.
        Set<BlockPos> controllers = new HashSet<>();
        Set<BlockPos> tunnels = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        frontier.add(originPos);
        visited.add(originPos);

        while (!frontier.isEmpty()) {
            BlockPos current = frontier.removeFirst();
            BlockState currentState = level.getBlockState(current);

            if (currentState.getBlock() instanceof WindTunnelControllerBlock) {
                controllers.add(current);
            } else if (currentState.getBlock() instanceof WindTunnelBlock) {
                tunnels.add(current);
            } else {
                continue;
            }

            // Expand to all six face-adjacent neighbors.
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = current.relative(direction);
                if (!visited.add(neighborPos)) {
                    continue;
                }

                BlockState neighborState = level.getBlockState(neighborPos);
                if (isNetworkBlock(neighborState)) {
                    frontier.addLast(neighborPos);
                }
            }
        }

        return new NetworkSnapshot(controllers, tunnels);
    }

    /** A block is part of the tunnel network if it's a Wind Tunnel or Controller. */
    private static boolean isNetworkBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof WindTunnelBlock || block instanceof WindTunnelControllerBlock;
    }

    /**
     * Sends a server-authoritative controller state sync to all players tracking the chunk.
     * This keeps nearby clients in sync even if they are not the player who edited the controller.
     */
    private static void syncController(Level level, BlockPos controllerPos, WindTunnelControllerBlockEntity controller, boolean enabled) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        PacketDistributor.sendToPlayersTrackingChunk(
                serverLevel,
                new net.minecraft.world.level.ChunkPos(controllerPos),
                new SyncWindTunnelControllerPayload(
                        controllerPos,
                        controller.getConfiguredTargetLength(),
                        controller.getConfiguredTargetAirspeed(),
                        controller.shouldSpinFanBlades(),
                        enabled,
                        controller.getConfiguredMaxLength(),
                        controller.getConfiguredMaxAirspeed()
                )
        );
    }

    private record NetworkSnapshot(Set<BlockPos> controllers, Set<BlockPos> tunnels) {
        private static final NetworkSnapshot EMPTY = new NetworkSnapshot(Set.of(), Set.of());

        private boolean isEmpty() {
            return this.controllers.isEmpty() && this.tunnels.isEmpty();
        }
    }
}
