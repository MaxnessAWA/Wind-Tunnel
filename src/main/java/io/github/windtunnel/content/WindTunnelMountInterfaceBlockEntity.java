package io.github.windtunnel.content;

import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Render carrier for the animated mount-interface insert.
 * The gameplay logic remains stateless; this block entity only exists so the animated inner part
 * can be rendered through a BER while the outer shell stays a normal baked block model.
 */
@SuppressWarnings("null")
public class WindTunnelMountInterfaceBlockEntity extends SyncedBlockEntity {
    public WindTunnelMountInterfaceBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.WIND_TUNNEL_MOUNT_INTERFACE.get(), pos, blockState);
    }

    public Direction getFacing() {
        return getBlockState().getValue(WindTunnelMountInterfaceBlock.FACING);
    }
}
