package io.github.windtunnel.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Item for symmetric airfoil blocks.
 */
public class SymmetricAirfoilItem extends BlockItem {
    public SymmetricAirfoilItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.windtunnel.symmetric_airfoil.tooltip")
                .withStyle(ChatFormatting.GRAY));
        if (Screen.hasShiftDown()) {
            tooltipComponents.add(Component.translatable("item.windtunnel.symmetric_airfoil.tooltip.shift")
                    .withStyle(style -> style.withColor(0xFFD700)));
        } else {
            tooltipComponents.add(Component.translatable("item.windtunnel.symmetric_airfoil.tooltip.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
