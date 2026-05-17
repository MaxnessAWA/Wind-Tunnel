package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.item.AirflowInjectorItem;
import io.github.windtunnel.item.HologramProjectorItem;
import io.github.windtunnel.item.SymmetricAirfoilItem;
import io.github.windtunnel.item.WindTunnelControllerItem;
import io.github.windtunnel.item.WindTunnelItem;
import io.github.windtunnel.item.WindTunnelMountInterfaceItem;
import io.github.windtunnel.item.WindTunnelMountItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Centralized item registration for the Wind Tunnel mod.
 */
@SuppressWarnings("null")
public final class WindTunnelItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WindTunnelMod.MOD_ID);

    public static final DeferredHolder<Item, WindTunnelItem> WIND_TUNNEL_ITEM =
            registerWindTunnelItem("wind_tunnel", WindTunnelBlocks.WIND_TUNNEL);
    public static final DeferredHolder<Item, WindTunnelControllerItem> WIND_TUNNEL_CONTROLLER_ITEM =
            registerWindTunnelControllerItem("wind_tunnel_controller", WindTunnelBlocks.WIND_TUNNEL_CONTROLLER);
    public static final DeferredHolder<Item, AirflowInjectorItem> AIRFLOW_INJECTOR_ITEM =
            registerAirflowInjectorItem("airflow_injector", WindTunnelBlocks.AIRFLOW_INJECTOR);
    public static final DeferredHolder<Item, WindTunnelMountItem> WIND_TUNNEL_MOUNT_ITEM =
            registerMountItem("wind_tunnel_mount", WindTunnelBlocks.WIND_TUNNEL_MOUNT);
    public static final DeferredHolder<Item, WindTunnelMountInterfaceItem> WIND_TUNNEL_MOUNT_INTERFACE_ITEM =
            registerMountInterfaceItem("wind_tunnel_mount_interface", WindTunnelBlocks.WIND_TUNNEL_MOUNT_INTERFACE);
    public static final DeferredHolder<Item, HologramProjectorItem> HOLOGRAM_PROJECTOR_ITEM =
            registerHologramProjectorItem("hologram_projector", WindTunnelBlocks.HOLOGRAM_PROJECTOR);

    public static final Map<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> SYMMETRIC_AIRFOIL_ITEMS =
            registerSymmetricAirfoilItems();
    public static final Map<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> VERTICAL_SYMMETRIC_AIRFOIL_ITEMS =
            registerVerticalSymmetricAirfoilItems();

    private WindTunnelItems() {
    }

    public static Iterable<DeferredHolder<Item, SymmetricAirfoilItem>> symmetricAirfoilItems() {
        return SYMMETRIC_AIRFOIL_ITEMS.values();
    }

    public static Iterable<DeferredHolder<Item, SymmetricAirfoilItem>> verticalSymmetricAirfoilItems() {
        return VERTICAL_SYMMETRIC_AIRFOIL_ITEMS.values();
    }

    private static <B extends Block> DeferredHolder<Item, WindTunnelItem> registerWindTunnelItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new WindTunnelItem(block.get(), new Item.Properties()));
    }

    private static <B extends Block> DeferredHolder<Item, WindTunnelControllerItem> registerWindTunnelControllerItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new WindTunnelControllerItem(block.get(), new Item.Properties()));
    }

    private static <B extends Block> DeferredHolder<Item, WindTunnelMountItem> registerMountItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new WindTunnelMountItem(block.get(), new Item.Properties()));
    }

    private static <B extends Block> DeferredHolder<Item, WindTunnelMountInterfaceItem> registerMountInterfaceItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new WindTunnelMountInterfaceItem(block.get(), new Item.Properties()));
    }

    private static <B extends Block> DeferredHolder<Item, AirflowInjectorItem> registerAirflowInjectorItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new AirflowInjectorItem(block.get(), new Item.Properties()));
    }

    private static <B extends Block> DeferredHolder<Item, HologramProjectorItem> registerHologramProjectorItem(
            String name, DeferredHolder<Block, B> block) {
        return ITEMS.register(name, () -> new HologramProjectorItem(block.get(), new Item.Properties()));
    }

    private static Map<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> registerSymmetricAirfoilItems() {
        EnumMap<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> map = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            map.put(color, ITEMS.register(color.getName() + "_symmetric_airfoil",
                    () -> new SymmetricAirfoilItem(
                            WindTunnelBlocks.SYMMETRIC_AIRFOILS.get(color).get(),
                            new Item.Properties())));
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> registerVerticalSymmetricAirfoilItems() {
        EnumMap<DyeColor, DeferredHolder<Item, SymmetricAirfoilItem>> map = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            map.put(color, ITEMS.register(color.getName() + "_vertical_symmetric_airfoil",
                    () -> new SymmetricAirfoilItem(
                            WindTunnelBlocks.VERTICAL_SYMMETRIC_AIRFOILS.get(color).get(),
                            new Item.Properties())));
        }
        return Collections.unmodifiableMap(map);
    }
}
