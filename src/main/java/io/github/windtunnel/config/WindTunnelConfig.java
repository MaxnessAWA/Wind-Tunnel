package io.github.windtunnel.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side tuning knobs for the wind tunnel flow field.
 * <p>
 * These values are intentionally narrow in scope: they shape airflow behaviour but do not
 * control controller UI limits, which are gameplay rules defined in the controller block entity.
 * <p>
 * Configurable parameters:
 * <ul>
 * <li><b>maxRange</b> — Maximum number of open blocks scanned in front of a wind tunnel (1-256).</li>
 * <li><b>baseAirspeed</b> — Base local air velocity in blocks/second (0.1-128.0).</li>
 * <li><b>forceFalloff</b> — How much the push weakens from nozzle to end of stream (0.0-0.95).</li>
 * <li><b>crossSectionRadius</b> — Half-width on the two axes perpendicular to flow (0.1-3.0).</li>
 * <li><b>maxAirspeed</b> — Upper clamp for injected air velocity (0.1-128.0).</li>
 * </ul>
 */
public final class WindTunnelConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_RANGE;
    private static final ModConfigSpec.DoubleValue BASE_AIRSPEED;
    private static final ModConfigSpec.DoubleValue FORCE_FALLOFF;
    private static final ModConfigSpec.DoubleValue CROSS_SECTION_RADIUS;
    private static final ModConfigSpec.DoubleValue MAX_AIRSPEED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("wind_tunnel");

        MAX_RANGE = builder
                .comment("Maximum number of open blocks scanned in front of a wind tunnel.")
                .defineInRange("maxRange", 16, 1, 256);

        BASE_AIRSPEED = builder
                .comment("Base local air velocity injected by the tunnel, in blocks-per-second.")
                .defineInRange("baseAirspeed", 12.0D, 0.1D, 128.0D);

        FORCE_FALLOFF = builder
                .comment("How much the push weakens from the nozzle to the end of the stream.")
                .defineInRange("forceFalloff", 0.65D, 0.0D, 0.95D);

        CROSS_SECTION_RADIUS = builder
                .comment("Half-width of the tunnel effect on the two axes perpendicular to the flow.")
                .defineInRange("crossSectionRadius", 0.85D, 0.1D, 3.0D);

        MAX_AIRSPEED = builder
                .comment("Upper clamp for the injected local air velocity, in blocks-per-second.")
                .defineInRange("maxAirspeed", 64.0D, 0.1D, 128.0D);

        builder.pop();
        SPEC = builder.build();
    }

    private WindTunnelConfig() {
    }

    // ---- Config accessors ----

    /** Maximum forward scan range in blocks (1-256). */
    public static int maxRange() {
        return MAX_RANGE.get();
    }

    /** Base airspeed in blocks/second (0.1-128.0). */
    public static double baseAirspeed() {
        return BASE_AIRSPEED.get();
    }

    /** Force falloff exponent (0.0-0.95). */
    public static double forceFalloff() {
        return FORCE_FALLOFF.get();
    }

    /** Cross-section radius (0.1-3.0). */
    public static double crossSectionRadius() {
        return CROSS_SECTION_RADIUS.get();
    }

    /** Maximum airspeed clamp (0.1-128.0). */
    public static double maxAirspeed() {
        return MAX_AIRSPEED.get();
    }
}
