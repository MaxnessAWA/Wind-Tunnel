package io.github.windtunnel.mixin.compat.synaxis;

import io.github.windtunnel.compat.SynaxisWindCompat;
import java.util.function.Supplier;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.verr1.synaxis.foundation.physics.control.flap.FlapControl", remap = false)
public abstract class SynaxisFlapControlMixin {
    @Shadow
    @Final
    private Supplier<?> config;

    @Redirect(
            method = "tick(Lcom/verr1/synaxis/foundation/physics/PhysicsStepContext;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/joml/Vector3d;add(Lorg/joml/Vector3dc;)Lorg/joml/Vector3d;"
            ),
            require = 0
    )
    private Vector3d windtunnel$applyWindToFlapVelocity(
            Vector3d linearVelocity,
            Vector3dc angularVelocityAtFlap,
            @Coerce Object context
    ) {
        Vector3d velocityAtFlap = linearVelocity.add(angularVelocityAtFlap);
        SynaxisWindCompat.applyWindToFlapVelocity(context, config.get(), velocityAtFlap);
        return velocityAtFlap;
    }
}
