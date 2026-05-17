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
 * Item for the Hologram Projector block.
 */
public class HologramProjectorItem extends BlockItem {
    public HologramProjectorItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.windtunnel.hologram_projector.tooltip")
                .withStyle(ChatFormatting.GRAY));
    }
}
