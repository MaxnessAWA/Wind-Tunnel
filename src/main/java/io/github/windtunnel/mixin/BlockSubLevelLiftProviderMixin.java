package io.github.windtunnel.mixin;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.windtunnel.compat.SableWindProviderBridge;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.function.BiFunction;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that injects registered wind providers (tunnels and aircraft injectors) into Sable's
 * aerodynamic lift/drag computation.
 * <p>
 * Sable's stock {@code BlockSubLevelLiftProvider} computes point velocity correctly, but it never
 * consults the registered ambient-air providers. This mixin injects right before the world
 * velocity is rotated into the craft's local frame, subtracting every registered airflow
 * contribution from the lift velocity vector {@code LIFT_VELO}.
 * <p>
 * This avoids copying the rest of Sable's lift/drag implementation — we simply adjust the
 * incoming air velocity before Sable resolves the aerodynamic forces from it.
 * <p>
 * The injection point is the {@code Pose3d.transformNormalInverse} call, which is the last
 * operation before the local velocity is fed into the lift/drag solver.
 */
@Mixin(BlockSubLevelLiftProvider.class)
public interface BlockSubLevelLiftProviderMixin {

    /**
     * Applies all registered wind providers to the lift velocity before Sable rotates it into
     * the craft's local frame.
     * <p>
     * Each registered {@code BiFunction<Vector3dc, Level, Vector3dc>} receives a copy of the
     * sample position (via {@code TEMP.set(LIFT_POS)}) and the current level, and returns an
     * air velocity vector to subtract from {@code LIFT_VELO}.
     *
     * @param ctx             Lift provider context containing per-point parameters
     * @param subLevel        The Sable sublevel being processed
     * @param localPose       Local pose of the lift provider point (may be null)
     * @param timeStep        Current physics time step
     * @param linearVelocity  Rigid-body linear velocity at the point
     * @param angularVelocity Rigid-body angular velocity at the point
     * @param linearImpulse   Output linear impulse accumulator
     * @param angularImpulse  Output angular impulse accumulator
     * @param group           Lift provider group (may be null)
     * @param ci              Mixin callback info
     */
    @Inject(
            method = "sable$contributeLiftAndDrag",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/companion/math/Pose3d;transformNormalInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"
            )
    )
    private void windtunnel$applyRegisteredWindProviders(
            final BlockSubLevelLiftProvider.LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            @Nullable final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group,
            final CallbackInfo ci
    ) {
        final ObjectList<BiFunction<Vector3dc, Level, Vector3dc>> windProviders =
                SableWindProviderBridge.getWindProviders();
        if (windProviders.isEmpty()) {
            return;
        }

        // Match SubLevelHelper's contract by giving providers a copy of the local sample position
        // rather than the shared static vector used by Sable's lift hot path.
        final Vector3d probePos = BlockSubLevelLiftProvider.TEMP.set(BlockSubLevelLiftProvider.LIFT_POS);
        for (final BiFunction<Vector3dc, Level, Vector3dc> windProvider : windProviders) {
            final Vector3dc airVelocity = windProvider.apply(probePos, subLevel.getLevel());
            if (airVelocity != null) {
                // Subtract the incoming air velocity from the lift velocity so Sable computes
                // aerodynamic forces relative to the ambient wind, not the world frame.
                BlockSubLevelLiftProvider.LIFT_VELO.sub(airVelocity);
            }
        }
    }
}
