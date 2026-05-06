package io.github.windtunnel.content;

import io.github.windtunnel.registry.WindTunnelMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Minimal menu carrying the target mount position to the client.
 */
@SuppressWarnings("null")
public class WindTunnelMountMenu extends AbstractContainerMenu {
    private final BlockPos mountPos;

    public WindTunnelMountMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos());
    }

    public WindTunnelMountMenu(int containerId, BlockPos mountPos) {
        super(WindTunnelMenus.WIND_TUNNEL_MOUNT.get(), containerId);
        this.mountPos = mountPos;
    }

    public BlockPos getMountPos() {
        return mountPos;
    }

    @Override
    public boolean stillValid(Player player) {
        // Left permissive because the mount screen is used for calibration and the user may want
        // to stand away from the stand while watching the aircraft. Server-side edits are still
        // bounded by block existence.
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
