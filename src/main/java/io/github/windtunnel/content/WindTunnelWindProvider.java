package io.github.windtunnel.content;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Bridges active tunnel blocks and airflow injectors into Sable's "what is the ambient air
 * velocity here?" query.
 * <p>
 * All returned vectors are expressed in world space so Sable can subtract them from rigid-body
 * point velocity before rotating into the craft's local coordinates.
 * <p>
 * Architecture:
 * <ul>
 * <li><b>Per-dimension tracking</b> — Each dimension has a {@link DimensionTracking} instance
 *     that holds the positions and flow fields of all active tunnels, indexed by block position
 *     for fast spatial lookup.</li>
 * <li><b>Immutable snapshots</b> — Writes to the tracking map atomically rebuild an immutable
 *     {@link DimensionSnapshot}, so the aerodynamic hot path can query without locking.</li>
 * <li><b>Sublevel-aware sampling</b> — When Sable queries from inside a sublevel, the provider
 *     converts the local probe position back to world space so tunnel volumes line up correctly,
 *     while injector airflow is keyed to the owning rigid body.</li>
 * <li><b>Thread-local scratch</b> — Reusable per-thread query state to avoid allocations in the
 *     hot path.</li>
 * </ul>
 */
public final class WindTunnelWindProvider {
    private static final double QUERY_MARGIN = 0.25D;
    private static final double FLOW_AXIS_EPSILON = 1.0E-6D;

    /** Active tunnels indexed by dimension, then by block position. */
    private static final Map<ResourceKey<Level>, DimensionTracking> ACTIVE_TUNNELS = new ConcurrentHashMap<>();
    /** Per-thread reusable query state — avoids allocations in the aerodynamic hot path. */
    private static final ThreadLocal<QueryScratch> QUERY_SCRATCH = ThreadLocal.withInitial(QueryScratch::new);

    private WindTunnelWindProvider() {
    }

    /**
     * Registers or unregisters a wind tunnel block entity in the per-dimension tracking map.
     * When active, the block entity's current flow field is stored. When inactive, the entry
     * is removed immediately so queries stop seeing stale airflow.
     */
    public static void updateTracking(WindTunnelBlockEntity blockEntity, boolean active) {
        Level lvl = blockEntity.getLevel();
        if (lvl == null || lvl.isClientSide) {
            return;
        }

        ResourceKey<Level> dimension = lvl.dimension();
        if (active) {
            WindTunnelFlowField field = blockEntity.getFlowField();
            if (field == null) {
                removeTracking(dimension, blockEntity.getBlockPos().asLong());
                return;
            }

            ACTIVE_TUNNELS.computeIfAbsent(dimension, unused -> new DimensionTracking())
                    .put(TrackedTunnel.create(blockEntity.getBlockPos().immutable(), field));
            return;
        }

        removeTracking(dimension, blockEntity.getBlockPos().asLong());
    }

    private static void removeTracking(ResourceKey<Level> dimension, long tunnelPos) {
        DimensionTracking tracking = ACTIVE_TUNNELS.get(dimension);
        if (tracking == null) {
            return;
        }

        tracking.remove(tunnelPos);
        if (tracking.isEmpty()) {
            ACTIVE_TUNNELS.remove(dimension, tracking);
        }
    }

    /** Removes a wind tunnel block entity from the tracking map. */
    public static void unregister(WindTunnelBlockEntity blockEntity) {
        updateTracking(blockEntity, false);
    }

    /**
     * Registers or unregisters an airflow injector's relative flow sources.
     * Unlike tunnels, injectors contribute relative velocities keyed to their owning sublevel
     * rather than fixed world-space volumes.
     */
    public static void updateTracking(AirflowInjectorBlockEntity blockEntity, boolean active) {
        Level lvl = blockEntity.getLevel();
        if (lvl == null || lvl.isClientSide) {
            return;
        }

        ResourceKey<Level> dimension = lvl.dimension();
        DimensionTracking tracking = ACTIVE_TUNNELS.computeIfAbsent(dimension, unused -> new DimensionTracking());
        if (active) {
            Collection<UUID> subLevelIds = blockEntity.getTrackedSubLevelIds();
            if (subLevelIds.isEmpty()) {
                tracking.removeRelative(blockEntity.getBlockPos().asLong());
            } else {
                // Build a list of relative flow sources — one per tracked sublevel.
                ArrayList<RelativeFlowSource> sources = new ArrayList<>(subLevelIds.size());
                for (UUID subLevelId : subLevelIds) {
                    RelativeFlowSource flowSource = blockEntity.createRelativeFlowSource(subLevelId);
                    if (flowSource != null) {
                        sources.add(flowSource);
                    }
                }
                if (sources.isEmpty()) {
                    tracking.removeRelative(blockEntity.getBlockPos().asLong());
                } else {
                    tracking.putRelative(blockEntity.getBlockPos().asLong(), sources);
                }
            }
        } else {
            tracking.removeRelative(blockEntity.getBlockPos().asLong());
        }

        if (tracking.isEmpty()) {
            ACTIVE_TUNNELS.remove(dimension, tracking);
        }
    }

    /** Removes an airflow injector from the tracking map. */
    public static void unregister(AirflowInjectorBlockEntity blockEntity) {
        updateTracking(blockEntity, false);
    }

    /**
     * Sable's registered wind provider entry point.
     * <p>
     * This is called from Sable's aerodynamic hot path for every lift provider point.
     * The method handles sublevel-relative vs world-space coordinate conversion internally.
     *
     * @param position The probe position (may be in sublevel-local or world space)
     * @param level    The current Minecraft level
     * @return The ambient air velocity at the probe point, or null if no wind is present
     */
    @Nullable
    public static Vector3dc getWindVelocityAt(Vector3dc position, Level level) {
        DimensionTracking tracking = ACTIVE_TUNNELS.get(level.dimension());
        if (tracking == null) {
            return null;
        }

        QueryScratch scratch = QUERY_SCRATCH.get();
        ServerSubLevel resolvedSubLevel = null;
        double sampleX = position.x();
        double sampleY = position.y();
        double sampleZ = position.z();

        // If Sable is querying from inside a sublevel, convert the local probe back to world
        // space so tunnel volumes (which are world-fixed) line up correctly.
        SubLevel containing = Sable.HELPER.getContaining(level, position);
        if (containing instanceof ServerSubLevel serverSubLevel) {
            resolvedSubLevel = serverSubLevel;
            serverSubLevel.logicalPose().transformPosition(position, scratch.worldPosition);
            sampleX = scratch.worldPosition.x;
            sampleY = scratch.worldPosition.y;
            sampleZ = scratch.worldPosition.z;
        }

        return tracking.getWindVelocityAt(sampleX, sampleY, sampleZ, resolvedSubLevel);
    }

    /**
     * Direct query with explicit sublevel context — avoids the cost of resolving the sublevel
     * from the position when the caller already knows it.
     */
    @Nullable
    public static Vector3dc getWindVelocityAt(Vector3dc position, Level level, @Nullable ServerSubLevel subLevel) {
        DimensionTracking tracking = ACTIVE_TUNNELS.get(level.dimension());
        if (tracking == null) {
            return null;
        }

        return tracking.getWindVelocityAt(position.x(), position.y(), position.z(), subLevel);
    }

    /**
     * Returns the accumulated relative velocity for a specific sublevel in local coordinates.
     * Used by airflow injectors to apply craft-relative wind.
     */
    @Nullable
    public static Vector3dc getRelativeVelocityFor(ServerSubLevel subLevel) {
        DimensionTracking tracking = ACTIVE_TUNNELS.get(subLevel.getLevel().dimension());
        if (tracking == null) {
            return null;
        }

        return tracking.getLocalRelativeVelocityFor(subLevel.getUniqueId());
    }

    /**
     * Returns the accumulated relative velocity for a specific sublevel in world coordinates.
     */
    @Nullable
    public static Vector3dc getWorldRelativeVelocityFor(ServerSubLevel subLevel) {
        DimensionTracking tracking = ACTIVE_TUNNELS.get(subLevel.getLevel().dimension());
        if (tracking == null) {
            return null;
        }

        return tracking.getWorldRelativeVelocityFor(subLevel.getUniqueId());
    }

    /**
     * Mutable builder state per dimension. Writes are synchronized and rebuild an immutable
     * snapshot so the aerodynamic hot path can query without locking.
     */
    private static final class DimensionTracking {
        private final Long2ObjectOpenHashMap<TrackedTunnel> tunnelsByPos = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<RelativeFlowSource[]> relativeByPos = new Long2ObjectOpenHashMap<>();
        /** Volatile — written under synchronization, read lock-free on the hot path. */
        private volatile DimensionSnapshot snapshot = DimensionSnapshot.EMPTY;

        private synchronized void put(TrackedTunnel tunnel) {
            tunnelsByPos.put(tunnel.posLong(), tunnel);
            rebuildSnapshot();
        }

        private synchronized void remove(long tunnelPos) {
            if (tunnelsByPos.remove(tunnelPos) == null) {
                return;
            }
            rebuildSnapshot();
        }

        private synchronized void putRelative(long sourcePos, Collection<RelativeFlowSource> sources) {
            relativeByPos.put(sourcePos, sources.toArray(RelativeFlowSource[]::new));
            rebuildSnapshot();
        }

        private synchronized void removeRelative(long sourcePos) {
            if (relativeByPos.remove(sourcePos) == null) {
                return;
            }
            rebuildSnapshot();
        }

        private boolean isEmpty() {
            return snapshot.isEmpty();
        }

        @Nullable
        private Vector3dc getWindVelocityAt(double sampleX, double sampleY, double sampleZ) {
            return snapshot.getWindVelocityAt(sampleX, sampleY, sampleZ, null, QUERY_SCRATCH.get());
        }

        @Nullable
        private Vector3dc getWindVelocityAt(double sampleX, double sampleY, double sampleZ, @Nullable ServerSubLevel subLevel) {
            return snapshot.getWindVelocityAt(sampleX, sampleY, sampleZ, subLevel, QUERY_SCRATCH.get());
        }

        @Nullable
        private Vector3dc getLocalRelativeVelocityFor(UUID subLevelId) {
            return snapshot.getLocalRelativeVelocityFor(subLevelId);
        }

        @Nullable
        private Vector3dc getWorldRelativeVelocityFor(UUID subLevelId) {
            return snapshot.getWorldRelativeVelocityFor(subLevelId);
        }

        private void rebuildSnapshot() {
            if (tunnelsByPos.isEmpty() && relativeByPos.isEmpty()) {
                snapshot = DimensionSnapshot.EMPTY;
                return;
            }

            // Absolute tunnel flow is indexed by chunk so sample queries only test nearby volumes.
            Long2ObjectOpenHashMap<ArrayList<TrackedTunnel>> chunkBuilders = new Long2ObjectOpenHashMap<>();
            for (TrackedTunnel tunnel : tunnelsByPos.values()) {
                for (long chunkKey : tunnel.chunkKeys()) {
                    ArrayList<TrackedTunnel> list = chunkBuilders.get(chunkKey);
                    if (list == null) {
                        list = new ArrayList<>();
                        chunkBuilders.put(chunkKey, list);
                    }
                    list.add(tunnel);
                }
            }

            Long2ObjectOpenHashMap<TrackedTunnel[]> chunkIndex = new Long2ObjectOpenHashMap<>(chunkBuilders.size());
            for (long chunkKey : chunkBuilders.keySet()) {
                ArrayList<TrackedTunnel> list = chunkBuilders.get(chunkKey);
                chunkIndex.put(chunkKey, list.toArray(TrackedTunnel[]::new));
            }
            ConcurrentHashMap<UUID, Vector3d> localRelativeFlows = new ConcurrentHashMap<>();
            ConcurrentHashMap<UUID, Vector3d> worldRelativeFlows = new ConcurrentHashMap<>();
            // Relative flow sources are keyed directly by target rigid body because the injector
            // affects the body uniformly rather than through a spatial field.
            Collection<RelativeFlowSource[]> relativeSources = relativeByPos.values();
            for (RelativeFlowSource[] sources : relativeSources) {
                for (RelativeFlowSource source : sources) {
                    ConcurrentHashMap<UUID, Vector3d> destination =
                            source.velocitySpace() == VelocitySpace.WORLD ? worldRelativeFlows : localRelativeFlows;
                    destination.compute(source.subLevelId(), (unused, existing) -> {
                        if (existing == null) {
                            return new Vector3d(source.velocity());
                        }
                        return existing.add(source.velocity());
                    });
                }
            }
            snapshot = new DimensionSnapshot(chunkIndex, localRelativeFlows, worldRelativeFlows);
        }
    }

    /**
     * Immutable, lock-free query state used from Sable's aerodynamic sample path.
     */
    private record DimensionSnapshot(Long2ObjectOpenHashMap<TrackedTunnel[]> chunkIndex,
                                     Map<UUID, Vector3d> localRelativeFlowsBySubLevel,
                                     Map<UUID, Vector3d> worldRelativeFlowsBySubLevel) {
        private static final DimensionSnapshot EMPTY =
                new DimensionSnapshot(new Long2ObjectOpenHashMap<>(0), Map.of(), Map.of());

        private boolean isEmpty() {
            return chunkIndex.isEmpty() && localRelativeFlowsBySubLevel.isEmpty() && worldRelativeFlowsBySubLevel.isEmpty();
        }

        @Nullable
        private Vector3dc getWindVelocityAt(double sampleX, double sampleY, double sampleZ, @Nullable ServerSubLevel subLevel,
                                            QueryScratch scratch) {
            scratch.aggregate.zero();
            boolean hasFlow = addTunnelFlow(sampleX, sampleY, sampleZ, scratch.aggregate);
            if (subLevel != null) {
                UUID subLevelId = subLevel.getUniqueId();

                Vector3dc worldRelativeVelocity = worldRelativeFlowsBySubLevel.get(subLevelId);
                if (worldRelativeVelocity != null) {
                    scratch.aggregate.add(worldRelativeVelocity);
                    hasFlow = true;
                }

                Vector3dc localRelativeVelocity = localRelativeFlowsBySubLevel.get(subLevelId);
                if (localRelativeVelocity != null) {
                    subLevel.logicalPose().transformNormal(localRelativeVelocity, scratch.transformedLocalFlow);
                    scratch.aggregate.add(scratch.transformedLocalFlow);
                    hasFlow = true;
                }
            }

            return hasFlow ? scratch.aggregate : null;
        }

        private boolean addTunnelFlow(double sampleX, double sampleY, double sampleZ, Vector3d destination) {
            long sampleChunk = ChunkPos.asLong(
                    SectionPos.blockToSectionCoord(Mth.floor(sampleX)),
                    SectionPos.blockToSectionCoord(Mth.floor(sampleZ))
            );
            TrackedTunnel[] candidateTunnels = chunkIndex.get(sampleChunk);
            double posX = 0.0D;
            double negX = 0.0D;
            double posY = 0.0D;
            double negY = 0.0D;
            double posZ = 0.0D;
            double negZ = 0.0D;
            boolean hasFlow = false;
            if (candidateTunnels != null && candidateTunnels.length > 0) {
                for (TrackedTunnel tunnel : candidateTunnels) {
                    if (!tunnel.contains(sampleX, sampleY, sampleZ)) {
                        continue;
                    }

                    double magnitude = tunnel.sampleMagnitude(sampleX, sampleY, sampleZ);
                    if (magnitude <= 0.0D) {
                        continue;
                    }

                    hasFlow = true;
                    // When multiple tunnels overlap, keep the strongest contribution on each axis
                    // instead of summing everything indiscriminately. This matches the idea of a
                    // dominant air stream rather than unbounded speed stacking.
                    if (Math.abs(tunnel.flowX()) > FLOW_AXIS_EPSILON) {
                        if (tunnel.flowX() > 0.0D) {
                            posX = Math.max(posX, magnitude);
                        } else {
                            negX = Math.max(negX, magnitude);
                        }
                    }
                    if (Math.abs(tunnel.flowY()) > FLOW_AXIS_EPSILON) {
                        if (tunnel.flowY() > 0.0D) {
                            posY = Math.max(posY, magnitude);
                        } else {
                            negY = Math.max(negY, magnitude);
                        }
                    }
                    if (Math.abs(tunnel.flowZ()) > FLOW_AXIS_EPSILON) {
                        if (tunnel.flowZ() > 0.0D) {
                            posZ = Math.max(posZ, magnitude);
                        } else {
                            negZ = Math.max(negZ, magnitude);
                        }
                    }
                }
            }
            if (!hasFlow) {
                return false;
            }

            destination.add(posX - negX, posY - negY, posZ - negZ);
            return true;
        }

        @Nullable
        private Vector3dc getLocalRelativeVelocityFor(UUID subLevelId) {
            return localRelativeFlowsBySubLevel.get(subLevelId);
        }

        @Nullable
        private Vector3dc getWorldRelativeVelocityFor(UUID subLevelId) {
            return worldRelativeFlowsBySubLevel.get(subLevelId);
        }
    }

    /**
     * Stores a uniform aircraft-mounted airflow contribution for one rigid body.
     * `LOCAL` is sublevel-local and gets subtracted after the world-to-local transform.
     * `WORLD` stays in world coordinates and gets subtracted before the transform.
     */
    public record RelativeFlowSource(long posLong, UUID subLevelId, VelocitySpace velocitySpace, Vector3d velocity) {
        public RelativeFlowSource(BlockPos pos, UUID subLevelId, VelocitySpace velocitySpace, Vector3d velocity) {
            this(pos.asLong(), subLevelId, velocitySpace, velocity);
        }
    }

    public enum VelocitySpace {
        LOCAL,
        WORLD
    }

    /**
     * Thread-local scratch state for the aerodynamic hot path.
     * The provider returns the aggregate vector directly from here to avoid a fresh allocation for
     * every single lift sample.
     */
    private static final class QueryScratch {
        private final Vector3d worldPosition = new Vector3d();
        private final Vector3d transformedLocalFlow = new Vector3d();
        private final Vector3d aggregate = new Vector3d();
    }

    private record TrackedTunnel(
            long posLong,
            AABB queryBounds,
            double flowX,
            double flowY,
            double flowZ,
            double baseSpeed,
            long[] chunkKeys
    ) {
        private static TrackedTunnel create(BlockPos pos, WindTunnelFlowField field) {
            // The provider only needs the resolved volume and direction/magnitude, not the full
            // block entity, so tracked tunnels are stored as compact immutable snapshots.
            AABB queryBounds = field.bounds().inflate(QUERY_MARGIN);
            double flowX = field.direction().getStepX();
            double flowY = field.direction().getStepY();
            double flowZ = field.direction().getStepZ();
            double baseSpeed = Math.abs(field.impulse().x) + Math.abs(field.impulse().y) + Math.abs(field.impulse().z);
            return new TrackedTunnel(
                    pos.asLong(),
                    queryBounds,
                    flowX,
                    flowY,
                    flowZ,
                    baseSpeed,
                    computeChunkKeys(queryBounds)
            );
        }

        private static long[] computeChunkKeys(AABB bounds) {
            int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(bounds.minX));
            int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(bounds.maxX));
            int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(bounds.minZ));
            int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(bounds.maxZ));

            long[] chunkKeys = new long[(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
            int index = 0;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunkKeys[index++] = ChunkPos.asLong(chunkX, chunkZ);
                }
            }
            return chunkKeys;
        }

        private boolean contains(double sampleX, double sampleY, double sampleZ) {
            return queryBounds.contains(sampleX, sampleY, sampleZ);
        }

        private double sampleMagnitude(double sampleX, double sampleY, double sampleZ) {
            return contains(sampleX, sampleY, sampleZ) ? baseSpeed : 0.0D;
        }
    }
}
