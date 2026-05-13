package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.network.SyncGogglesForceVectorsPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * Streams nearby Sable point-force vectors to players wearing Aeronautics aviator's goggles.
 */
@SuppressWarnings("null")
public final class GogglesForceVectorService {
    private static final Map<ResourceKey<Level>, Map<UUID, CaptureTicket>> PENDING_CAPTURES = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<UUID, Long>> LAST_CAPTURE_TICKS = new ConcurrentHashMap<>();
    private static final String TRACKING_OWNER_PREFIX = "goggles_force_vectors/";
    private static final int CAPTURE_INTERVAL_TICKS = 4;
    private static final int MAX_RENDERED_ARROWS = 128;
    private static final double CAPTURE_RADIUS = 32.0D;
    private static final double CAPTURE_RADIUS_SQUARED = CAPTURE_RADIUS * CAPTURE_RADIUS;

    private GogglesForceVectorService() {
    }

    public static void prePhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        if (level.players().isEmpty()) {
            cleanupLevel(level.dimension());
            return;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        Map<UUID, CaptureTicket> captures =
                PENDING_CAPTURES.computeIfAbsent(dimension, unused -> new ConcurrentHashMap<>());
        Map<UUID, Long> lastCaptureTicks =
                LAST_CAPTURE_TICKS.computeIfAbsent(dimension, unused -> new ConcurrentHashMap<>());
        Set<UUID> presentPlayers = new HashSet<>();

        for (ServerPlayer player : level.players()) {
            UUID playerId = player.getUUID();
            presentPlayers.add(playerId);
            if (!AeronauticsCompat.isWearingAviatorsGoggles(player) || captures.containsKey(playerId)) {
                continue;
            }

            long gameTime = level.getGameTime();
            Long previousCaptureTick = lastCaptureTicks.get(playerId);
            if (previousCaptureTick != null && gameTime - previousCaptureTick < CAPTURE_INTERVAL_TICKS) {
                continue;
            }
            lastCaptureTicks.put(playerId, gameTime);

            List<List<ServerSubLevel>> targetSystems = resolveNearbySubLevelSystems(container, player);
            if (targetSystems.isEmpty()) {
                PacketDistributor.sendToPlayer(player, SyncGogglesForceVectorsPayload.empty());
                continue;
            }

            PointForceTracking.Key trackingKey = PointForceTracking.key(
                    TRACKING_OWNER_PREFIX + playerId,
                    player.blockPosition()
            );
            CaptureTicket ticket = new CaptureTicket(playerId, trackingKey, targetSystems, false);
            retainTracking(ticket);
            captures.put(playerId, ticket);
        }

        lastCaptureTicks.keySet().removeIf(playerId -> !presentPlayers.contains(playerId));
        if (captures.isEmpty()) {
            PENDING_CAPTURES.remove(dimension, captures);
        }
        if (lastCaptureTicks.isEmpty()) {
            LAST_CAPTURE_TICKS.remove(dimension, lastCaptureTicks);
        }
    }

    public static void postPhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        ResourceKey<Level> dimension = level.dimension();
        Map<UUID, CaptureTicket> captures = PENDING_CAPTURES.get(dimension);
        if (captures == null || captures.isEmpty()) {
            return;
        }

        List<CaptureTicket> completed = new ArrayList<>();
        for (Map.Entry<UUID, CaptureTicket> entry : new ArrayList<>(captures.entrySet())) {
            CaptureTicket ticket = entry.getValue();
            if (!ticket.armed()) {
                captures.put(entry.getKey(), ticket.arm());
                continue;
            }

            captures.remove(entry.getKey(), ticket);
            syncCompletedCapture(level, ticket);
            completed.add(ticket);
        }

        for (CaptureTicket ticket : completed) {
            releaseTracking(ticket);
        }

        if (captures.isEmpty()) {
            PENDING_CAPTURES.remove(dimension, captures);
        }
    }

    private static void syncCompletedCapture(ServerLevel level, CaptureTicket ticket) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(ticket.playerId());
        if (player == null || player.level() != level || !AeronauticsCompat.isWearingAviatorsGoggles(player)) {
            return;
        }

        List<HologramForceArrow> arrows = buildForceArrowsBySystem(level, ticket.targetSystems());
        PacketDistributor.sendToPlayer(player, SyncGogglesForceVectorsPayload.fromArrows(arrows));
    }

    private static List<List<ServerSubLevel>> resolveNearbySubLevelSystems(ServerSubLevelContainer container, ServerPlayer player) {
        Vector3d playerCenter = new Vector3d(player.getX(), player.getY() + player.getBbHeight() * 0.5D, player.getZ());
        List<List<ServerSubLevel>> targetSystems = new ArrayList<>();
        Set<ServerSubLevel> visited = new HashSet<>();
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved() || !visited.add(subLevel)) {
                continue;
            }
            if (distanceSquaredToBounds(subLevel.boundingBox(), playerCenter) <= CAPTURE_RADIUS_SQUARED) {
                List<ServerSubLevel> systemSubLevels = resolveLiveConnectedSystem(subLevel, visited);
                if (!systemSubLevels.isEmpty()) {
                    targetSystems.add(systemSubLevels);
                }
            }
        }
        return targetSystems;
    }

    private static List<ServerSubLevel> resolveLiveConnectedSystem(ServerSubLevel seed, Set<ServerSubLevel> visited) {
        LinkedHashSet<ServerSubLevel> systemSubLevels = new LinkedHashSet<>();
        for (SubLevel connectedSubLevel : SubLevelHelper.getConnectedChain(seed)) {
            if (connectedSubLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()) {
                visited.add(serverSubLevel);
                systemSubLevels.add(serverSubLevel);
            }
        }
        if (systemSubLevels.isEmpty() && !seed.isRemoved()) {
            systemSubLevels.add(seed);
        }
        return List.copyOf(systemSubLevels);
    }

    private static List<HologramForceArrow> buildForceArrowsBySystem(ServerLevel level, List<List<ServerSubLevel>> targetSystems) {
        if (targetSystems.isEmpty()) {
            return List.of();
        }

        List<HologramForceArrow> arrows = new ArrayList<>();
        for (List<ServerSubLevel> targetSystem : targetSystems) {
            List<ServerSubLevel> liveSubLevels = targetSystem.stream()
                    .filter(subLevel -> !subLevel.isRemoved())
                    .toList();
            arrows.addAll(PhysicsForceArrowSampler.buildForceArrows(level, liveSubLevels, MAX_RENDERED_ARROWS));
        }
        if (arrows.size() <= MAX_RENDERED_ARROWS) {
            return arrows;
        }

        arrows.sort(Comparator.comparingDouble((HologramForceArrow arrow) -> arrow.force().lengthSquared()).reversed());
        return List.copyOf(arrows.subList(0, MAX_RENDERED_ARROWS));
    }

    private static void retainTracking(CaptureTicket ticket) {
        for (ServerSubLevel subLevel : ticket.targetSubLevels()) {
            PointForceTracking.retain(subLevel, ticket.trackingKey());
        }
    }

    private static void releaseTracking(CaptureTicket ticket) {
        for (ServerSubLevel subLevel : ticket.targetSubLevels()) {
            PointForceTracking.release(subLevel, ticket.trackingKey());
        }
    }

    private static void cleanupLevel(ResourceKey<Level> dimension) {
        Map<UUID, CaptureTicket> captures = PENDING_CAPTURES.remove(dimension);
        if (captures != null) {
            for (CaptureTicket ticket : captures.values()) {
                releaseTracking(ticket);
            }
        }
        LAST_CAPTURE_TICKS.remove(dimension);
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

    private record CaptureTicket(
            UUID playerId,
            PointForceTracking.Key trackingKey,
            List<List<ServerSubLevel>> targetSystems,
            boolean armed
    ) {
        private CaptureTicket {
            targetSystems = targetSystems.stream()
                    .map(List::copyOf)
                    .toList();
        }

        private List<ServerSubLevel> targetSubLevels() {
            return targetSystems.stream()
                    .flatMap(List::stream)
                    .distinct()
                    .toList();
        }

        private CaptureTicket arm() {
            return new CaptureTicket(playerId, trackingKey, targetSystems, true);
        }
    }
}
