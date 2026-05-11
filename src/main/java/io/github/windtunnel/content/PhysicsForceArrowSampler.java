package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

/**
 * Converts Sable's recorded per-point forces into world-space force arrows.
 */
@SuppressWarnings("null")
public final class PhysicsForceArrowSampler {
    private static final double MIN_FORCE_LENGTH_SQUARED = 1.0E-4D;
    private static final double GRAVITY_ACCELERATION = 11.0D;
    private static final Vector3d WORLD_DOWN = new Vector3d(0.0D, -1.0D, 0.0D);

    private PhysicsForceArrowSampler() {
    }

    public static List<HologramForceArrow> buildForceArrows(ServerLevel level, List<ServerSubLevel> targetSubLevels,
                                                            int maxArrows) {
        if (targetSubLevels.isEmpty() || maxArrows <= 0) {
            return List.of();
        }

        double timeStep = resolveTimeStep(level);
        List<HologramForceArrow> arrows = new ArrayList<>();
        double totalMass = 0.0D;
        Vector3d weightedCenterWorld = new Vector3d();
        for (ServerSubLevel subLevel : targetSubLevels) {
            double mass = subLevel.getMassTracker().getMass();
            if (mass > 0.0D) {
                totalMass += mass;
                Vector3d centerWorld = subLevel.logicalPose().transformPosition(
                        subLevel.getMassTracker().getCenterOfMass(),
                        new Vector3d()
                );
                weightedCenterWorld.fma(mass, centerWorld);
            }

            Object2ObjectMap<ForceGroup, QueuedForceGroup> queuedForceGroups = subLevel.getQueuedForceGroups();
            if (queuedForceGroups == null) {
                continue;
            }
            for (Map.Entry<ForceGroup, QueuedForceGroup> entry : queuedForceGroups.entrySet()) {
                ResourceLocation groupId = ForceGroups.REGISTRY.getKey(entry.getKey());
                if (groupId == null) {
                    continue;
                }
                for (QueuedForceGroup.PointForce pointForce : entry.getValue().getRecordedPointForces()) {
                    Vector3d forceWorld = subLevel.logicalPose().transformNormal(pointForce.force(), new Vector3d()).div(timeStep);
                    if (forceWorld.lengthSquared() <= MIN_FORCE_LENGTH_SQUARED) {
                        continue;
                    }
                    Vector3d pointWorld = subLevel.logicalPose().transformPosition(pointForce.point(), new Vector3d());
                    arrows.add(HologramForceArrow.of(groupId, pointWorld, forceWorld));
                }
            }
        }
        addGravityArrow(arrows, totalMass, weightedCenterWorld);

        if (arrows.size() <= maxArrows) {
            return arrows;
        }

        arrows.sort(Comparator.comparingDouble((HologramForceArrow arrow) -> arrow.force().lengthSquared()).reversed());
        return List.copyOf(arrows.subList(0, maxArrows));
    }

    private static void addGravityArrow(List<HologramForceArrow> arrows, double totalMass, Vector3d weightedCenterWorld) {
        ResourceLocation gravityGroupId = ForceGroups.REGISTRY.getKey(ForceGroups.GRAVITY.get());
        if (gravityGroupId == null || totalMass <= 0.0D) {
            return;
        }

        Vector3d centerWorld = weightedCenterWorld.div(totalMass, new Vector3d());
        Vector3d gravityForce = WORLD_DOWN.mul(GRAVITY_ACCELERATION * totalMass, new Vector3d());
        if (gravityForce.lengthSquared() > MIN_FORCE_LENGTH_SQUARED) {
            arrows.add(HologramForceArrow.of(gravityGroupId, centerWorld, gravityForce));
        }
    }

    private static double resolveTimeStep(ServerLevel level) {
        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        if (physicsSystem == null) {
            return 0.05D;
        }
        return 0.05D / (double) physicsSystem.getConfig().substepsPerTick;
    }
}
