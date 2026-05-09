package io.github.windtunnel.mixin;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.content.AirfoilContraptionContext;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerSubLevel.class)
public abstract class ServerSubLevelAirfoilMixin {
    @Inject(method = "prePhysicsTick", at = @At("HEAD"))
    private void windtunnel$beginAirfoilPhysicsTick(SubLevelPhysicsSystem physicsSystem, RigidBodyHandle handle,
                                                    double timeStep, CallbackInfo ci) {
        AirfoilContraptionContext.beginPhysicsTick();
    }

    @Inject(method = "prePhysicsTick", at = @At("RETURN"))
    private void windtunnel$endAirfoilPhysicsTick(SubLevelPhysicsSystem physicsSystem, RigidBodyHandle handle,
                                                  double timeStep, CallbackInfo ci) {
        AirfoilContraptionContext.endPhysicsTick();
    }

    @Redirect(
            method = "prePhysicsTick",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/sublevel/KinematicContraption;sable$liftProviders()Ljava/util/Map;"
            ),
            require = 0
    )
    private Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> windtunnel$registerContraptionAirfoilBlocks(
            KinematicContraption contraption
    ) {
        return AirfoilContraptionContext.registerLiftProviders(contraption);
    }
}
