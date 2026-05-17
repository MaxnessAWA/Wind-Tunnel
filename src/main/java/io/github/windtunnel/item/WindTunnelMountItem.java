package io.github.windtunnel.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Item for the Wind Tunnel Mount block.
 */
public class WindTunnelMountItem extends BlockItem {
    public WindTunnelMountItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.windtunnel.wind_tunnel_mount_bind.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
