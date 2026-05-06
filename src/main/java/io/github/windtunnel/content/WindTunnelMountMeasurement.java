package io.github.windtunnel.content;

/**
 * Aggregated forces and moments reported by the mount after one physics step.
 * Values are expressed in the aerodynamic axes chosen by the mount, not directly in world axes.
 */
public record WindTunnelMountMeasurement(
        double lift,
        double drag,
        double sideForce,
        double pitchMoment,
        double rollMoment,
        double yawMoment
) {
    public static final WindTunnelMountMeasurement EMPTY = new WindTunnelMountMeasurement(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
    private static final double EPSILON = 1.0E-4D;

    public boolean nearlyEquals(WindTunnelMountMeasurement other) {
        // Measurement sync is throttled, so tiny float jitter should not trigger packet spam.
        return Math.abs(lift - other.lift) <= EPSILON
                && Math.abs(drag - other.drag) <= EPSILON
                && Math.abs(sideForce - other.sideForce) <= EPSILON
                && Math.abs(pitchMoment - other.pitchMoment) <= EPSILON
                && Math.abs(rollMoment - other.rollMoment) <= EPSILON
                && Math.abs(yawMoment - other.yawMoment) <= EPSILON;
    }
}
