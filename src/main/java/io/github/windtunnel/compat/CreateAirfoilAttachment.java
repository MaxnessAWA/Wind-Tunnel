package io.github.windtunnel.compat;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.content.contraptions.bearing.SailBlock;
import io.github.windtunnel.content.SymmetricAirfoilBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registers Create contraption attachment rules for symmetric airfoils.
 *
 * <p>Current behavior is intentionally minimal: airfoils do not advertise custom planar
 * attachment, but they do explicitly refuse mutual attachment with Create sails so windmill
 * sail assembly does not pull airfoils into the structure.</p>
 */
public final class CreateAirfoilAttachment {
    private static boolean registered;

    private CreateAirfoilAttachment() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        BlockMovementChecks.registerAttachedCheck((state, world, pos, direction) -> {
            if (isSailAirfoilPair(state, world, pos, direction)) {
                return BlockMovementChecks.CheckResult.FAIL;
            }
            return BlockMovementChecks.CheckResult.PASS;
        });

        registered = true;
    }

    private static boolean isSailAirfoilPair(BlockState state, Level world, net.minecraft.core.BlockPos pos, Direction direction) {
        BlockState neighbor = world.getBlockState(pos.relative(direction));
        return isAirfoil(state) && isSail(neighbor)
                || isSail(state) && isAirfoil(neighbor);
    }

    private static boolean isAirfoil(BlockState state) {
        return state.getBlock() instanceof SymmetricAirfoilBlock;
    }

    private static boolean isSail(BlockState state) {
        return state.getBlock() instanceof SailBlock;
    }
}
