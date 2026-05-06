package io.github.windtunnel.content;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Small compatibility helpers that identify Create content without depending on Create's
 * Registrate wrapper types at compile time.
 * <p>
 * All lookups use the vanilla {@link BuiltInRegistries} so the mod can compile against
 * Create's Maven coordinates without pulling in Registrate or other transitive wrappers.
 */
public final class CreateCompat {
    /** Resource location for Create's wrench item. */
    private static final ResourceLocation WRENCH_ID = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    private CreateCompat() {
    }

    /**
     * Returns true if the given stack is Create's wrench.
     * Used by all interactive blocks to let Create wrenches rotate them without opening GUIs.
     */
    @SuppressWarnings("null")
    public static boolean isCreateWrench(ItemStack stack) {
        return !stack.isEmpty() && WRENCH_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
