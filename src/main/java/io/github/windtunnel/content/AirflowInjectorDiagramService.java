package io.github.windtunnel.content;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.network.SyncAirflowInjectorDiagramPayload;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * On-demand force-diagram capture service for the aircraft airflow injector.
 * <p>
 * Unlike the mount, the injector does not need a persistent per-tick physics hook. This service
 * only arms point-force tracking after a client explicitly requests a new diagram frame via the
 * {@link RequestAirflowInjectorDiagramPayload} network packet.
 * <p>
 * The capture workflow:
 * <ol>
 * <li>Client opens the injector screen and presses the "refresh diagram" button.</li>
 * <li>A {@code RequestAirflowInjectorDiagramPayload} is sent to the server.</li>
 * <li>This service queues a {@link PendingDiagramRequest} for the next physics tick.</li>
 * <li>In {@code prePhysicsTick}, per-point force tracking is enabled on the target sublevels.</li>
 * <li>In {@code postPhysicsTick}, the accumulated point forces are read, packed into a
 *     {@link WindTunnelMountDiagramData}, and sent back to the requesting clients.</li>
 * </ol>
 */
@SuppressWarnings("null")
public final class AirflowInjectorDiagramService {
    /** Queued diagram requests waiting for the next physics tick. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, PendingDiagramRequest>> PENDING_REQUESTS = new ConcurrentHashMap<>();
    /** Armed captures: tracking was enabled in pre-tick, data will be read in post-tick. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, DiagramCaptureTicket>> PENDING_CAPTURES = new ConcurrentHashMap<>();

    private AirflowInjectorDiagramService() {
    }

    /**
     * Queues a diagram capture request for the next physics tick.
     * Called from the network handler when a client presses the refresh button.
     */
    public static void requestCapture(ServerPlayer player, AirflowInjectorBlockEntity injector) {
        if (!(injector.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        Map<BlockPos, PendingDiagramRequest> requests =
                PENDING_REQUESTS.computeIfAbsent(dimension, unused -> new ConcurrentHashMap<>());
        BlockPos pos = injector.getBlockPos().immutable();
        // If multiple players request diagrams for the same injector in the same tick, merge
        // their requests into one capture that sends results to all of them.
        requests.compute(pos, (ignored, existing) -> existing == null
                ? PendingDiagramRequest.create(pos, player)
                : existing.withPlayer(player));
    }

    /**
     * Pre-physics tick: processes queued requests by resolving the reference sublevel, determining
     * which sublevels to sample, and enabling individual queued force tracking.
     */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Map<BlockPos, PendingDiagramRequest> requests = PENDING_REQUESTS.get(level.dimension());
        if (requests == null || requests.isEmpty()) {
            return;
        }

        for (Map.Entry<BlockPos, PendingDiagramRequest> entry : new ArrayList<>(requests.entrySet())) {
            requests.remove(entry.getKey(), entry.getValue());
            PendingDiagramRequest request = entry.getValue();
            if (!(level.getBlockEntity(request.injectorPos()) instanceof AirflowInjectorBlockEntity injector)) {
                sendToPlayers(request.injectorPos(), WindTunnelMountDiagramData.EMPTY, request.players());
                continue;
            }

            ServerSubLevel referenceSubLevel = resolveReferenceSubLevel(injector);
            if (referenceSubLevel == null) {
                sendToPlayers(injector.getBlockPos(), WindTunnelMountDiagramData.EMPTY, request.players());
                continue;
            }

            List<ServerSubLevel> targetSubLevels = resolveMeasuredSubLevels(referenceSubLevel, injector.getApplicationMode());
            if (targetSubLevels.isEmpty()) {
                sendToPlayers(injector.getBlockPos(), WindTunnelMountDiagramData.EMPTY, request.players());
                continue;
            }

            // Enable individual point-force tracking on all target sublevels for this frame.
            for (ServerSubLevel targetSubLevel : targetSubLevels) {
                targetSubLevel.enableIndividualQueuedForcesTracking(true);
            }

            Map<BlockPos, DiagramCaptureTicket> captures =
                    PENDING_CAPTURES.computeIfAbsent(level.dimension(), unused -> new ConcurrentHashMap<>());
            captures.put(injector.getBlockPos().immutable(),
                    new DiagramCaptureTicket(injector.getBlockPos().immutable(), referenceSubLevel, targetSubLevels, request.players(), false));
        }

        if (requests.isEmpty()) {
            PENDING_REQUESTS.remove(level.dimension(), requests);
        }
    }

    /**
     * Post-physics tick: reads the accumulated point forces, builds diagram data, sends to
     * players, and disables tracking on sublevels that are no longer needed.
     */
    public static void postPhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Map<BlockPos, DiagramCaptureTicket> captures = PENDING_CAPTURES.get(level.dimension());
        if (captures == null || captures.isEmpty()) {
            return;
        }

        Set<ServerSubLevel> subLevelsToDisable = new LinkedHashSet<>();
        for (Map.Entry<BlockPos, DiagramCaptureTicket> entry : new ArrayList<>(captures.entrySet())) {
            DiagramCaptureTicket ticket = entry.getValue();
            // First frame: arm the capture. Actual data collection happens next tick.
            if (!ticket.armed()) {
                captures.put(entry.getKey(), ticket.arm());
                continue;
            }

            // Second frame: read data, send to players, disable tracking.
            WindTunnelMountDiagramData data = WindTunnelMountDiagramData.EMPTY;
            if (level.getBlockEntity(ticket.injectorPos()) instanceof AirflowInjectorBlockEntity injector) {
                ServerSubLevel referenceSubLevel = resolveReferenceSubLevel(injector);
                if (referenceSubLevel != null) {
                    List<ServerSubLevel> sampledSubLevels = resolveMeasuredSubLevels(referenceSubLevel, injector.getApplicationMode());
                    data = buildDiagramData(referenceSubLevel, sampledSubLevels);
                }
            }

            sendToPlayers(ticket.injectorPos(), data, ticket.players());
            subLevelsToDisable.addAll(ticket.targetSubLevels());
            captures.remove(entry.getKey(), ticket);
        }

        // Disable tracking on sublevels that are no longer captured by any pending ticket.
        Set<ServerSubLevel> stillTracked = new LinkedHashSet<>();
        for (DiagramCaptureTicket remaining : captures.values()) {
            stillTracked.addAll(remaining.targetSubLevels());
        }
        for (ServerSubLevel subLevel : subLevelsToDisable) {
            if (!stillTracked.contains(subLevel)) {
                subLevel.enableIndividualQueuedForcesTracking(false);
            }
        }

        if (captures.isEmpty()) {
            PENDING_CAPTURES.remove(level.dimension(), captures);
        }
    }

    private static void sendToPlayers(BlockPos injectorPos, WindTunnelMountDiagramData data, List<ServerPlayer> players) {
        SyncAirflowInjectorDiagramPayload payload = SyncAirflowInjectorDiagramPayload.fromData(injectorPos, data);
        for (ServerPlayer player : players) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * Resolves the reference sublevel from the injector's position.
     * The injector must be placed ON the aircraft to have a valid reference sublevel.
     */
    private static ServerSubLevel resolveReferenceSubLevel(AirflowInjectorBlockEntity injector) {
        if (injector.getLevel() == null || injector.getLevel().isClientSide) {
            return null;
        }
        if (Sable.HELPER.getContaining(injector.getLevel(), injector.getBlockPos()) instanceof ServerSubLevel subLevel) {
            return subLevel;
        }
        return null;
    }

    /**
     * Resolves which sublevels to sample based on the injector's application mode.
     * SINGLE_BODY: only the reference sublevel (the one the injector is placed on).
     * MULTI_BODY: the reference sublevel plus all connected sublevels in the same chain.
     */
    private static List<ServerSubLevel> resolveMeasuredSubLevels(ServerSubLevel referenceSubLevel,
                                                                 AirflowInjectorBlockEntity.ApplicationMode applicationMode) {
        if (applicationMode == AirflowInjectorBlockEntity.ApplicationMode.MULTI_BODY) {
            Collection<SubLevel> connected = SubLevelHelper.getConnectedChain(referenceSubLevel);
            LinkedHashSet<ServerSubLevel> subLevels = new LinkedHashSet<>();
            for (SubLevel subLevel : connected) {
                if (subLevel instanceof ServerSubLevel serverSubLevel) {
                    subLevels.add(serverSubLevel);
                }
            }
            if (!subLevels.isEmpty()) {
                return new ArrayList<>(subLevels);
            }
        }
        return List.of(referenceSubLevel);
    }

    /**
     * Builds the diagram data from accumulated point forces across all sampled sublevels.
     * Forces are normalized into the reference sublevel's local frame before serialization.
     */
    private static WindTunnelMountDiagramData buildDiagramData(ServerSubLevel referenceSubLevel, List<ServerSubLevel> sampledSubLevels) {
        if (sampledSubLevels.isEmpty()) {
            return WindTunnelMountDiagramData.EMPTY;
        }

        // The injector reuses the same client diagram format as the mount, so all forces are
        // normalized into the reference sublevel's local frame before being serialized.
        double timeStep = resolveTimeStep(referenceSubLevel.getLevel());
        Object2ObjectOpenHashMap<dev.ryanhcode.sable.api.physics.force.ForceGroup, List<QueuedForceGroup.PointForce>> groupedForces =
                new Object2ObjectOpenHashMap<>();
        List<UUID> renderedSubLevelIds = new ArrayList<>(sampledSubLevels.size());
        double totalMass = 0.0D;
        Vector3d weightedCenterWorld = new Vector3d();
        Pose3d referencePose = new Pose3d(referenceSubLevel.logicalPose());

        for (ServerSubLevel sampledSubLevel : sampledSubLevels) {
            renderedSubLevelIds.add(sampledSubLevel.getUniqueId());
            double mass = sampledSubLevel.getMassTracker().getMass();
            totalMass += mass;

            Vector3d centerWorld = sampledSubLevel.logicalPose().transformPosition(
                    sampledSubLevel.getMassTracker().getCenterOfMass(),
                    new Vector3d()
            );
            weightedCenterWorld.fma(mass, centerWorld);

            Object2ObjectMap<?, QueuedForceGroup> queuedForceGroups = sampledSubLevel.getQueuedForceGroups();
            if (queuedForceGroups == null) {
                continue;
            }

            for (Map.Entry<?, QueuedForceGroup> forceEntry : queuedForceGroups.entrySet()) {
                if (!(forceEntry.getKey() instanceof dev.ryanhcode.sable.api.physics.force.ForceGroup forceGroup)) {
                    continue;
                }
                List<QueuedForceGroup.PointForce> destination =
                        groupedForces.computeIfAbsent(forceGroup, ignored -> new ArrayList<>());
                for (QueuedForceGroup.PointForce pointForce : forceEntry.getValue().getRecordedPointForces()) {
                    Vector3d pointWorld = sampledSubLevel.logicalPose().transformPosition(pointForce.point(), new Vector3d());
                    Vector3d forceWorld = sampledSubLevel.logicalPose().transformNormal(pointForce.force(), new Vector3d()).div(timeStep);
                    // The client renders the entire aircraft in the reference sublevel's local
                    // coordinates, so every point force must be converted into that same frame.
                    Vector3d pointReference = referencePose.transformPositionInverse(pointWorld, new Vector3d());
                    Vector3d forceReference = referencePose.transformNormalInverse(forceWorld, new Vector3d());
                    destination.add(WindTunnelMountDiagramData.pointForce(pointReference, forceReference));
                }
            }
        }

        if (totalMass <= 0.0D) {
            return WindTunnelMountDiagramData.EMPTY;
        }

        Vector3d centerWorld = weightedCenterWorld.div(totalMass);
        Vector3d centerLocal = referencePose.transformPositionInverse(centerWorld, new Vector3d());
        Vector3d localGravity = new Vector3d(Direction.DOWN.getStepX(), Direction.DOWN.getStepY(), Direction.DOWN.getStepZ());
        referencePose.transformNormalInverse(localGravity);
        localGravity.mul(9.8D * totalMass);
        groupedForces.put(ForceGroups.GRAVITY.get(), List.of(WindTunnelMountDiagramData.pointForce(centerLocal, localGravity)));

        return new WindTunnelMountDiagramData(
                groupedForces,
                totalMass,
                referenceSubLevel.getUniqueId(),
                renderedSubLevelIds,
                centerLocal
        );
    }

    private static double resolveTimeStep(ServerLevel level) {
        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        if (physicsSystem == null) {
            return 0.05D;
        }
        return 0.05D / (double) physicsSystem.getConfig().substepsPerTick;
    }

    /**
     * Coalesces repeated capture requests from multiple viewers of the same injector so only one
     * force-tracking pass is scheduled per physics frame.
     */
    private record PendingDiagramRequest(BlockPos injectorPos, List<ServerPlayer> players) {
        private static PendingDiagramRequest create(BlockPos injectorPos, ServerPlayer player) {
            return new PendingDiagramRequest(injectorPos, List.of(player));
        }

        private PendingDiagramRequest withPlayer(ServerPlayer player) {
            LinkedHashSet<UUID> seen = new LinkedHashSet<>();
            List<ServerPlayer> merged = new ArrayList<>(players.size() + 1);
            for (ServerPlayer existing : players) {
                if (seen.add(existing.getUUID())) {
                    merged.add(existing);
                }
            }
            if (seen.add(player.getUUID())) {
                merged.add(player);
            }
            return new PendingDiagramRequest(injectorPos, List.copyOf(merged));
        }
    }

    /**
     * One armed capture spanning a single completed physics step.
     */
    private record DiagramCaptureTicket(
            BlockPos injectorPos,
            ServerSubLevel referenceSubLevel,
            List<ServerSubLevel> targetSubLevels,
            List<ServerPlayer> players,
            boolean armed
    ) {
        private DiagramCaptureTicket arm() {
            return new DiagramCaptureTicket(injectorPos, referenceSubLevel, targetSubLevels, players, true);
        }
    }
}
