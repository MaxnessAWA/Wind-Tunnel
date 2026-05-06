package io.github.windtunnel.content;

import io.github.windtunnel.registry.WindTunnelMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Minimal menu carrying the injector position to the client.
 */
public class AirflowInjectorMenu extends AbstractContainerMenu {
    private final BlockPos injectorPos;

    public AirflowInjectorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, extraData.readBlockPos());
    }

    public AirflowInjectorMenu(int containerId, BlockPos injectorPos) {
        super(WindTunnelMenus.AIRFLOW_INJECTOR.get(), containerId);
        this.injectorPos = injectorPos;
    }

    public BlockPos getInjectorPos() {
        return injectorPos;
    }

    @Override
    public boolean stillValid(@NotNull Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(@NotNull Player player, int index) {
        return ItemStack.EMPTY;
    }
}
