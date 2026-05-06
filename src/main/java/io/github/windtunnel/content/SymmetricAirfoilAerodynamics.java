package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Local aerodynamic solver for the symmetric airfoil block.
 *
 * <p>The model resolves a 2D airfoil section in the local chord-normal plane, projects the
 * resulting lift/drag pair into body-local coordinates, and applies both impulses at the
 * quarter-chord point. The first version keeps the aerodynamic center fixed and sets
 * additional pitching moment to zero.</p>
 */
final class SymmetricAirfoilAerodynamics {
    private static final double MIN_EFFECTIVE_SPEED_SQUARED = 1.0E-6D;
    private static final double EFFECTIVE_AIR_DENSITY_SCALE = 0.2D;
    private static final double DEFAULT_CHORD = 1.0D;
    private static final double DEFAULT_SPAN = 1.0D;
    private static final double DEFAULT_AREA = DEFAULT_CHORD * DEFAULT_SPAN;
    private static final double DEFAULT_ASPECT_RATIO = DEFAULT_SPAN * DEFAULT_SPAN / DEFAULT_AREA;
    private static final double DEFAULT_OSWALD_EFFICIENCY = 0.9D;
    private static final double DEFAULT_ZERO_LIFT_DRAG = 0.02D;
    private static final double PEAK_LIFT_ANGLE_RADIANS = Math.toRadians(15.0D);
    private static final double ZERO_LIFT_ANGLE_RADIANS = Math.toRadians(25.0D);
    private static final double MIN_GROUP_FORCE_SQUARED = 1.0E-6D;

    private SymmetricAirfoilAerodynamics() {
    }

    static void contribute(
            BlockSubLevelAirfoilContext ctx,
            ServerSubLevel subLevel,
            @Nullable Pose3d localPose,
            double timeStep,
            Vector3dc linearVelocityWorld,
            Vector3dc angularVelocityWorld,
            Vector3d linearImpulseLocal,
            Vector3d angularImpulseLocal,
            @Nullable dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider.LiftProviderGroup group
    ) {
        Vector3d chordLocal = directionVector(ctx.chordDirection());
        Vector3d spanLocal = directionVector(ctx.spanDirection());
        if (chordLocal.lengthSquared() <= 1.0E-12D || spanLocal.lengthSquared() <= 1.0E-12D) {
            return;
        }
        chordLocal.normalize();
        spanLocal.normalize();

        Vector3d surfaceNormalLocal = new Vector3d(spanLocal).cross(chordLocal);
        if (surfaceNormalLocal.lengthSquared() <= 1.0E-12D) {
            return;
        }
        surfaceNormalLocal.normalize();

        Vector3d quarterChordLocal = quarterChordPoint(ctx.pos(), chordLocal);
        Vector3d chordWorld = new Vector3d(chordLocal);
        Vector3d spanWorld = new Vector3d(spanLocal);
        Vector3d aerodynamicCenterWorld = new Vector3d(quarterChordLocal);
        if (localPose != null) {
            localPose.transformNormal(chordWorld);
            localPose.transformNormal(spanWorld);
            localPose.transformPosition(aerodynamicCenterWorld);
        }

        Pose3d subLevelPose = subLevel.logicalPose();
        subLevelPose.transformNormal(chordWorld);
        subLevelPose.transformNormal(spanWorld);
        subLevelPose.transformPosition(aerodynamicCenterWorld);
        chordWorld.normalize();
        spanWorld.normalize();

        Vector3d offsetFromBodyCenterWorld = new Vector3d(aerodynamicCenterWorld).sub(subLevelPose.position());
        Vector3d pointVelocityWorld = WindTunnelMountService.shouldIgnoreSelfInducedAerodynamics(subLevel)
                ? new Vector3d()
                : new Vector3d(linearVelocityWorld)
                .add(new Vector3d(angularVelocityWorld).cross(offsetFromBodyCenterWorld, new Vector3d()));
        Vector3d airVelocityWorld = resolveAirVelocityWorld(subLevel, aerodynamicCenterWorld);
        Vector3d relativeAirVelocityWorld = airVelocityWorld.sub(pointVelocityWorld, new Vector3d());
        double relativeSpeedSquared = relativeAirVelocityWorld.lengthSquared();
        if (relativeSpeedSquared <= MIN_EFFECTIVE_SPEED_SQUARED) {
            return;
        }

        Vector3d effectiveFlowWorld = relativeAirVelocityWorld.sub(
                new Vector3d(spanWorld).mul(relativeAirVelocityWorld.dot(spanWorld)),
                new Vector3d());
        double effectiveSpeedSquared = effectiveFlowWorld.lengthSquared();
        if (effectiveSpeedSquared <= MIN_EFFECTIVE_SPEED_SQUARED) {
            return;
        }

        Vector3d dragDirectionWorld = relativeAirVelocityWorld.normalize(new Vector3d());
        Vector3d liftDirectionWorld = new Vector3d(spanWorld).cross(dragDirectionWorld);
        if (liftDirectionWorld.lengthSquared() <= 1.0E-12D) {
            return;
        }
        liftDirectionWorld.normalize();

        Vector3d flowFromFrontWorld = effectiveFlowWorld.normalize(new Vector3d()).negate();
        double alpha = Math.atan2(flowFromFrontWorld.dot(surfaceNormalLocalToWorld(ctx, localPose, subLevelPose)),
                flowFromFrontWorld.dot(chordWorld));

        double effectiveAirDensity = DimensionPhysicsData.getAirPressure(subLevel.getLevel(), aerodynamicCenterWorld)
                * EFFECTIVE_AIR_DENSITY_SCALE;
        if (effectiveAirDensity <= 0.0D) {
            return;
        }

        Coefficients coefficients = coefficients(alpha);
        double dynamicPressure = 0.5D * effectiveAirDensity * effectiveSpeedSquared;
        double liftMagnitude = dynamicPressure * DEFAULT_AREA * coefficients.cl() * timeStep;
        double dragMagnitude = dynamicPressure * DEFAULT_AREA * coefficients.cd() * timeStep;

        Vector3d aerodynamicCenterBodyLocal = subLevelPose.transformPositionInverse(aerodynamicCenterWorld, new Vector3d());

        Vector3d liftWorld = liftDirectionWorld.mul(liftMagnitude, new Vector3d());
        Vector3d dragWorld = dragDirectionWorld.mul(dragMagnitude, new Vector3d());
        Vector3d liftLocal = subLevelPose.transformNormalInverse(liftWorld, new Vector3d());
        Vector3d dragLocal = subLevelPose.transformNormalInverse(dragWorld, new Vector3d());
        if (liftLocal.lengthSquared() > MIN_GROUP_FORCE_SQUARED) {
            subLevel.getOrCreateQueuedForceGroup(ForceGroups.LIFT.get()).applyAndRecordPointForce(aerodynamicCenterBodyLocal, liftLocal);
        }
        if (dragLocal.lengthSquared() > MIN_GROUP_FORCE_SQUARED) {
            subLevel.getOrCreateQueuedForceGroup(ForceGroups.DRAG.get()).applyAndRecordPointForce(aerodynamicCenterBodyLocal, dragLocal);
        }
    }

    private static Vector3d resolveAirVelocityWorld(ServerSubLevel subLevel, Vector3d aerodynamicCenterWorld) {
        Vector3dc velocity = WindTunnelWindProvider.getWindVelocityAt(aerodynamicCenterWorld, subLevel.getLevel(), subLevel);
        return velocity == null ? new Vector3d() : new Vector3d(velocity);
    }

    private static Vector3d surfaceNormalLocalToWorld(BlockSubLevelAirfoilContext ctx, @Nullable Pose3d localPose, Pose3d subLevelPose) {
        Vector3d chordLocal = directionVector(ctx.chordDirection()).normalize();
        Vector3d spanLocal = directionVector(ctx.spanDirection()).normalize();
        Vector3d normalWorld = new Vector3d(spanLocal).cross(chordLocal).normalize();
        if (localPose != null) {
            localPose.transformNormal(normalWorld);
        }
        subLevelPose.transformNormal(normalWorld);
        return normalWorld.normalize();
    }

    private static Vector3d quarterChordPoint(BlockPos pos, Vector3d chordLocal) {
        return new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                .fma(0.25D * DEFAULT_CHORD, chordLocal);
    }

    private static Coefficients coefficients(double alpha) {
        double absAlpha = Math.abs(alpha);
        double aspectRatio = DEFAULT_ASPECT_RATIO;
        double liftSlope = (2.0D * Math.PI * aspectRatio) / (aspectRatio + 2.0D);
        double clLinear = liftSlope * alpha;
        double cdLinear = DEFAULT_ZERO_LIFT_DRAG + (clLinear * clLinear) / (Math.PI * DEFAULT_OSWALD_EFFICIENCY * aspectRatio);
        double cdPlate = 1.28D * Math.sin(alpha) * Math.sin(alpha);

        double peakLiftMagnitude = liftSlope * PEAK_LIFT_ANGLE_RADIANS;
        double cl;
        if (absAlpha <= PEAK_LIFT_ANGLE_RADIANS) {
            cl = clLinear;
        } else if (absAlpha >= ZERO_LIFT_ANGLE_RADIANS) {
            cl = 0.0D;
        } else {
            double liftRetention = 1.0D - smoothstep(PEAK_LIFT_ANGLE_RADIANS, ZERO_LIFT_ANGLE_RADIANS, absAlpha);
            cl = Math.copySign(peakLiftMagnitude * liftRetention, alpha);
        }

        double dragBlend = smoothstep(PEAK_LIFT_ANGLE_RADIANS, ZERO_LIFT_ANGLE_RADIANS, absAlpha);
        double cd = Math.max(DEFAULT_ZERO_LIFT_DRAG, lerp(cdLinear, cdPlate, dragBlend));
        return new Coefficients(cl, cd);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        if (edge1 <= edge0) {
            return value >= edge1 ? 1.0D : 0.0D;
        }
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double lerp(double start, double end, double delta) {
        return start + (end - start) * delta;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Vector3d directionVector(Direction direction) {
        return new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    record Coefficients(double cl, double cd) {
    }

    record BlockSubLevelAirfoilContext(BlockPos pos, BlockState state, Direction chordDirection, Direction spanDirection) {
    }
}
