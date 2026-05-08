package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Dedicated creative mode tab for the mod's blocks.
 */
@SuppressWarnings("null")
public final class WindTunnelCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WindTunnelMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.windtunnel.main"))
                    .icon(WindTunnelCreativeTabs::createWindTunnelIcon)
                    .displayItems((parameters, output) -> {
                        // All block items in the mod
                        output.accept(WindTunnelBlocks.WIND_TUNNEL_ITEM.get());
                        output.accept(WindTunnelBlocks.WIND_TUNNEL_CONTROLLER_ITEM.get());
                        output.accept(WindTunnelBlocks.AIRFLOW_INJECTOR_ITEM.get());
                        output.accept(WindTunnelBlocks.WIND_TUNNEL_MOUNT_ITEM.get());
                        output.accept(WindTunnelBlocks.WIND_TUNNEL_MOUNT_INTERFACE_ITEM.get());
                        for (var airfoilItem : WindTunnelBlocks.symmetricAirfoilItems()) {
                            output.accept(airfoilItem.get());
                        }
                        for (var airfoilItem : WindTunnelBlocks.verticalSymmetricAirfoilItems()) {
                            output.accept(airfoilItem.get());
                        }
                    })
                    .build());

    private WindTunnelCreativeTabs() {
    }

    private static ItemStack createWindTunnelIcon() {
        return new ItemStack(WindTunnelBlocks.WIND_TUNNEL_ITEM.get());
    }
}
