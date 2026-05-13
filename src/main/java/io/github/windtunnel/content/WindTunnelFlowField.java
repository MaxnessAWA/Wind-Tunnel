package io.github.windtunnel.content;

import com.simibubi.create.AllTags;
import com.simibubi.create.content.decoration.copycat.CopycatBlock;
import io.github.windtunnel.config.WindTunnelConfig;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Immutable description of one tunnel's current airflow volume.
 * <p>
 * It answers two questions:
 * <ol>
 * <li>How far the stream can reach before hitting solid geometry (via ray-marching).</li>
 * <li>What local air velocity a sample point should see inside that volume.</li>
 * </ol>
 * <p>
 * The field models a uniform test section: once a point is inside the valid flow volume,
 * it sees the configured airspeed without spatial decay. Partial blocks are handled by
 * sampling the collision shape at multiple test coordinates, matching Create's fan
 * transparency behavior.
 */
@SuppressWarnings("null")
public record WindTunnelFlowField(
        /** Direction of the airflow (where it points toward). */
        Direction direction,
        /** How far the stream reaches before hitting solid geometry. */
        double length,
        /** Axis-aligned bounding box of the airflow volume. */
        AABB bounds,
        /** Full velocity vector (direction * airspeed). */
        Vec3 impulse,
        /** Unit direction vector (or ZERO if airspeed is near-zero). */
        Vec3 normalizedImpulse,
        /** Scalar airspeed magnitude. */
        double impulseMagnitude,
        /** Center of the nozzle face, offset slightly forward. */
        Vec3 nozzleCenter,
        /** Attenuation length (currently unused — uniform test section). */
        double attenuationLength
) {
    /**
     * Test coordinates used for depth-sampling partial blocks.
     * These are distributed across the block face to handle non-full collision shapes.
     */
    private static final double[][] DEPTH_TEST_COORDINATES = {
            {0.25D, 0.25D},
            {0.25D, 0.75D},
            {0.5D, 0.5D},
            {0.75D, 0.25D},
            {0.75D, 0.75D}
    };

    public WindTunnelFlowField {
        attenuationLength = Math.max(1.0D, attenuationLength);
    }

    /** Shorthand factory using config defaults for range and airspeed. */
    @Nullable
    public static WindTunnelFlowField create(Level level, BlockPos origin, Direction direction) {
        return create(level, origin, direction, WindTunnelConfig.maxRange(), WindTunnelConfig.baseAirspeed(), WindTunnelConfig.maxAirspeed());
    }

    /** Factory with custom base airspeed, config defaults for range. */
    @Nullable
    public static WindTunnelFlowField create(Level level, BlockPos origin, Direction direction, double baseAirspeed) {
        return create(level, origin, direction, WindTunnelConfig.maxRange(), baseAirspeed, WindTunnelConfig.maxAirspeed());
    }

    /** Factory with custom length and airspeed, config cap for max airspeed. */
    @Nullable
    public static WindTunnelFlowField create(Level level, BlockPos origin, Direction direction, int configuredLength, double baseAirspeed) {
        return create(
                level,
                origin,
                direction,
                configuredLength,
                baseAirspeed,
                WindTunnelConfig.maxAirspeed()
        );
    }

    /**
     * Core factory: ray-marches forward to find the open length, then returns an immutable
     * field record. Returns {@code null} if the first obstruction is too close.
     */
    @Nullable
    private static WindTunnelFlowField create(Level level, BlockPos origin, Direction direction, int configuredLength, double baseAirspeed, double maxAirspeed) {
        // The tunnel only acts through open space. If the first solid obstruction is too close,
        // there is no useful flow field to register.
        double openLength = findOpenLength(level, origin, direction, Math.max(1, configuredLength));
        if (openLength <= 0.0D) {
            return null;
        }

        double clampedAirspeed = Mth.clamp(baseAirspeed, 0.0D, maxAirspeed);
        Vec3 impulse = Vec3.atLowerCornerOf(direction.getNormal()).scale(clampedAirspeed);
        Vec3 normalizedImpulse = clampedAirspeed <= 1.0E-8D ? Vec3.ZERO : Vec3.atLowerCornerOf(direction.getNormal());
        return new WindTunnelFlowField(
                direction,
                openLength,
                createBounds(origin, direction, openLength, WindTunnelConfig.crossSectionRadius()),
                impulse,
                normalizedImpulse,
                clampedAirspeed,
                Vec3.atCenterOf(origin).add(impulse.scale(0.75D)),
                Math.max(1.0D, openLength)
        );
    }

    /**
     * Returns 1.0 — the tunnel models a uniform test section with no spatial decay.
     * Points inside the valid flow volume see the configured airspeed regardless of
     * distance from the nozzle.
     */
    public double attenuationFor(Vec3 samplePoint, BlockPos origin) {
        return 1.0D;
    }

    /**
     * Computes the local air velocity at a sample point.
     * Returns {@link Vec3#ZERO} if the point is outside the flow volume.
     * <p>
     * Returning air velocity instead of force lets Sable compute lift/drag in its normal way.
     */
    public Vec3 airVelocityAt(Vec3 samplePoint, BlockPos origin) {
        if (!this.bounds.contains(samplePoint)) {
            return Vec3.ZERO;
        }

        double magnitude = this.impulseMagnitude * this.attenuationFor(samplePoint, origin);
        if (magnitude <= 0.0D) {
            return Vec3.ZERO;
        }

        return this.normalizedImpulse.scale(magnitude);
    }

    /**
     * Ray-marches forward from the origin to find the open length.
     * Each step checks the collision shape of the block at that position.
     * Full cubes terminate immediately; partial blocks are depth-sampled.
     * Chunks that are not loaded stop the scan (no forced chunk loads).
     */
    private static double findOpenLength(Level level, BlockPos origin, Direction direction, int maxRange) {
        for (int step = 0; step < maxRange; step++) {
            BlockPos currentPos = origin.relative(direction, step + 1);
            if (!level.isLoaded(currentPos)) {
                // Do not force chunk loads while sampling a purely local effect.
                return step;
            }

            BlockState currentState = level.getBlockState(currentPos);
            BlockState copycatState = CopycatBlock.getMaterial(level, currentPos);
            if (shouldAlwaysPass(copycatState.isAir() ? currentState : copycatState)) {
                continue;
            }

            VoxelShape shape = currentState.getCollisionShape(level, currentPos);
            if (shape.isEmpty()) {
                continue;
            }

            if (shape == Shapes.block()) {
                // Full cubes terminate the stream immediately at the previous step.
                return step;
            }

            // Partial blocks mimic Create's fan transparency handling: measure the deepest blocking
            // face sample and trim the stream to that point.
            double shapeDepth = findMaxDepth(shape, direction);
            if (shapeDepth == Double.POSITIVE_INFINITY) {
                continue;
            }

            return Math.min(step + shapeDepth + 1.0D / 32.0D, maxRange);
        }

        return maxRange;
    }

    /** Passes through blocks tagged as {@code FAN_TRANSPARENT} in Create. */
    private static boolean shouldAlwaysPass(BlockState state) {
        return AllTags.AllBlockTags.FAN_TRANSPARENT.matches(state);
    }

    /**
     * Samples the collision shape at multiple test coordinates along the flow axis to find
     * the deepest penetration of a partial block.
     */
    private static double findMaxDepth(VoxelShape shape, Direction direction) {
        Axis axis = direction.getAxis();
        AxisDirection axisDirection = direction.getAxisDirection();
        double maxDepth = 0.0D;

        for (double[] coordinates : DEPTH_TEST_COORDINATES) {
            double depth;
            if (axisDirection == AxisDirection.POSITIVE) {
                double min = shape.min(axis, coordinates[0], coordinates[1]);
                if (min == Double.POSITIVE_INFINITY) {
                    return Double.POSITIVE_INFINITY;
                }
                depth = min;
            } else {
                double max = shape.max(axis, coordinates[0], coordinates[1]);
                if (max == Double.NEGATIVE_INFINITY) {
                    return Double.POSITIVE_INFINITY;
                }
                depth = 1.0D - max;
            }

            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }

        return maxDepth;
    }

    /**
     * Builds the AABB for the airflow volume: a forward box from the origin, inflated only on
     * the two axes orthogonal to the flow direction.
     */
    private static AABB createBounds(BlockPos origin, Direction direction, double length, double radius) {
        // Build a forward box first, then inflate only on the two axes orthogonal to the flow.
        AABB baseBox = new AABB(origin.relative(direction));
        Vec3 directionVec = Vec3.atLowerCornerOf(direction.getNormal());
        double factor = length - 1.0D;
        Vec3 scale = directionVec.scale(factor);
        AABB flowBox = factor > 0.0D
                ? baseBox.expandTowards(scale)
                : baseBox.contract(scale.x, scale.y, scale.z).move(scale);

        return switch (direction.getAxis()) {
            case X -> flowBox.inflate(0.0D, radius, radius);
            case Y -> flowBox.inflate(radius, 0.0D, radius);
            case Z -> flowBox.inflate(radius, radius, 0.0D);
        };
    }
}
