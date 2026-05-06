package io.github.windtunnel.content;

import java.util.Objects;
import net.minecraft.core.Direction;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Shared helpers for turning "mount facing + AoA + beta" into a concrete local airflow vector.
 * Keeping the basis math in one place prevents the mount tools and injector from drifting into
 * incompatible angle conventions.
 */
public final class WindTunnelSharedAerodynamics {
    private static final Vector3d WORLD_UP = new Vector3d(0.0D, 1.0D, 0.0D);
    private static final Vector3d WORLD_NORTH = new Vector3d(0.0D, 0.0D, -1.0D);
    private static final double AXIS_EPSILON = 1.0E-6D;

    private WindTunnelSharedAerodynamics() {
    }

    public static Vector3dc computeWorldAirVelocity(Direction worldFlowDirection, double airspeed) {
        Objects.requireNonNull(worldFlowDirection, "worldFlowDirection");
        return new Vector3d(directionVector(worldFlowDirection)).mul(airspeed);
    }

    public static Vector3dc computeLocalRelativeFlow(Direction mountFacing, double angleOfAttack, double sideslipAngle) {
        Basis localBasis = Basis.fromForward(Objects.requireNonNull(mountFacing, "mountFacing"));
        // Mirror the stand's convention exactly by undoing the body rotations on a fixed body
        // basis, then converting the recovered zero-AoA forward axis back into incoming flow.
        Vector3d forwardAfterBeta = new Vector3d(localBasis.forward())
                .rotate(new Quaterniond().fromAxisAngleRad(localBasis.right(), Math.toRadians(-angleOfAttack)))
                .normalize();
        Vector3d upAfterBeta = new Vector3d(localBasis.up())
                .rotate(new Quaterniond().fromAxisAngleRad(localBasis.right(), Math.toRadians(-angleOfAttack)))
                .normalize();
        Vector3d baseForward = forwardAfterBeta
                .rotate(new Quaterniond().fromAxisAngleRad(upAfterBeta, Math.toRadians(-sideslipAngle)))
                .normalize();
        return baseForward.negate();
    }

    /**
     * Returns the ambient air velocity expressed in the rigid body's own local frame.
     * Sable later subtracts this vector from point velocity to recover velocity relative to air.
     */
    public static Vector3dc computeLocalAirVelocity(Direction mountFacing, double angleOfAttack, double sideslipAngle, double airspeed) {
        // The returned vector is the ambient air velocity seen by Sable. The wind provider later
        // subtracts it from the rigid-body point velocity to recover velocity relative to air.
        return new Vector3d(computeLocalRelativeFlow(mountFacing, angleOfAttack, sideslipAngle)).mul(airspeed);
    }

    private static Vector3d directionVector(Direction direction) {
        return new Vector3d(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private record Basis(Vector3d forward, Vector3d up, Vector3d right) {
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
    }
}
