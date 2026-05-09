package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.block.BlockSubLevelLiftProvider;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

public final class AirfoilContraptionContext {
    private static final ThreadLocal<IdentityHashMap<BlockSubLevelLiftProvider.LiftProviderContext, BlockGetter>> BLOCK_GETTERS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private AirfoilContraptionContext() {
    }

    public static void beginPhysicsTick() {
        BLOCK_GETTERS.get().clear();
    }

    public static void endPhysicsTick() {
        BLOCK_GETTERS.get().clear();
    }

    public static Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> registerLiftProviders(KinematicContraption contraption) {
        Map<BlockPos, BlockSubLevelLiftProvider.LiftProviderContext> liftProviders = contraption.sable$liftProviders();
        if (!liftProviders.isEmpty()) {
            BlockGetter blockGetter = contraption.sable$blockGetter();
            IdentityHashMap<BlockSubLevelLiftProvider.LiftProviderContext, BlockGetter> blockGetters = BLOCK_GETTERS.get();
            for (BlockSubLevelLiftProvider.LiftProviderContext context : liftProviders.values()) {
                blockGetters.put(context, blockGetter);
            }
        }
        return liftProviders;
    }

    public static BlockGetter getBlockGetter(BlockSubLevelLiftProvider.LiftProviderContext context) {
        return BLOCK_GETTERS.get().get(context);
    }
}
