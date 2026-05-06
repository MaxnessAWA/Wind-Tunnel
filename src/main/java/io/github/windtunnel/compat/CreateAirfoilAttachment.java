package io.github.windtunnel.compat;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import io.github.windtunnel.content.SymmetricAirfoilBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers Create contraption attachment rules for symmetric airfoils.
 *
 * <p>Unlike Create sails, the airfoil block's {@code FACING} property is the chord direction,
 * not the surface normal. Attachment therefore has to use the derived panel normal so
 * contraption assembly propagates within the wing plane instead of along the chord.</p>
 */
public final class CreateAirfoilAttachment {
    private static boolean registered;

    private CreateAirfoilAttachment() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        BlockMovementChecks.registerAttachedCheck((state, world, pos, direction) ->
                isAirfoil(state) ? BlockMovementChecks.CheckResult.of(isAttachedTowards(state, direction))
                        : BlockMovementChecks.CheckResult.PASS);

        BlockMovementChecks.registerNotSupportiveCheck((state, direction) ->
                isAirfoil(state) ? BlockMovementChecks.CheckResult.of(isNotSupportiveTowards(state, direction))
                        : BlockMovementChecks.CheckResult.PASS);

        registered = true;
    }

    private static boolean isAirfoil(BlockState state) {
        return state.getBlock() instanceof SymmetricAirfoilBlock;
    }

    private static boolean isAttachedTowards(BlockState state, Direction direction) {
        return direction.getAxis() != SymmetricAirfoilBlock.surfaceNormalAxis(state);
    }

    private static boolean isNotSupportiveTowards(BlockState state, Direction direction) {
        return direction.getAxis() == SymmetricAirfoilBlock.surfaceNormalAxis(state);
    }
}
