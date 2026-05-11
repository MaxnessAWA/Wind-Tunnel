package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.network.SyncHologramProjectorPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * Captures Sable point forces around hologram projector blocks and streams them to clients.
 */
@SuppressWarnings("null")
public final class HologramProjectorService {
    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE_PROJECTORS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, CaptureTicket>> PENDING_CAPTURES = new ConcurrentHashMap<>();
    private static final double CAPTURE_RADIUS_SQUARED =
            HologramProjectorBlockEntity.CAPTURE_RADIUS * HologramProjectorBlockEntity.CAPTURE_RADIUS;

    private HologramProjectorService() {
    }

    public static void register(HologramProjectorBlockEntity projector) {
        if (!(projector.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ACTIVE_PROJECTORS.computeIfAbsent(level.dimension(), unused -> ConcurrentHashMap.newKeySet())
                .add(projector.getBlockPos().immutable());
    }

    public static void unregister(HologramProjectorBlockEntity projector) {
        if (!(projector.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<BlockPos> positions = ACTIVE_PROJECTORS.get(level.dimension());
        if (positions != null) {
            positions.remove(projector.getBlockPos());
            if (positions.isEmpty()) {
                ACTIVE_PROJECTORS.remove(level.dimension(), positions);
            }
        }
        Map<BlockPos, CaptureTicket> captures = PENDING_CAPTURES.get(level.dimension());
        if (captures != null) {
            CaptureTicket ticket = captures.remove(projector.getBlockPos());
            if (ticket != null) {
                releaseTracking(ticket);
            }
            if (captures.isEmpty()) {
                PENDING_CAPTURES.remove(level.dimension(), captures);
            }
        }
    }

    public static void prePhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Set<BlockPos> positions = ACTIVE_PROJECTORS.get(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        Map<BlockPos, CaptureTicket> captures =
                PENDING_CAPTURES.computeIfAbsent(level.dimension(), unused -> new ConcurrentHashMap<>());
        long gameTime = level.getGameTime();
        for (BlockPos pos : new ArrayList<>(positions)) {
            if (captures.containsKey(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof HologramProjectorBlockEntity projector)) {
                positions.remove(pos);
                continue;
            }
            if (!projector.shouldCapture(gameTime)) {
                continue;
            }

            List<ServerSubLevel> targetSubLevels = resolveTargetSubLevels(container, pos);
            if (targetSubLevels.isEmpty()) {
                projector.markCaptureQueued(gameTime);
                syncProjector(level, projector, List.of(), List.of(), gameTime);
                continue;
            }

            projector.markCaptureQueued(gameTime);
            CaptureTicket ticket = new CaptureTicket(pos.immutable(), targetSubLevels, false);
            retainTracking(ticket);
            captures.put(pos.immutable(), ticket);
        }

        if (captures.isEmpty()) {
            PENDING_CAPTURES.remove(level.dimension(), captures);
        }
    }

    public static void postPhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Map<BlockPos, CaptureTicket> captures = PENDING_CAPTURES.get(level.dimension());
        if (captures == null || captures.isEmpty()) {
            return;
        }

        List<CompletedCapture> completed = new ArrayList<>();
        for (Map.Entry<BlockPos, CaptureTicket> entry : new ArrayList<>(captures.entrySet())) {
            CaptureTicket ticket = entry.getValue();
            if (!ticket.armed()) {
                captures.put(entry.getKey(), ticket.arm());
                continue;
            }

            captures.remove(entry.getKey(), ticket);
            if (level.getBlockEntity(ticket.projectorPos()) instanceof HologramProjectorBlockEntity projector) {
                List<HologramForceArrow> arrows = buildForceArrows(level, ticket.targetSubLevels());
                syncProjector(level, projector, arrows, ticket.targetSubLevels(), level.getGameTime());
            }
            completed.add(new CompletedCapture(ticket));
        }

        for (CompletedCapture capture : completed) {
            releaseTracking(capture.ticket());
        }

        if (captures.isEmpty()) {
            PENDING_CAPTURES.remove(level.dimension(), captures);
        }
    }

    private static List<ServerSubLevel> resolveTargetSubLevels(ServerSubLevelContainer container, BlockPos projectorPos) {
        Vector3d projectorCenter = toCenter(projectorPos);
        ServerSubLevel nearest = container.getAllSubLevels().stream()
                .filter(subLevel -> !subLevel.isRemoved())
                .filter(subLevel -> distanceSquaredToBounds(subLevel.boundingBox(), projectorCenter) <= CAPTURE_RADIUS_SQUARED)
                .min(Comparator.comparingDouble(subLevel -> distanceSquaredToBounds(subLevel.boundingBox(), projectorCenter)))
                .orElse(null);
        if (nearest == null) {
            return List.of();
        }

        List<ServerSubLevel> subLevels = new ArrayList<>();
        for (SubLevel subLevel : SubLevelHelper.getConnectedChain(nearest)) {
            if (subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()) {
                subLevels.add(serverSubLevel);
            }
        }
        return subLevels.isEmpty() ? List.of(nearest) : subLevels;
    }

    private static List<HologramForceArrow> buildForceArrows(ServerLevel level, List<ServerSubLevel> targetSubLevels) {
        return PhysicsForceArrowSampler.buildForceArrows(level, targetSubLevels,
                HologramProjectorBlockEntity.MAX_RENDERED_ARROWS);
    }

    private static void syncProjector(ServerLevel level, HologramProjectorBlockEntity projector,
                                      List<HologramForceArrow> arrows, List<ServerSubLevel> renderedSubLevels, long gameTime) {
        projector.updateHologram(arrows, renderedSubLevelIds(renderedSubLevels), gameTime);
        PacketDistributor.sendToPlayersTrackingChunk(
                level,
                new ChunkPos(projector.getBlockPos()),
                SyncHologramProjectorPayload.fromSnapshot(projector.getBlockPos(), arrows, renderedSubLevelIds(renderedSubLevels))
        );
    }

    private static void retainTracking(CaptureTicket ticket) {
        PointForceTracking.Key key = PointForceTracking.key("hologram_projector", ticket.projectorPos());
        for (ServerSubLevel subLevel : ticket.targetSubLevels()) {
            PointForceTracking.retain(subLevel, key);
        }
    }

    private static void releaseTracking(CaptureTicket ticket) {
        PointForceTracking.Key key = PointForceTracking.key("hologram_projector", ticket.projectorPos());
        for (ServerSubLevel subLevel : ticket.targetSubLevels()) {
            PointForceTracking.release(subLevel, key);
        }
    }

    private static List<UUID> renderedSubLevelIds(List<ServerSubLevel> renderedSubLevels) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (ServerSubLevel renderedSubLevel : renderedSubLevels) {
            ids.add(renderedSubLevel.getUniqueId());
        }
        return List.copyOf(ids);
    }

    private static Vector3d toCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static double distanceSquaredToBounds(BoundingBox3dc bounds, Vector3d point) {
        double dx = distanceToRange(point.x, bounds.minX(), bounds.maxX());
        double dy = distanceToRange(point.y, bounds.minY(), bounds.maxY());
        double dz = distanceToRange(point.z, bounds.minZ(), bounds.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0D;
    }

    private record CaptureTicket(BlockPos projectorPos, List<ServerSubLevel> targetSubLevels, boolean armed) {
        private CaptureTicket {
            targetSubLevels = List.copyOf(targetSubLevels);
        }

        private CaptureTicket arm() {
            return new CaptureTicket(projectorPos, targetSubLevels, true);
        }
    }

    private record CompletedCapture(CaptureTicket ticket) {
    }
}
