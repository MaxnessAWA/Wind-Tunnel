package io.github.windtunnel.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side tuning knobs for wind tunnel airflow and optional quadratic sail aerodynamics.
 */
public final class WindTunnelConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue MAX_RANGE;
    private static final ModConfigSpec.DoubleValue BASE_AIRSPEED;
    private static final ModConfigSpec.DoubleValue CROSS_SECTION_RADIUS;
    private static final ModConfigSpec.DoubleValue MAX_AIRSPEED;
    private static final ModConfigSpec.BooleanValue QUADRATIC_SAIL_AERODYNAMICS;
    private static final ModConfigSpec.DoubleValue NORMAL_DRAG_SOFT_CAP;
    private static final ModConfigSpec.DoubleValue LIFT_SOFT_CAP;
    private static final ModConfigSpec.DoubleValue LIFT_TUNING;
    private static final ModConfigSpec.DoubleValue PARALLEL_DRAG_TUNING;
    private static final ModConfigSpec.DoubleValue DIRECTIONLESS_DRAG_TUNING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("wind_tunnel");

        MAX_RANGE = builder
                .comment("Maximum number of open blocks scanned in front of a wind tunnel.")
                .defineInRange("maxRange", 256, 1, 256);

        BASE_AIRSPEED = builder
                .comment("Base local air velocity injected by the tunnel, in blocks-per-second.")
                .defineInRange("baseAirspeed", 12.0D, 0.1D, 256.0D);

        CROSS_SECTION_RADIUS = builder
                .comment("Half-width of the tunnel effect on the two axes perpendicular to the flow.")
                .defineInRange("crossSectionRadius", 0.85D, 0.1D, 3.0D);

        MAX_AIRSPEED = builder
                .comment("Upper clamp for the injected local air velocity, in blocks-per-second.")
                .defineInRange("maxAirspeed", 128.0D, 0.1D, 256.0D);

        QUADRATIC_SAIL_AERODYNAMICS = builder
                .comment("When true, Create sails and Simulated symmetric sails use velocity-squared lift/drag scaling instead of linear velocity scaling.")
                .define("quadraticSailAerodynamics", false);

        NORMAL_DRAG_SOFT_CAP = builder
                .comment("Active when quadraticSailAerodynamics is enabled")
                .defineInRange("normalDragSoftCap", 50.0D, 20.0D, 256.0D);

        LIFT_SOFT_CAP = builder
                .comment("Active when quadraticSailAerodynamics is enabled")
                .defineInRange("liftSoftCap", 40.0D, 20.0D, 256.0D);

        LIFT_TUNING = builder
                .comment("Active when quadraticSailAerodynamics is enabled")
                .defineInRange("liftTuning", 0.16D, 0.1D, 1.0D);

        PARALLEL_DRAG_TUNING = builder
                .comment("Active when quadraticSailAerodynamics is enabled")
                .defineInRange("parallelDragTuning", 0.35D, 0.1D, 1.0D);

        DIRECTIONLESS_DRAG_TUNING = builder
                .comment("Active when quadraticSailAerodynamics is enabled")
                .defineInRange("directionlessDragTuning", 0.1D, 0.02D, 1.0D);

        builder.pop();
        SPEC = builder.build();
    }

    private WindTunnelConfig() {
    }

    public static int maxRange() {
        return MAX_RANGE.get();
    }

    public static double baseAirspeed() {
        return BASE_AIRSPEED.get();
    }

    public static double crossSectionRadius() {
        return CROSS_SECTION_RADIUS.get();
    }

    public static double maxAirspeed() {
        return MAX_AIRSPEED.get();
    }

    public static boolean quadraticSailAerodynamics() {
        return QUADRATIC_SAIL_AERODYNAMICS.get();
    }

    public static double normalDragSoftCap() {
        return NORMAL_DRAG_SOFT_CAP.get();
    }

    public static double liftSoftCap() {
        return LIFT_SOFT_CAP.get();
    }

    public static double liftTuning() {
        return LIFT_TUNING.get();
    }

    public static double parallelDragTuning() {
        return PARALLEL_DRAG_TUNING.get();
    }

    public static double directionlessDragTuning() {
        return DIRECTIONLESS_DRAG_TUNING.get();
    }
}
