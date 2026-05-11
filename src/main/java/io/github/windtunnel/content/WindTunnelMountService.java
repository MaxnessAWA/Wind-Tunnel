package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.free.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.network.SyncWindTunnelMountDiagramPayload;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Server-side physics hook for the measurement stand.
 * <p>
 * It is responsible for two independent jobs:
 * <ol>
 * <li><b>Before each physics step</b> — Place a bound aircraft at the requested test pose and
 *     freeze velocity using Sable's physics constraint API. This ensures the aircraft stays
 *     exactly where the measurement stand wants it during the aerodynamic force computation.</li>
 * <li><b>After the step</b> — Read the lift/drag force groups accumulated by Sable and convert
 *     them into aerodynamic totals (lift, drag, side force, pitch/roll/yaw moments) relative to
 *     the configured flow direction.</li>
 * </ol>
 * <p>
 * The service is driven from the global Sable physics tick, so mounts are tracked by world
 * position instead of using individual block-entity tickers. This keeps the per-tick cost
 * proportional to the number of active mounts, not the number of loaded blocks.
 */
@SuppressWarnings("null")
public final class WindTunnelMountService {
    /** Active mounts indexed by dimension, then by block position. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> ACTIVE_MOUNTS = new ConcurrentHashMap<>();
    /**
     * Diagram capture spans two physics phases: arm tracking before the step, then read the
     * recorded point forces after the step.
     */
    private static final Map<ServerSubLevel, DiagramCaptureTicket> PENDING_DIAGRAM_CAPTURES = new ConcurrentHashMap<>();
    /**
     * Multi-body locking replays stored relative transforms so secondary bodies keep their
     * original relation to the selected reference body while the stand moves the structure.
     */
    private static final Map<ResourceKey<Level>, Map<BlockPos, MultiBodyPoseSnapshot>> MULTI_BODY_POSE_SNAPSHOTS = new ConcurrentHashMap<>();
    /**
     * Sublevels currently driven kinematically by the stand through the teleport/velocity-lock
     * path. Custom airfoil forces should ignore self-motion for these bodies so the stand's servo
     * corrections do not get reinterpreted as aerodynamic relative wind.
     */
    private static final Map<ResourceKey<Level>, Set<UUID>> KINEMATICALLY_DRIVEN_SUBLEVELS = new ConcurrentHashMap<>();

    // ---- Physics constants ----
    private static final Vector3d WORLD_UP = new Vector3d(0.0D, 1.0D, 0.0D);
    private static final Vector3d WORLD_NORTH = new Vector3d(0.0D, 0.0D, -1.0D);
    private static final double AXIS_EPSILON = 1.0E-6D;
    private static final double GRAVITY_ACCELERATION = 11.0D;
    /** Constraint parameters for locking the aircraft in place during measurement. */
    private static final double SUPPORT_LINEAR_STIFFNESS = 10000.0D;
    private static final double SUPPORT_LINEAR_DAMPING = 850.0D;
    private static final double SUPPORT_ANGULAR_LOCK_STIFFNESS = 10000.0D;
    private static final double SUPPORT_ANGULAR_LOCK_DAMPING = 850.0D;
    private static final double SUPPORT_ANGULAR_FREE_DAMPING = 4.5D;

    private WindTunnelMountService() {
    }

    /** Registers a mount block entity with the global active mount tracker. */
    public static void register(WindTunnelMountBlockEntity mount) {
        if (!(mount.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ACTIVE_MOUNTS.computeIfAbsent(level.dimension(), unused -> ConcurrentHashMap.newKeySet()).add(mount.getBlockPos().immutable());
    }

    /** Removes a mount from the global tracker and cleans up related state. */
    public static void unregister(WindTunnelMountBlockEntity mount) {
        if (!(mount.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Set<BlockPos> positions = ACTIVE_MOUNTS.get(level.dimension());
        if (positions == null) {
            return;
        }

        positions.remove(mount.getBlockPos());
        clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
        if (positions.isEmpty()) {
            ACTIVE_MOUNTS.remove(level.dimension());
        }
    }

    /**
     * Called when an interface block is wrenched on the aircraft.
     * Updates the facing direction for all stands currently bound to that marker.
     */
    public static void updateBindingsForInterfaceRotation(Level level, BlockPos interfacePos, Direction newFacing) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        Set<BlockPos> positions = ACTIVE_MOUNTS.get(serverLevel.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }

        for (BlockPos mountPos : new ArrayList<>(positions)) {
            WindTunnelMountBlockEntity mount = getMount(serverLevel, mountPos, positions);
            if (mount == null || !mount.isSubLevelBinding()) {
                continue;
            }
            if (!mount.getInterfacePos().equals(interfacePos)) {
                continue;
            }

            // The interface block defines the stand-side body axes, so an in-place wrench rotate
            // must update every stand currently bound to that marker.
            mount.updateInterfaceFacing(newFacing);
        }
    }

    /**
     * Pre-physics tick: called before Sable steps the simulation.
     * <p>
     * For each active mount:
     * <ol>
     * <li>Validates the binding (entity or sublevel still exists).</li>
     * <li>If the mount is on an aircraft, releases the constraint immediately.</li>
     * <li>If locked, applies the test pose and freezes velocity via physics constraints.</li>
     * <li>If a diagram capture was requested, arms per-point force tracking.</li>
     * </ol>
     */
    public static void prePhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Set<BlockPos> positions = ACTIVE_MOUNTS.get(level.dimension());
        if (positions == null || positions.isEmpty()) {
            KINEMATICALLY_DRIVEN_SUBLEVELS.remove(level.dimension());
            return;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            KINEMATICALLY_DRIVEN_SUBLEVELS.remove(level.dimension());
            return;
        }

        Set<UUID> drivenSubLevelIds = new LinkedHashSet<>();

        for (BlockPos pos : new ArrayList<>(positions)) {
            WindTunnelMountBlockEntity mount = getMount(level, pos, positions);
            if (mount == null) {
                continue;
            }

            // Mounts placed on aircraft cannot act as stands — release and skip.
            if (mount.isPlacedOnAircraft()) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.releaseConstraint();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            if (!mount.hasBinding()) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.releaseConstraint();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            // Entity binding: lock the entity at the requested pose.
            if (mount.isEntityBinding()) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.releaseConstraint();
                Entity boundEntity = resolveBoundEntity(level, mount);
                if (boundEntity == null) {
                    mount.clearBinding();
                    mount.clearMeasurement();
                    mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                    continue;
                }

                if (!mount.isLocked()) {
                    mount.clearMeasurement();
                    mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                    continue;
                }

                if (mount.consumeDiagramCaptureRequest()) {
                    mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                }

                applyLockedEntityPose(mount, boundEntity);
                mount.clearPoseDirty();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            // Sublevel binding: lock the aircraft at the interface-grounded test pose.
            UUID subLevelId = mount.getBoundSubLevelId();
            if (subLevelId == null) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.releaseConstraint();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            if (!(container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel)) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.clearBinding();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            if (!mount.isLocked()) {
                clearMultiBodyPoseSnapshot(level.dimension(), mount.getBlockPos());
                mount.releaseConstraint();
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            if (mount.consumeDiagramCaptureRequest()) {
                queueDiagramCapture(level, mount, subLevel);
            }

            // The current implementation intentionally uses "teleport + resetVelocity" every physics
            // step instead of a persistent constraint. This is a stability workaround for the
            // extreme-Y removal issue seen with constraint-based locking.
            collectKinematicallyDrivenSublevels(container, mount, subLevel, drivenSubLevelIds);
            applyLockedPose(system, container, mount, subLevel);
            mount.clearPoseDirty();
        }

        if (drivenSubLevelIds.isEmpty()) {
            KINEMATICALLY_DRIVEN_SUBLEVELS.remove(level.dimension());
        } else {
            KINEMATICALLY_DRIVEN_SUBLEVELS.put(level.dimension(), Set.copyOf(drivenSubLevelIds));
        }
    }

    public static void postPhysicsTick(SubLevelPhysicsSystem system, double partialPhysicsTick) {
        ServerLevel level = system.getLevel();
        Set<BlockPos> positions = ACTIVE_MOUNTS.get(level.dimension());
        if (positions == null || positions.isEmpty()) {
            return;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        for (BlockPos pos : new ArrayList<>(positions)) {
            WindTunnelMountBlockEntity mount = getMount(level, pos, positions);
            if (mount == null || !mount.hasBinding() || !mount.isLocked()) {
                continue;
            }

            if (mount.isEntityBinding()) {
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            if (mount.isPlacedOnAircraft()) {
                mount.clearMeasurement();
                mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
                continue;
            }

            UUID subLevelId = mount.getBoundSubLevelId();
            if (subLevelId == null || !(container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel)) {
                continue;
            }

            PoseSolution pose = computePoseSolution(mount, container, subLevel);
            WindTunnelMountMeasurement measurement = computeMeasurement(level, mount, subLevel, pose);
            mount.setMeasurement(measurement);
        }

        flushDiagramCaptures(level);

        for (BlockPos pos : new ArrayList<>(positions)) {
            WindTunnelMountBlockEntity mount = getMount(level, pos, positions);
            if (mount == null || !mount.hasBinding() || !mount.isLocked() || mount.isEntityBinding() || mount.isPlacedOnAircraft()) {
                continue;
            }

            UUID subLevelId = mount.getBoundSubLevelId();
            if (subLevelId == null || !(container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel)) {
                continue;
            }

            stabilizeLockedPoseAfterPhysics(system, container, mount, subLevel);
        }
    }

    private static Entity resolveBoundEntity(ServerLevel level, WindTunnelMountBlockEntity mount) {
        UUID entityId = mount.getBoundEntityId();
        if (entityId == null) {
            return null;
        }

        Entity entity = level.getEntity(entityId);
        if (entity == null || entity.isRemoved()) {
            return null;
        }
        return entity;
    }

    private static void applyLockedEntityPose(WindTunnelMountBlockEntity mount, Entity entity) {
        Basis targetBasis = Basis.forFlow(mount.getFlowDirection(), mount.getAngleOfAttack(), mount.getSideslipAngle());
        Vector3d targetPosition = toCenter(mount.getBlockPos())
                .add(mount.getOffsetX(), mount.getOffsetY(), mount.getOffsetZ());
        float yaw = computeYaw(targetBasis.forward());
        float pitch = computePitch(targetBasis.forward());

        entity.stopRiding();
        entity.moveTo(targetPosition.x(), targetPosition.y(), targetPosition.z(), yaw, pitch);
        entity.setDeltaMovement(Vec3.ZERO);
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.yRotO = yaw;
        entity.xRotO = pitch;
        entity.fallDistance = 0.0F;

        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.setYBodyRot(yaw);
            livingEntity.setYHeadRot(yaw);
            livingEntity.yBodyRotO = yaw;
            livingEntity.yHeadRotO = yaw;
        }
    }

    private static void applyLockedPose(SubLevelPhysicsSystem system, ServerSubLevelContainer container, WindTunnelMountBlockEntity mount, ServerSubLevel subLevel) {
        if (shouldUseInterfaceSupportConstraint(mount)) {
            applyInterfaceSupportConstraint(system, container, mount, subLevel);
            return;
        }

        // Solve the reference body once, then derive every secondary body from that target pose in
        // multi-body mode.
        PoseSolution pose = computePoseSolution(mount, container, subLevel);
        Vector3d desiredAngularVelocityWorld = resolveDesiredAngularVelocity(system, subLevel, pose, mount);
        mount.releaseConstraint();
        if (mount.getApplicationMode() != WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY) {
            clearMultiBodyPoseSnapshot(subLevel.getLevel().dimension(), mount.getBlockPos());
            system.getPipeline().teleport(subLevel, pose.position(), pose.orientation());
            Vector3d desiredLinearVelocityWorld = resolveDesiredLinearVelocity(subLevel, pose.position(), pose.orientation(),
                    pose.constraintAnchorWorld(), desiredAngularVelocityWorld);
            applyVelocityLock(system, subLevel, desiredLinearVelocityWorld, desiredAngularVelocityWorld);
            mount.setActiveConstraint(null, null);
            return;
        }

        List<ServerSubLevel> groupedSubLevels = resolveMeasuredSubLevels(container, mount, subLevel);
        if (groupedSubLevels.isEmpty()) {
            clearMultiBodyPoseSnapshot(subLevel.getLevel().dimension(), mount.getBlockPos());
            system.getPipeline().teleport(subLevel, pose.position(), pose.orientation());
            Vector3d desiredLinearVelocityWorld = resolveDesiredLinearVelocity(subLevel, pose.position(), pose.orientation(),
                    pose.constraintAnchorWorld(), desiredAngularVelocityWorld);
            applyVelocityLock(system, subLevel, desiredLinearVelocityWorld, desiredAngularVelocityWorld);
            mount.setActiveConstraint(null, null);
            return;
        }

        MultiBodyPoseSnapshot snapshot = getOrCreateMultiBodyPoseSnapshot(mount, subLevel, groupedSubLevels);
        // Pose3d transforms are defined around the sublevel's rotationPoint (typically its center
        // of mass). Rebuilding a target pose from only position + orientation would silently reset
        // that pivot to zero, which sends secondary bodies to the wrong world positions.
        Pose3d referenceTargetPose = new Pose3d(subLevel.logicalPose());
        referenceTargetPose.position().set(pose.position());
        referenceTargetPose.orientation().set(pose.orientation());
        for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
            SubLevelRelativePose relativePose = snapshot.relativePoses().get(groupedSubLevel.getUniqueId());
            if (relativePose == null) {
                continue;
            }

            Vector3d targetPosition = referenceTargetPose.transformPosition(relativePose.positionRelativeToReference(), new Vector3d());
            Quaterniond targetOrientation = new Quaterniond(referenceTargetPose.orientation()).mul(relativePose.orientationRelativeToReference());
            system.getPipeline().teleport(groupedSubLevel, targetPosition, targetOrientation);
            Vector3d desiredLinearVelocityWorld = resolveDesiredLinearVelocity(groupedSubLevel, targetPosition, targetOrientation,
                    pose.constraintAnchorWorld(), desiredAngularVelocityWorld);
            applyVelocityLock(system, groupedSubLevel, desiredLinearVelocityWorld, desiredAngularVelocityWorld);
        }
        mount.setActiveConstraint(null, null);
    }

    private static void stabilizeLockedPoseAfterPhysics(SubLevelPhysicsSystem system, ServerSubLevelContainer container,
                                                        WindTunnelMountBlockEntity mount, ServerSubLevel subLevel) {
        if (!shouldUseInterfaceSupportConstraint(mount)) {
            applyLockedPose(system, container, mount, subLevel);
            return;
        }

        PoseSolution pose = computePoseSolution(mount, container, subLevel);
        stabilizeInterfaceSupportPoseAfterPhysics(system, container, mount, subLevel, pose);
    }

    static boolean shouldIgnoreSelfInducedAerodynamics(ServerSubLevel subLevel) {
        Set<UUID> subLevelIds = KINEMATICALLY_DRIVEN_SUBLEVELS.get(subLevel.getLevel().dimension());
        return subLevelIds != null && subLevelIds.contains(subLevel.getUniqueId());
    }

    private static void collectKinematicallyDrivenSublevels(ServerSubLevelContainer container, WindTunnelMountBlockEntity mount,
                                                            ServerSubLevel referenceSubLevel, Set<UUID> destination) {
        if (shouldUseInterfaceSupportConstraint(mount)) {
            return;
        }
        if (mount.getApplicationMode() != WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY) {
            destination.add(referenceSubLevel.getUniqueId());
            return;
        }
        List<ServerSubLevel> groupedSubLevels = resolveMeasuredSubLevels(container, mount, referenceSubLevel);
        if (groupedSubLevels.isEmpty()) {
            destination.add(referenceSubLevel.getUniqueId());
            return;
        }
        for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
            destination.add(groupedSubLevel.getUniqueId());
        }
    }

    private static boolean shouldUseInterfaceSupportConstraint(WindTunnelMountBlockEntity mount) {
        return mount.getReferenceMode() == WindTunnelMountBlockEntity.ReferenceMode.INTERFACE
                && !(mount.isPitchLocked() && mount.isRollLocked() && mount.isYawLocked());
    }

    private static void applyInterfaceSupportConstraint(SubLevelPhysicsSystem system, ServerSubLevelContainer container,
                                                        WindTunnelMountBlockEntity mount, ServerSubLevel subLevel) {
        PoseSolution pose = computePoseSolution(mount, container, subLevel);
        mount.releaseConstraint();
        boolean poseDirty = mount.isPoseDirty();

        if (poseDirty) {
            // When the user edits mount settings, snap once to the requested support pose before
            // rebuilding the joint so the next solver step starts from the intended configuration.
            system.getPipeline().teleport(subLevel, pose.position(), pose.orientation());
            system.getPipeline().resetVelocity(subLevel);
        }

        // The free-joint API expects the sublevel anchor in the sublevel's own plot/local
        // coordinates. Passing the already-transformed world point here creates a misaligned
        // constraint frame, which is why the craft could still be blown away while "locked".
        Quaterniond supportOrientation = new Quaterniond(pose.orientation());
        PhysicsConstraintHandle handle = system.getPipeline().addConstraint(
                null,
                subLevel,
                new FreeConstraintConfiguration(
                        new Vector3d(),
                        pose.referenceAnchorLocal(),
                        supportOrientation
                )
        );
        configureInterfaceSupportConstraint(handle, mount, pose, supportOrientation);
        mount.setActiveConstraint(handle, subLevel.getUniqueId());

        if (mount.getApplicationMode() != WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY) {
            clearMultiBodyPoseSnapshot(subLevel.getLevel().dimension(), mount.getBlockPos());
            return;
        }

        List<ServerSubLevel> groupedSubLevels = resolveMeasuredSubLevels(container, mount, subLevel);
        if (groupedSubLevels.size() <= 1) {
            clearMultiBodyPoseSnapshot(subLevel.getLevel().dimension(), mount.getBlockPos());
            return;
        }

        MultiBodyPoseSnapshot snapshot = getOrCreateMultiBodyPoseSnapshot(mount, subLevel, groupedSubLevels);
        syncFollowerSubLevelsToReference(system, subLevel, groupedSubLevels, snapshot, pose, poseDirty);
    }

    private static void configureInterfaceSupportConstraint(PhysicsConstraintHandle handle, WindTunnelMountBlockEntity mount,
                                                            PoseSolution pose, Quaterniond supportOrientation) {
        Vector3d localGoal = supportOrientation.transformInverse(new Vector3d(pose.constraintAnchorWorld()));
        handle.setMotor(ConstraintJointAxis.LINEAR_X, localGoal.x(), SUPPORT_LINEAR_STIFFNESS, SUPPORT_LINEAR_DAMPING, false, 0.0D);
        handle.setMotor(ConstraintJointAxis.LINEAR_Y, localGoal.y(), SUPPORT_LINEAR_STIFFNESS, SUPPORT_LINEAR_DAMPING, false, 0.0D);
        handle.setMotor(ConstraintJointAxis.LINEAR_Z, localGoal.z(), SUPPORT_LINEAR_STIFFNESS, SUPPORT_LINEAR_DAMPING, false, 0.0D);
        configureAngularSupportMotor(handle, ConstraintJointAxis.ANGULAR_X, mount.isPitchLocked());
        configureAngularSupportMotor(handle, ConstraintJointAxis.ANGULAR_Y, mount.isYawLocked());
        configureAngularSupportMotor(handle, ConstraintJointAxis.ANGULAR_Z, mount.isRollLocked());
    }

    private static void configureAngularSupportMotor(PhysicsConstraintHandle handle, ConstraintJointAxis axis, boolean locked) {
        if (locked) {
            handle.setMotor(axis, 0.0D, SUPPORT_ANGULAR_LOCK_STIFFNESS, SUPPORT_ANGULAR_LOCK_DAMPING, false, 0.0D);
            return;
        }
        handle.setMotor(axis, 0.0D, 0.0D, SUPPORT_ANGULAR_FREE_DAMPING, false, 0.0D);
    }

    private static void syncFollowerSubLevelsToReference(SubLevelPhysicsSystem system, ServerSubLevel referenceSubLevel,
                                                         List<ServerSubLevel> groupedSubLevels, MultiBodyPoseSnapshot snapshot,
                                                         PoseSolution pose, boolean poseDirty) {
        Pose3d referencePose = new Pose3d(referenceSubLevel.logicalPose());
        if (poseDirty) {
            referencePose.position().set(pose.position());
            referencePose.orientation().set(pose.orientation());
        }

        Vector3d referenceLinearVelocity = poseDirty
                ? new Vector3d()
                : system.getPipeline().getLinearVelocity(referenceSubLevel, new Vector3d());
        Vector3d referenceAngularVelocity = poseDirty
                ? new Vector3d()
                : system.getPipeline().getAngularVelocity(referenceSubLevel, new Vector3d());
        Vector3d referenceOriginWorld = new Vector3d(referencePose.position());

        for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
            if (groupedSubLevel == referenceSubLevel) {
                continue;
            }

            SubLevelRelativePose relativePose = snapshot.relativePoses().get(groupedSubLevel.getUniqueId());
            if (relativePose == null) {
                continue;
            }

            Vector3d targetPosition = referencePose.transformPosition(relativePose.positionRelativeToReference(), new Vector3d());
            Quaterniond targetOrientation = new Quaterniond(referencePose.orientation()).mul(relativePose.orientationRelativeToReference());
            system.getPipeline().teleport(groupedSubLevel, targetPosition, targetOrientation);
            if (poseDirty) {
                system.getPipeline().resetVelocity(groupedSubLevel);
            }

            Vector3d armWorld = targetPosition.sub(referenceOriginWorld, new Vector3d());
            Vector3d desiredLinearVelocityWorld = new Vector3d(referenceLinearVelocity)
                    .add(new Vector3d(referenceAngularVelocity).cross(armWorld, new Vector3d()));
            applyVelocityLock(system, groupedSubLevel, desiredLinearVelocityWorld, referenceAngularVelocity);
        }
    }

    private static void stabilizeInterfaceSupportPoseAfterPhysics(SubLevelPhysicsSystem system, ServerSubLevelContainer container,
                                                                  WindTunnelMountBlockEntity mount, ServerSubLevel subLevel,
                                                                  PoseSolution pose) {
        Pose3d currentPose = new Pose3d(subLevel.logicalPose());
        Vector3d actualAnchorWorld = currentPose.transformPosition(pose.referenceAnchorLocal(), new Vector3d());
        Vector3d correctedPosition = new Vector3d(currentPose.position())
                .add(new Vector3d(pose.constraintAnchorWorld()).sub(actualAnchorWorld));
        Quaterniond correctedOrientation = new Quaterniond(currentPose.orientation());
        Vector3d correctedAngularVelocity = resolveDesiredAngularVelocity(system, subLevel, pose, mount);

        system.getPipeline().teleport(subLevel, correctedPosition, correctedOrientation);
        Vector3d correctedLinearVelocity = resolveDesiredLinearVelocity(
                subLevel,
                correctedPosition,
                correctedOrientation,
                pose.constraintAnchorWorld(),
                correctedAngularVelocity);
        applyVelocityLock(system, subLevel, correctedLinearVelocity, correctedAngularVelocity);

        if (mount.getApplicationMode() != WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY) {
            return;
        }

        List<ServerSubLevel> groupedSubLevels = resolveMeasuredSubLevels(container, mount, subLevel);
        if (groupedSubLevels.size() <= 1) {
            return;
        }

        MultiBodyPoseSnapshot snapshot = getOrCreateMultiBodyPoseSnapshot(mount, subLevel, groupedSubLevels);
        Pose3d correctedReferencePose = new Pose3d(subLevel.logicalPose());
        correctedReferencePose.position().set(correctedPosition);
        correctedReferencePose.orientation().set(correctedOrientation);
        Vector3d referenceOriginWorld = new Vector3d(correctedPosition);

        for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
            if (groupedSubLevel == subLevel) {
                continue;
            }

            SubLevelRelativePose relativePose = snapshot.relativePoses().get(groupedSubLevel.getUniqueId());
            if (relativePose == null) {
                continue;
            }

            Vector3d targetPosition = correctedReferencePose.transformPosition(relativePose.positionRelativeToReference(), new Vector3d());
            Quaterniond targetOrientation = new Quaterniond(correctedReferencePose.orientation()).mul(relativePose.orientationRelativeToReference());
            system.getPipeline().teleport(groupedSubLevel, targetPosition, targetOrientation);

            Vector3d armWorld = targetPosition.sub(referenceOriginWorld, new Vector3d());
            Vector3d desiredLinearVelocityWorld = new Vector3d(correctedLinearVelocity)
                    .add(new Vector3d(correctedAngularVelocity).cross(armWorld, new Vector3d()));
            applyVelocityLock(system, groupedSubLevel, desiredLinearVelocityWorld, correctedAngularVelocity);
        }
    }

    private static WindTunnelMountMeasurement computeMeasurement(ServerLevel level, WindTunnelMountBlockEntity mount, ServerSubLevel subLevel, PoseSolution pose) {
        // Measurements are reported in the aircraft body frame so lift/drag/side-force remain
        // meaningful regardless of how the stand is oriented in world space.
        Vector3d totalForce = new Vector3d();
        Vector3d totalMoment = new Vector3d();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return WindTunnelMountMeasurement.EMPTY;
        }

        List<ServerSubLevel> measuredSubLevels = resolveMeasuredSubLevels(container, mount, subLevel);
        if (measuredSubLevels.isEmpty()) {
            return WindTunnelMountMeasurement.EMPTY;
        }

        for (ServerSubLevel measuredSubLevel : measuredSubLevels) {
            accumulateMeasurementForSubLevel(measuredSubLevel, pose, totalForce, totalMoment);
        }

        Vector3d flowLocal = new Vector3d(pose.flowWorld());
        new Quaterniond(pose.orientation()).transformInverse(flowLocal);
        flowLocal.normalize();

        // Build aerodynamic axes in body-local space:
        // forward axis follows incoming flow, side axis is body-up x flow, lift axis completes the
        // triad. Fallbacks keep the basis valid near degenerate orientations.
        Vector3d sideAxis = new Vector3d(pose.bodyUpLocal()).cross(flowLocal);
        if (sideAxis.lengthSquared() <= AXIS_EPSILON) {
            sideAxis.set(pose.bodyRightLocal());
        } else {
            sideAxis.normalize();
        }

        Vector3d liftAxis = new Vector3d(flowLocal).cross(sideAxis);
        if (liftAxis.lengthSquared() <= AXIS_EPSILON) {
            liftAxis.set(pose.bodyUpLocal());
        } else {
            liftAxis.normalize();
        }

        return new WindTunnelMountMeasurement(
                totalForce.dot(liftAxis),
                totalForce.dot(flowLocal),
                totalForce.dot(sideAxis),
                totalMoment.dot(pose.bodyRightLocal()),
                totalMoment.dot(pose.bodyForwardLocal()),
                totalMoment.dot(pose.bodyUpLocal())
        );
    }

    @SuppressWarnings("unused")
    private static void accumulateGroup(QueuedForceGroup group, Vector3d anchorLocal, Vector3d totalForce, Vector3d totalMoment) {
        if (group == null) {
            return;
        }

        totalForce.add(group.getForceTotal().getLocalForce());
        for (QueuedForceGroup.PointForce pointForce : group.getRecordedPointForces()) {
            Vector3d point = new Vector3d(pointForce.point());
            Vector3d force = new Vector3d(pointForce.force());
            Vector3d arm = point.sub(anchorLocal, new Vector3d());
            // Moments are resolved about the chosen interface anchor, not the center of mass.
            totalMoment.add(arm.cross(force, new Vector3d()));
        }
    }

    private static PoseSolution computePoseSolution(WindTunnelMountBlockEntity mount, ServerSubLevelContainer container, ServerSubLevel subLevel) {
        // localBasis: how the bound aircraft defines its own body axes in sublevel-local space.
        // The stand block's facing is purely a placement/rendering concern; incoming-flow
        // direction must stay anchored to world axes, so the aircraft basis comes from the bound
        // interface marker instead of the stand block itself.
        Basis localBasis = Basis.fromForward(mount.getInterfaceFacing());
        // worldBasis: how those axes should appear in world space after applying flow, AoA, beta.
        Basis worldBasis = Basis.forFlow(mount.getFlowDirection(), mount.getAngleOfAttack(), mount.getSideslipAngle());
        Quaterniond desiredBodyOrientation = computeOrientation(Basis.canonical(), worldBasis);
        Quaterniond bodyOrientation = applyRotationLocks(mount, subLevel, localBasis, worldBasis, desiredBodyOrientation);
        Basis resolvedWorldBasis = Basis.fromBodyOrientation(bodyOrientation);
        Quaterniond orientation = computeOrientation(localBasis, resolvedWorldBasis);
        Vector3d referenceAnchorLocal = resolveReferenceAnchorLocal(mount, container, subLevel);

        // The UI exposes raw Minecraft X/Y/Z offsets, so apply them directly in world space:
        // +X east, +Y up, +Z south.
        Vector3d targetAnchorWorld = toCenter(mount.getBlockPos())
                .add(mount.getOffsetX(), mount.getOffsetY(), mount.getOffsetZ());

        // First solve rotation, then compute the translation needed to bring the chosen reference
        // point (interface anchor or center of mass) onto the requested world anchor.
        Pose3d targetPose = new Pose3d(subLevel.logicalPose());
        targetPose.position().zero();
        targetPose.orientation().set(orientation);
        Vector3d anchorWithZeroTranslation = targetPose.transformPosition(referenceAnchorLocal, new Vector3d());
        Vector3d position = targetAnchorWorld.sub(anchorWithZeroTranslation, new Vector3d());

        return new PoseSolution(
                position,
                orientation,
                bodyOrientation,
                targetAnchorWorld,
                directionVector(mount.getFlowDirection()),
                referenceAnchorLocal,
                localBasis.forward(),
                localBasis.up(),
                localBasis.right()
        );
    }

    private static Vector3d resolveReferenceAnchorLocal(WindTunnelMountBlockEntity mount, ServerSubLevelContainer container, ServerSubLevel subLevel) {
        if (mount.getReferenceMode() == WindTunnelMountBlockEntity.ReferenceMode.CENTER_OF_MASS) {
            return resolveCenterOfMassLocal(container, mount, subLevel);
        }
        return resolveInterfaceAnchorLocal(mount, subLevel);
    }

    private static Vector3d resolveInterfaceAnchorLocal(WindTunnelMountBlockEntity mount, ServerSubLevel subLevel) {
        // Newer bindings persist a local anchor directly. The fallback path only exists to migrate
        // older saved bindings that still encode the anchor indirectly.
        Vector3d storedLocalAnchor = mount.getStoredInterfaceAnchorLocal();
        if (storedLocalAnchor != null) {
            return storedLocalAnchor;
        }

        Vector3d storedAnchor = toCenter(mount.getInterfacePos());
        BoundingBox3ic plotBounds = subLevel.getPlot().getBoundingBox();
        if (plotBounds != null && plotBounds.contains(storedAnchor)) {
            // Newly stored bindings may already be in plot-local coordinates.
            mount.setStoredInterfaceAnchorLocal(storedAnchor);
            return storedAnchor;
        }

        // Older or cross-context bindings may still be in world space, so convert them back.
        Vector3d resolvedLocalAnchor = subLevel.logicalPose().transformPositionInverse(storedAnchor, new Vector3d());
        mount.setStoredInterfaceAnchorLocal(resolvedLocalAnchor);
        return resolvedLocalAnchor;
    }

    private static Vector3d resolveCenterOfMassLocal(ServerSubLevelContainer container, WindTunnelMountBlockEntity mount, ServerSubLevel referenceSubLevel) {
        List<ServerSubLevel> referenceSubLevels = resolveMeasuredSubLevels(container, mount, referenceSubLevel);
        if (referenceSubLevels.isEmpty()) {
            return new Vector3d(referenceSubLevel.getMassTracker().getCenterOfMass());
        }

        double totalMass = 0.0D;
        Vector3d weightedCenterWorld = new Vector3d();
        Pose3d referencePose = new Pose3d(referenceSubLevel.logicalPose());
        for (ServerSubLevel subLevel : referenceSubLevels) {
            double mass = subLevel.getMassTracker().getMass();
            if (mass <= 0.0D) {
                continue;
            }
            Vector3d centerWorld = subLevel.logicalPose().transformPosition(subLevel.getMassTracker().getCenterOfMass(), new Vector3d());
            weightedCenterWorld.fma(mass, centerWorld);
            totalMass += mass;
        }

        if (totalMass <= 1.0E-6D) {
            return new Vector3d(referenceSubLevel.getMassTracker().getCenterOfMass());
        }
        return referencePose.transformPositionInverse(weightedCenterWorld.div(totalMass), new Vector3d());
    }

    private static Quaterniond applyRotationLocks(WindTunnelMountBlockEntity mount, ServerSubLevel subLevel, Basis localBasis, Basis desiredWorldBasis,
                                                  Quaterniond desiredBodyOrientation) {
        if (mount.isPitchLocked() && mount.isRollLocked() && mount.isYawLocked()) {
            return new Quaterniond(desiredBodyOrientation);
        }

        Basis currentWorldBasis = Basis.fromSubLevelOrientation(localBasis, subLevel.logicalPose().orientation());
        Quaterniond currentBodyOrientation = computeOrientation(Basis.canonical(), currentWorldBasis);
        Vector3d desiredAngles = desiredBodyOrientation.getEulerAnglesYXZ(new Vector3d());
        Vector3d currentAngles = currentBodyOrientation.getEulerAnglesYXZ(new Vector3d());
        Vector3d blendedAngles = new Vector3d(
                mount.isPitchLocked() ? desiredAngles.x() : currentAngles.x(),
                mount.isYawLocked() ? desiredAngles.y() : currentAngles.y(),
                mount.isRollLocked() ? desiredAngles.z() : currentAngles.z()
        );
        return new Quaterniond().rotationYXZ(blendedAngles.y(), blendedAngles.x(), blendedAngles.z());
    }

    private static Vector3d resolveDesiredAngularVelocity(SubLevelPhysicsSystem system, ServerSubLevel subLevel, PoseSolution pose, WindTunnelMountBlockEntity mount) {
        if (mount.isPitchLocked() && mount.isRollLocked() && mount.isYawLocked()) {
            return new Vector3d();
        }

        Vector3d angularVelocityWorld = system.getPipeline().getAngularVelocity(subLevel, new Vector3d());
        Vector3d angularVelocityBody = new Quaterniond(pose.bodyOrientation()).transformInverse(angularVelocityWorld, new Vector3d());
        if (mount.isPitchLocked()) {
            angularVelocityBody.x = 0.0D;
        }
        if (mount.isYawLocked()) {
            angularVelocityBody.y = 0.0D;
        }
        if (mount.isRollLocked()) {
            angularVelocityBody.z = 0.0D;
        }
        return new Quaterniond(pose.bodyOrientation()).transform(angularVelocityBody);
    }

    private static Vector3d resolveDesiredLinearVelocity(ServerSubLevel subLevel, Vector3d targetPosition, Quaterniond targetOrientation,
                                                         Vector3d anchorWorld, Vector3d desiredAngularVelocityWorld) {
        if (desiredAngularVelocityWorld.lengthSquared() <= AXIS_EPSILON) {
            return new Vector3d();
        }

        // A rigid body rotating about a fixed support point generally needs non-zero linear
        // velocity at its center of mass. Clearing linear velocity unconditionally makes a
        // support-point lock behave like a center-of-mass lock and suppresses gravity-driven swing.
        Pose3d targetPose = new Pose3d(subLevel.logicalPose());
        targetPose.position().set(targetPosition);
        targetPose.orientation().set(targetOrientation);
        Vector3d centerWorld = targetPose.transformPosition(subLevel.getMassTracker().getCenterOfMass(), new Vector3d());
        Vector3d centerOffset = centerWorld.sub(anchorWorld, new Vector3d());
        return desiredAngularVelocityWorld.cross(centerOffset, new Vector3d());
    }

    private static void applyVelocityLock(SubLevelPhysicsSystem system, ServerSubLevel subLevel,
                                          Vector3d desiredLinearVelocityWorld, Vector3d desiredAngularVelocityWorld) {
        Vector3d currentLinearVelocity = system.getPipeline().getLinearVelocity(subLevel, new Vector3d());
        Vector3d currentAngularVelocity = system.getPipeline().getAngularVelocity(subLevel, new Vector3d());
        Vector3d linearCorrection = new Vector3d(desiredLinearVelocityWorld).sub(currentLinearVelocity);
        Vector3d angularCorrection = new Vector3d(desiredAngularVelocityWorld).sub(currentAngularVelocity);
        system.getPipeline().addLinearAndAngularVelocity(subLevel, linearCorrection, angularCorrection);
    }

    private static Quaterniond computeOrientation(Basis localBasis, Basis worldBasis) {
        // Step 1: rotate body forward onto the desired forward.
        Quaterniond alignForward = new Quaterniond().rotationTo(localBasis.forward(), worldBasis.forward());
        Vector3d alignedUp = new Vector3d(localBasis.up()).rotate(alignForward);
        Vector3d upProjected = projectOntoPlane(alignedUp, worldBasis.forward());
        Vector3d desiredUpProjected = projectOntoPlane(worldBasis.up(), worldBasis.forward());
        if (upProjected.lengthSquared() <= AXIS_EPSILON || desiredUpProjected.lengthSquared() <= AXIS_EPSILON) {
            return alignForward.normalize();
        }

        upProjected.normalize();
        desiredUpProjected.normalize();
        double sin = worldBasis.forward().dot(new Vector3d(upProjected).cross(desiredUpProjected));
        double cos = upProjected.dot(desiredUpProjected);
        double twistAngle = Math.atan2(sin, cos);

        // Step 2: twist around the new forward axis so "up" also lines up.
        Quaterniond twist = new Quaterniond().fromAxisAngleRad(worldBasis.forward(), twistAngle);
        return twist.mul(alignForward).normalize();
    }

    private static Vector3d projectOntoPlane(Vector3d vector, Vector3d normal) {
        return vector.sub(new Vector3d(normal).mul(vector.dot(normal)), new Vector3d());
    }

    private static Vector3d toCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Vector3d directionVector(Direction direction) {
        return new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static float computeYaw(Vector3d forward) {
        return (float) Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
    }

    private static float computePitch(Vector3d forward) {
        double horizontalLength = Math.sqrt(forward.x() * forward.x() + forward.z() * forward.z());
        return (float) Math.toDegrees(Math.atan2(-forward.y(), horizontalLength));
    }

    private static WindTunnelMountBlockEntity getMount(ServerLevel level, BlockPos pos, Set<BlockPos> positions) {
        if (!(level.getBlockEntity(pos) instanceof WindTunnelMountBlockEntity mount)) {
            positions.remove(pos);
            clearMultiBodyPoseSnapshot(level.dimension(), pos);
            return null;
        }
        return mount;
    }

    private static MultiBodyPoseSnapshot getOrCreateMultiBodyPoseSnapshot(WindTunnelMountBlockEntity mount, ServerSubLevel referenceSubLevel,
                                                                          List<ServerSubLevel> groupedSubLevels) {
        // Cache against the mount position so one lock session keeps reusing the same relative
        // rigid-body layout instead of recapturing while the structure is being manipulated.
        ResourceKey<Level> dimension = referenceSubLevel.getLevel().dimension();
        BlockPos mountPos = mount.getBlockPos().immutable();
        Map<BlockPos, MultiBodyPoseSnapshot> snapshots =
                MULTI_BODY_POSE_SNAPSHOTS.computeIfAbsent(dimension, unused -> new ConcurrentHashMap<>());
        MultiBodyPoseSnapshot existing = snapshots.get(mountPos);
        if (existing != null && existing.matches(referenceSubLevel, groupedSubLevels)) {
            return existing;
        }

        MultiBodyPoseSnapshot captured = captureMultiBodyPoseSnapshot(referenceSubLevel, groupedSubLevels);
        snapshots.put(mountPos, captured);
        return captured;
    }

    private static MultiBodyPoseSnapshot captureMultiBodyPoseSnapshot(ServerSubLevel referenceSubLevel, List<ServerSubLevel> groupedSubLevels) {
        // Capture each body's transform relative to the chosen reference body exactly once.
        Pose3d referencePose = new Pose3d(referenceSubLevel.logicalPose());
        Quaterniond referenceOrientationInverse = new Quaterniond(referencePose.orientation()).conjugate();
        LinkedHashMap<UUID, SubLevelRelativePose> relativePoses = new LinkedHashMap<>();
        LinkedHashSet<UUID> subLevelIds = new LinkedHashSet<>();
        for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
            subLevelIds.add(groupedSubLevel.getUniqueId());
            Vector3d relativePosition = referencePose.transformPositionInverse(groupedSubLevel.logicalPose().position(), new Vector3d());
            Quaterniond relativeOrientation = new Quaterniond(referenceOrientationInverse).mul(groupedSubLevel.logicalPose().orientation());
            relativePoses.put(groupedSubLevel.getUniqueId(), new SubLevelRelativePose(relativePosition, relativeOrientation));
        }
        return new MultiBodyPoseSnapshot(referenceSubLevel.getUniqueId(), subLevelIds, relativePoses);
    }

    private static void clearMultiBodyPoseSnapshot(ResourceKey<Level> dimension, BlockPos mountPos) {
        Map<BlockPos, MultiBodyPoseSnapshot> snapshots = MULTI_BODY_POSE_SNAPSHOTS.get(dimension);
        if (snapshots == null) {
            return;
        }

        snapshots.remove(mountPos);
        if (snapshots.isEmpty()) {
            MULTI_BODY_POSE_SNAPSHOTS.remove(dimension, snapshots);
        }
    }

    private static void accumulateMeasurementForSubLevel(ServerSubLevel measuredSubLevel, PoseSolution referencePose,
                                                         Vector3d totalForce, Vector3d totalMoment) {
        Object2ObjectMap<?, QueuedForceGroup> queuedForceGroups = measuredSubLevel.getQueuedForceGroups();
        if (queuedForceGroups == null) {
            return;
        }

        // Each sampled body accumulates forces in its own local frame. Convert everything into the
        // reference body's local frame before summing.
        Quaterniond worldToReferenceLocal = new Quaterniond(referencePose.orientation()).conjugate();
        Vector3d referenceAnchorWorld = new Vector3d(referencePose.constraintAnchorWorld());
        accumulateGroupForMeasurement(queuedForceGroups.get(ForceGroups.LIFT.get()), measuredSubLevel, worldToReferenceLocal, referenceAnchorWorld, totalForce, totalMoment);
        accumulateGroupForMeasurement(queuedForceGroups.get(ForceGroups.DRAG.get()), measuredSubLevel, worldToReferenceLocal, referenceAnchorWorld, totalForce, totalMoment);
        accumulateGravityMomentForMeasurement(measuredSubLevel, worldToReferenceLocal, referenceAnchorWorld, totalMoment);
    }

    private static void accumulateGroupForMeasurement(Object rawGroup, ServerSubLevel measuredSubLevel,
                                                      Quaterniond worldToReferenceLocal, Vector3d referenceAnchorWorld,
                                                      Vector3d totalForce, Vector3d totalMoment) {
        if (!(rawGroup instanceof QueuedForceGroup group)) {
            return;
        }

        Vector3d localForce = new Vector3d(group.getForceTotal().getLocalForce());
        Vector3d worldForce = measuredSubLevel.logicalPose().transformNormal(localForce, new Vector3d());
        worldToReferenceLocal.transform(worldForce);
        totalForce.add(worldForce);

        Vector3d localTorque = new Vector3d(group.getForceTotal().getLocalTorque());
        Vector3d worldTorque = measuredSubLevel.logicalPose().transformNormal(localTorque, new Vector3d());
        worldToReferenceLocal.transform(worldTorque);
        totalMoment.add(worldTorque);
        Vector3d centerWorld = measuredSubLevel.logicalPose().transformPosition(measuredSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
        Vector3d arm = centerWorld.sub(referenceAnchorWorld, new Vector3d());
        worldToReferenceLocal.transform(arm);
        totalMoment.add(arm.cross(worldForce, new Vector3d()));
    }

    private static void accumulateGravityMomentForMeasurement(ServerSubLevel measuredSubLevel, Quaterniond worldToReferenceLocal,
                                                              Vector3d referenceAnchorWorld, Vector3d totalMoment) {
        double mass = measuredSubLevel.getMassTracker().getMass();
        if (mass <= 1.0E-6D) {
            return;
        }

        // The stand UI still reports aerodynamic force components separately, but if the user
        // chooses an interface-based reference point, the weight acting at the center of mass must
        // still contribute to the measured moment about that support point.
        Vector3d centerWorld = measuredSubLevel.logicalPose().transformPosition(measuredSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
        Vector3d arm = centerWorld.sub(referenceAnchorWorld, new Vector3d());
        Vector3d gravityWorld = directionVector(Direction.DOWN).mul(GRAVITY_ACCELERATION * mass, new Vector3d());
        worldToReferenceLocal.transform(arm);
        worldToReferenceLocal.transform(gravityWorld);
        totalMoment.add(arm.cross(gravityWorld, new Vector3d()));
    }

    private static void queueDiagramCapture(ServerLevel level, WindTunnelMountBlockEntity mount, ServerSubLevel referenceSubLevel) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        List<ServerSubLevel> targetSubLevels = resolveMeasuredSubLevels(container, mount, referenceSubLevel);
        if (targetSubLevels.isEmpty()) {
            mount.setDiagramData(WindTunnelMountDiagramData.EMPTY);
            return;
        }

        List<ServerPlayer> players = new ArrayList<>(level.players().stream()
                .filter(player -> player.containerMenu instanceof WindTunnelMountMenu menu && menu.getMountPos().equals(mount.getBlockPos()))
                .toList());
        if (players.isEmpty()) {
            return;
        }

        DiagramCaptureTicket ticket = new DiagramCaptureTicket(mount.getBlockPos().immutable(), targetSubLevels, players, false);
        for (ServerSubLevel targetSubLevel : targetSubLevels) {
            PointForceTracking.retain(targetSubLevel, PointForceTracking.key("wind_tunnel_mount", mount.getBlockPos()));
            PENDING_DIAGRAM_CAPTURES.put(targetSubLevel, ticket);
        }
    }

    private static void flushDiagramCaptures(ServerLevel level) {
        // Tracking must stay armed for one completed physics step; otherwise the recorded point
        // force lists would still be empty when we sample them.
        if (PENDING_DIAGRAM_CAPTURES.isEmpty()) {
            return;
        }

        Map<BlockPos, DiagramCaptureAggregation> completed = new LinkedHashMap<>();
        for (Map.Entry<ServerSubLevel, DiagramCaptureTicket> entry : new ArrayList<>(PENDING_DIAGRAM_CAPTURES.entrySet())) {
            ServerSubLevel targetSubLevel = entry.getKey();
            DiagramCaptureTicket ticket = entry.getValue();
            if (!ticket.armed()) {
                PENDING_DIAGRAM_CAPTURES.put(targetSubLevel, ticket.arm());
                continue;
            }
            PENDING_DIAGRAM_CAPTURES.remove(targetSubLevel);
            PointForceTracking.release(targetSubLevel, PointForceTracking.key("wind_tunnel_mount", ticket.mountPos()));

            if (!(level.getBlockEntity(ticket.mountPos()) instanceof WindTunnelMountBlockEntity mount) || !mount.hasBinding()) {
                continue;
            }
            UUID referenceId = mount.getBoundSubLevelId();
            if (referenceId == null) {
                continue;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || !(container.getSubLevel(referenceId) instanceof ServerSubLevel referenceSubLevel)) {
                continue;
            }

            completed.computeIfAbsent(ticket.mountPos(), ignored ->
                    new DiagramCaptureAggregation(mount, referenceSubLevel, computePoseSolution(mount, container, referenceSubLevel), ticket.players()));
            completed.get(ticket.mountPos()).sampledSubLevels().add(targetSubLevel);
        }

        for (DiagramCaptureAggregation aggregation : completed.values()) {
            WindTunnelMountDiagramData data = buildDiagramData(aggregation.referenceSubLevel(), aggregation.pose(), aggregation.sampledSubLevels());
            aggregation.mount().setDiagramData(data);
            for (ServerPlayer player : aggregation.players()) {
                PacketDistributor.sendToPlayer(player, SyncWindTunnelMountDiagramPayload.fromMount(aggregation.mount().getBlockPos(), aggregation.mount()));
            }
        }
    }

    private static WindTunnelMountDiagramData buildDiagramData(ServerSubLevel referenceSubLevel, PoseSolution pose, List<ServerSubLevel> sampledSubLevels) {
        if (sampledSubLevels.isEmpty()) {
            return WindTunnelMountDiagramData.EMPTY;
        }

        // The client diagram renders in the reference sublevel's local coordinates, so all sampled
        // forces, center-of-mass positions and gravity vectors are transformed into that frame.
        double timeStep = resolveTimeStep(referenceSubLevel.getLevel());
        Object2ObjectOpenHashMap<dev.ryanhcode.sable.api.physics.force.ForceGroup, List<QueuedForceGroup.PointForce>> groupedForces = new Object2ObjectOpenHashMap<>();
        List<UUID> renderedSubLevelIds = new ArrayList<>(sampledSubLevels.size());
        double totalMass = 0.0D;
        Vector3d weightedCenterWorld = new Vector3d();
        Pose3d referencePose = new Pose3d(referenceSubLevel.logicalPose());

        for (ServerSubLevel sampledSubLevel : sampledSubLevels) {
            renderedSubLevelIds.add(sampledSubLevel.getUniqueId());
            double mass = sampledSubLevel.getMassTracker().getMass();
            totalMass += mass;

            Vector3d centerWorld = sampledSubLevel.logicalPose().transformPosition(sampledSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
            weightedCenterWorld.fma(mass, centerWorld);

            Object2ObjectMap<?, QueuedForceGroup> queuedForceGroups = sampledSubLevel.getQueuedForceGroups();
            if (queuedForceGroups == null) {
                continue;
            }

            for (Map.Entry<?, QueuedForceGroup> entry : queuedForceGroups.entrySet()) {
                if (!(entry.getKey() instanceof dev.ryanhcode.sable.api.physics.force.ForceGroup forceGroup)) {
                    continue;
                }
                List<QueuedForceGroup.PointForce> destination = groupedForces.computeIfAbsent(forceGroup, ignored -> new ArrayList<>());
                for (QueuedForceGroup.PointForce pointForce : entry.getValue().getRecordedPointForces()) {
                    Vector3d pointWorld = sampledSubLevel.logicalPose().transformPosition(pointForce.point(), new Vector3d());
                    Vector3d forceWorld = sampledSubLevel.logicalPose().transformNormal(pointForce.force(), new Vector3d()).div(timeStep);
                    // The client diagram renders the contraption in reference-sublevel local space,
                    // so the force markers must be converted into that same frame.
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
        Vector3d localGravity = new Vector3d(directionVector(Direction.DOWN));
        referencePose.transformNormalInverse(localGravity);
        localGravity.mul(GRAVITY_ACCELERATION * totalMass);
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

    private static List<ServerSubLevel> resolveMeasuredSubLevels(ServerSubLevelContainer container, WindTunnelMountBlockEntity mount, ServerSubLevel referenceSubLevel) {
        if (mount.getApplicationMode() == WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY) {
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
     * Basis vectors for either the stand-defined aircraft body frame or the desired world-space
     * frame after flow direction, angle of attack and sideslip are applied.
     */
    private record Basis(Vector3d forward, Vector3d up, Vector3d right) {
        private static Basis canonical() {
            return new Basis(new Vector3d(0.0D, 0.0D, 1.0D), new Vector3d(0.0D, 1.0D, 0.0D), new Vector3d(1.0D, 0.0D, 0.0D));
        }

        private static Basis fromForward(Direction forwardDirection) {
            Vector3d forward = directionVector(forwardDirection).normalize();
            Vector3d upReference = Math.abs(forward.dot(WORLD_UP)) > 0.999D ? new Vector3d(WORLD_NORTH) : new Vector3d(WORLD_UP);
            Vector3d right = new Vector3d(forward).cross(upReference);
            if (right.lengthSquared() <= AXIS_EPSILON) {
                right.set(1.0D, 0.0D, 0.0D);
            } else {
                right.normalize();
            }

            Vector3d up = new Vector3d(right).cross(forward);
            if (up.lengthSquared() <= AXIS_EPSILON) {
                up.set(WORLD_UP);
            } else {
                up.normalize();
            }
            return new Basis(forward, up, right);
        }

        private static Basis forFlow(Direction flowDirection, double angleOfAttack, double sideslipAngle) {
            // flowDirection is the incoming air direction in world space, so body forward starts as
            // the opposite of that flow before AoA and sideslip are applied.
            Vector3d flow = directionVector(flowDirection).normalize();
            Vector3d forward = new Vector3d(flow).negate();
            Vector3d upReference = Math.abs(forward.dot(WORLD_UP)) > 0.999D ? new Vector3d(WORLD_NORTH) : new Vector3d(WORLD_UP);
            Vector3d right = new Vector3d(forward).cross(upReference);
            if (right.lengthSquared() <= AXIS_EPSILON) {
                right.set(1.0D, 0.0D, 0.0D);
            } else {
                right.normalize();
            }

            Vector3d up = new Vector3d(right).cross(forward).normalize();
            Quaterniond betaRotation = new Quaterniond().fromAxisAngleRad(up, Math.toRadians(sideslipAngle));
            Quaterniond alphaRotation = new Quaterniond().fromAxisAngleRad(new Vector3d(right).rotate(betaRotation), Math.toRadians(angleOfAttack));

            // Apply sideslip first, then pitch about the rotated right axis.
            Quaterniond totalRotation = new Quaterniond(alphaRotation).mul(betaRotation);
            forward.rotate(totalRotation).normalize();
            up.rotate(totalRotation).normalize();
            right.rotate(totalRotation).normalize();
            return new Basis(forward, up, right);
        }

        private static Basis fromSubLevelOrientation(Basis localBasis, Quaterniond orientation) {
            return new Basis(
                    new Vector3d(localBasis.forward()).rotate(orientation).normalize(),
                    new Vector3d(localBasis.up()).rotate(orientation).normalize(),
                    new Vector3d(localBasis.right()).rotate(orientation).normalize()
            );
        }

        private static Basis fromBodyOrientation(Quaterniond bodyOrientation) {
            return new Basis(
                    new Vector3d(0.0D, 0.0D, 1.0D).rotate(bodyOrientation).normalize(),
                    new Vector3d(0.0D, 1.0D, 0.0D).rotate(bodyOrientation).normalize(),
                    new Vector3d(1.0D, 0.0D, 0.0D).rotate(bodyOrientation).normalize()
            );
        }
    }

    /**
     * Fully resolved target pose for the reference rigid body plus the local basis vectors needed
     * to interpret measured forces and moments.
     */
    private record PoseSolution(
            Vector3d position,
            Quaterniond orientation,
            Quaterniond bodyOrientation,
            Vector3d constraintAnchorWorld,
            Vector3d flowWorld,
            Vector3d referenceAnchorLocal,
            Vector3d bodyForwardLocal,
            Vector3d bodyUpLocal,
            Vector3d bodyRightLocal
    ) {
    }

    private record DiagramCaptureTicket(
            BlockPos mountPos,
            List<ServerSubLevel> targetSubLevels,
            List<ServerPlayer> players,
            boolean armed
    ) {
        private DiagramCaptureTicket arm() {
            return new DiagramCaptureTicket(mountPos, targetSubLevels, players, true);
        }
    }

    private record DiagramCaptureAggregation(
            WindTunnelMountBlockEntity mount,
            ServerSubLevel referenceSubLevel,
            PoseSolution pose,
            List<ServerPlayer> players,
            List<ServerSubLevel> sampledSubLevels
    ) {
        private DiagramCaptureAggregation(WindTunnelMountBlockEntity mount, ServerSubLevel referenceSubLevel,
                                          PoseSolution pose, List<ServerPlayer> players) {
            this(mount, referenceSubLevel, pose, players, new ArrayList<>());
        }
    }

    /**
     * Relative transform of one secondary body with respect to the reference body used by the
     * mount. These values are replayed while the stand is locked.
     */
    private record SubLevelRelativePose(Vector3d positionRelativeToReference, Quaterniond orientationRelativeToReference) {
        private SubLevelRelativePose {
            positionRelativeToReference = new Vector3d(positionRelativeToReference);
            orientationRelativeToReference = new Quaterniond(orientationRelativeToReference);
        }
    }

    /**
     * Frozen multi-body layout captured at the start of a lock session. It is invalidated whenever
     * the bound structure changes identity or body membership.
     */
    private record MultiBodyPoseSnapshot(
            UUID referenceSubLevelId,
            Set<UUID> subLevelIds,
            Map<UUID, SubLevelRelativePose> relativePoses
    ) {
        private MultiBodyPoseSnapshot {
            subLevelIds = Set.copyOf(subLevelIds);
            relativePoses = Map.copyOf(relativePoses);
        }

        private boolean matches(ServerSubLevel referenceSubLevel, List<ServerSubLevel> groupedSubLevels) {
            if (!referenceSubLevel.getUniqueId().equals(referenceSubLevelId)) {
                return false;
            }

            if (groupedSubLevels.size() != subLevelIds.size()) {
                return false;
            }

            for (ServerSubLevel groupedSubLevel : groupedSubLevels) {
                if (!subLevelIds.contains(groupedSubLevel.getUniqueId())) {
                    return false;
                }
            }
            return true;
        }
    }
}
