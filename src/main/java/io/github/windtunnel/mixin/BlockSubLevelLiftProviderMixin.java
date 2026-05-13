package io.github.windtunnel.mixin;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider.LiftProviderContext;
import dev.ryanhcode.sable.companion.math.Pose3d;
import io.github.windtunnel.compat.SableWindProviderBridge;
import io.github.windtunnel.config.WindTunnelConfig;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.function.BiFunction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin that injects registered wind providers (tunnels and aircraft injectors)
 * into Sable's
 * aerodynamic lift/drag computation.
 * <p>
 * Sable's stock {@code BlockSubLevelLiftProvider} computes point velocity
 * correctly, but it never
 * consults the registered ambient-air providers. This mixin injects right
 * before the world
 * velocity is rotated into the craft's local frame, subtracting every
 * registered airflow
 * contribution from the lift velocity vector {@code LIFT_VELO}.
 * <p>
 * This avoids copying the rest of Sable's lift/drag implementation — we simply
 * adjust the
 * incoming air velocity before Sable resolves the aerodynamic forces from it.
 * <p>
 * The injection point is the {@code Pose3d.transformNormalInverse} call, which
 * is the last
 * operation before the local velocity is fed into the lift/drag solver.
 */
@Mixin(BlockSubLevelLiftProvider.class)
public interface BlockSubLevelLiftProviderMixin {
    /**
     * Applies all registered wind providers to the lift velocity before Sable
     * rotates it into
     * the craft's local frame.
     * <p>
     * Each registered {@code BiFunction<Vector3dc, Level, Vector3dc>} receives a
     * copy of the
     * sample position (via {@code TEMP.set(LIFT_POS)}) and the current level, and
     * returns an
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
    @Inject(method = "sable$contributeLiftAndDrag", at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/companion/math/Pose3d;transformNormalInverse(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
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
            final CallbackInfo ci) {
        final ObjectList<BiFunction<Vector3dc, Level, Vector3dc>> windProviders = SableWindProviderBridge
                .getWindProviders();
        if (windProviders.isEmpty()) {
            return;
        }

        // Match SubLevelHelper's contract by giving providers a copy of the local
        // sample position
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

        if (!WindTunnelConfig.quadraticSailAerodynamics()) {
            return;
        }

        final Block block = ctx.state().getBlock();
        if (!windtunnel$usesQuadraticSailAerodynamics(block)) {
            return;
        }

        final double speed = BlockSubLevelLiftProvider.LIFT_VELO.length();
        if (speed <= 1.0E-8D) {
            return;
        }

        // Sable's stock solver is linear in velocity. Multiply by |v| here so the
        // downstream
        // drag and lift terms become proportional to v^2 while preserving
        // direction/sign.
        BlockSubLevelLiftProvider.LIFT_VELO.mul(speed);
    }

    // @Inject(method = "sable$contributeLiftAndDrag", at = @At(value = "INVOKE",
    // target = "Lorg/joml/Vector3d;add{2}(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
    // shift = Shift.BEFORE), locals = )
    // private void windtunnel$parallelDragMixin(
    // final BlockSubLevelLiftProvider.LiftProviderContext ctx,
    // final ServerSubLevel subLevel,
    // @Nullable final Pose3d localPose,
    // final double timeStep,
    // final Vector3dc linearVelocity,
    // final Vector3dc angularVelocity,
    // final Vector3d linearImpulse,
    // final Vector3d angularImpulse,
    // @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group,
    // final CallbackInfo ci) {
    // if (!WindTunnelConfig.quadraticSailAerodynamics()) {
    // return;
    // }

    // final Block block = ctx.state().getBlock();
    // if (!windtunnel$usesQuadraticSailAerodynamics(block)) {
    // return;
    // }
    // }

    @ModifyVariable(
            method = "sable$contributeLiftAndDrag",
            name = "dragStrength",
            at = @At(value = "STORE", target = "dragStrength", ordinal = 0),
            require = 1)
    private double windtunnel$modifyParallelDragStrength(
            final double original,
            final LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group) {
        if (!WindTunnelConfig.quadraticSailAerodynamics()) {
            return original;
        }

        final Block block = ctx.state().getBlock();
        if (!windtunnel$usesQuadraticSailAerodynamics(block)) {
            return original;
        }

        double normalDragSoftCap = WindTunnelConfig.normalDragSoftCap();
        double vnSq = BlockSubLevelLiftProvider.LIFT_NORMAL.dot(BlockSubLevelLiftProvider.LIFT_VELO);
        return original * WindTunnelConfig.parallelDragTuning() / (1 + (vnSq / normalDragSoftCap / normalDragSoftCap));
    }

    @ModifyVariable(
            method = "sable$contributeLiftAndDrag",
            name = "dragStrength",
            at = @At(value = "STORE", target = "dragStrength", ordinal = 1),
            require = 1)
    private double windtunnel$modifyDirectionlessDragStrength(
            final double original,
            final LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group) {
        if (!WindTunnelConfig.quadraticSailAerodynamics()) {
            return original;
        }

        final Block block = ctx.state().getBlock();
        if (!windtunnel$usesQuadraticSailAerodynamics(block)) {
            return original;
        }

        return original * WindTunnelConfig.directionlessDragTuning();
    }

    @ModifyVariable(
            method = "sable$contributeLiftAndDrag",
            name = "liftStrength",
            at = @At(value = "STORE", target = "liftStrength", ordinal = 0),
            require = 1)
    private double windtunnel$modifyLiftStrength(
            final double original,
            final LiftProviderContext ctx,
            final ServerSubLevel subLevel,
            final Pose3d localPose,
            final double timeStep,
            final Vector3dc linearVelocity,
            final Vector3dc angularVelocity,
            final Vector3d linearImpulse,
            final Vector3d angularImpulse,
            @Nullable final BlockSubLevelLiftProvider.LiftProviderGroup group) {
        if (!WindTunnelConfig.quadraticSailAerodynamics()) {
            return original;
        }

        final Block block = ctx.state().getBlock();
        if (!windtunnel$usesQuadraticSailAerodynamics(block)) {
            return original;
        }

        double liftSoftCap = WindTunnelConfig.liftSoftCap();
        double vnSq = BlockSubLevelLiftProvider.LIFT_NORMAL.dot(BlockSubLevelLiftProvider.LIFT_VELO);
        return original * WindTunnelConfig.liftTuning() / (1 + (vnSq / liftSoftCap / liftSoftCap));
    }

    private static boolean windtunnel$usesQuadraticSailAerodynamics(final Block block) {
        final Class<?> blockClass = block.getClass();
        final String name = blockClass.getName();
        return "com.simibubi.create.content.contraptions.bearing.SailBlock".equals(name)
                || "dev.simulated_team.simulated.content.blocks.symmetric_sail.SymmetricSailBlock".equals(name);
    }
}
