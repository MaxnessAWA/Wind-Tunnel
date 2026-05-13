package io.github.windtunnel.content;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Small Aeronautics compatibility helpers that avoid hard compile-time coupling to mod item classes.
 */
public final class AeronauticsCompat {
    private static final ResourceLocation AVIATORS_GOGGLES_ID =
            ResourceLocation.fromNamespaceAndPath("aeronautics", "aviators_goggles");

    private AeronauticsCompat() {
    }

    public static boolean isWearingAviatorsGoggles(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        // Aeronautics registers aviators_goggles as head armor; only the equipped head slot counts.
        return isAviatorsGoggles(entity.getItemBySlot(EquipmentSlot.HEAD));
    }

    public static boolean isAviatorsGoggles(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return AVIATORS_GOGGLES_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
