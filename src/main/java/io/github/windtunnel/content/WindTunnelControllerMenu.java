package io.github.windtunnel.content;

import io.github.windtunnel.registry.WindTunnelMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Minimal menu used only to carry the controller position to the client screen.
 * No inventory slots are exposed.
 */
@SuppressWarnings("null")
public class WindTunnelControllerMenu extends AbstractContainerMenu {
    private final BlockPos controllerPos;

    public WindTunnelControllerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos());
    }

    public WindTunnelControllerMenu(int containerId, BlockPos controllerPos) {
        super(WindTunnelMenus.WIND_TUNNEL_CONTROLLER.get(), containerId);
        this.controllerPos = controllerPos;
    }

    public BlockPos getControllerPos() {
        return controllerPos;
    }

    @Override
    public boolean stillValid(Player player) {
        // Standard short-range interaction check to close the screen if the player walks away.
        return player.level().isLoaded(controllerPos)
                && player.distanceToSqr(
                        controllerPos.getX() + 0.5D,
                        controllerPos.getY() + 0.5D,
                        controllerPos.getZ() + 0.5D
                ) <= 64.0D;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
