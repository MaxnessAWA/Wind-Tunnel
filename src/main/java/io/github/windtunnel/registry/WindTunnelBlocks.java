package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorBlock;
import io.github.windtunnel.content.HologramProjectorBlock;
import io.github.windtunnel.content.SymmetricAirfoilBlock;
import io.github.windtunnel.content.VerticalSymmetricAirfoilBlock;
import io.github.windtunnel.content.WindTunnelBlock;
import io.github.windtunnel.content.WindTunnelControllerBlock;
import io.github.windtunnel.content.WindTunnelMountBlock;
import io.github.windtunnel.content.WindTunnelMountInterfaceBlock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Centralized block and block-item registration.
 * <p>
 * All five blocks in the mod are registered here with their physical properties:
 * <ul>
 * <li><b>Wind Tunnel</b> — Copper-tier airflow generator, animated fan model.</li>
 * <li><b>Wind Tunnel Controller</b> — Orange control panel for network-wide tunnel settings.</li>
 * <li><b>Airflow Injector</b> — Steel-tier aircraft-mounted relative airflow source.</li>
 * <li><b>Wind Tunnel Mount</b> — Steel-tier measurement stand for aerodynamic testing.</li>
 * <li><b>Wind Tunnel Mount Interface</b> — Yellow anchor marker placed on the aircraft.</li>
 * </ul>
 */
@SuppressWarnings("null")
public final class WindTunnelBlocks {
    /** Deferred register for blocks, scoped to the mod ID. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(WindTunnelMod.MOD_ID);
    /** Deferred register for block items, scoped to the mod ID. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WindTunnelMod.MOD_ID);

    // ---- Wind Tunnel: the core airflow generator ----
    public static final DeferredHolder<Block, WindTunnelBlock> WIND_TUNNEL = BLOCKS.register("wind_tunnel",
            () -> new WindTunnelBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    // ---- Controller: user-facing configuration panel ----
    public static final DeferredHolder<Block, WindTunnelControllerBlock> WIND_TUNNEL_CONTROLLER = BLOCKS.register("wind_tunnel_controller",
            () -> new WindTunnelControllerBlock());

    // ---- Airflow Injector: aircraft-mounted relative wind source ----
    public static final DeferredHolder<Block, AirflowInjectorBlock> AIRFLOW_INJECTOR = BLOCKS.register("airflow_injector",
            () -> new AirflowInjectorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(4.0F, 8.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    // ---- Mount: aerodynamic measurement stand ----
    public static final DeferredHolder<Block, WindTunnelMountBlock> WIND_TUNNEL_MOUNT = BLOCKS.register("wind_tunnel_mount",
            () -> new WindTunnelMountBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(4.0F, 8.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    // ---- Mount Interface: yellow anchor marker placed on the aircraft ----
    public static final DeferredHolder<Block, WindTunnelMountInterfaceBlock> WIND_TUNNEL_MOUNT_INTERFACE = BLOCKS.register("wind_tunnel_mount_interface",
            () -> new WindTunnelMountInterfaceBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_YELLOW)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    // ---- Hologram Projector: world-space force visualization base ----
    public static final DeferredHolder<Block, HologramProjectorBlock> HOLOGRAM_PROJECTOR = BLOCKS.register("hologram_projector",
            () -> new HologramProjectorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

    // ---- Symmetric Airfoil: custom aerodynamic lifting surface ----
    public static final Map<DyeColor, DeferredHolder<Block, SymmetricAirfoilBlock>> SYMMETRIC_AIRFOILS =
            registerSymmetricAirfoils();
    public static final Map<DyeColor, DeferredHolder<Block, VerticalSymmetricAirfoilBlock>> VERTICAL_SYMMETRIC_AIRFOILS =
            registerVerticalSymmetricAirfoils();

    // ---- Block items: one per block ----
    public static final DeferredHolder<Item, BlockItem> WIND_TUNNEL_ITEM = ITEMS.register("wind_tunnel",
            () -> new BlockItem(WIND_TUNNEL.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> WIND_TUNNEL_CONTROLLER_ITEM = ITEMS.register("wind_tunnel_controller",
            () -> new BlockItem(WIND_TUNNEL_CONTROLLER.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> AIRFLOW_INJECTOR_ITEM = ITEMS.register("airflow_injector",
            () -> new BlockItem(AIRFLOW_INJECTOR.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> WIND_TUNNEL_MOUNT_ITEM = ITEMS.register("wind_tunnel_mount",
            () -> new BlockItem(WIND_TUNNEL_MOUNT.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> WIND_TUNNEL_MOUNT_INTERFACE_ITEM = ITEMS.register("wind_tunnel_mount_interface",
            () -> new BlockItem(WIND_TUNNEL_MOUNT_INTERFACE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> HOLOGRAM_PROJECTOR_ITEM = ITEMS.register("hologram_projector",
            () -> new BlockItem(HOLOGRAM_PROJECTOR.get(), new Item.Properties()));

    public static final Map<DyeColor, DeferredHolder<Item, BlockItem>> SYMMETRIC_AIRFOIL_ITEMS =
            registerSymmetricAirfoilItems();
    public static final Map<DyeColor, DeferredHolder<Item, BlockItem>> VERTICAL_SYMMETRIC_AIRFOIL_ITEMS =
            registerVerticalSymmetricAirfoilItems();

    private WindTunnelBlocks() {
    }

    public static Iterable<DeferredHolder<Item, BlockItem>> symmetricAirfoilItems() {
        return SYMMETRIC_AIRFOIL_ITEMS.values();
    }

    public static Iterable<DeferredHolder<Item, BlockItem>> verticalSymmetricAirfoilItems() {
        return VERTICAL_SYMMETRIC_AIRFOIL_ITEMS.values();
    }

    public static SymmetricAirfoilBlock symmetricAirfoil(DyeColor color) {
        return Objects.requireNonNull(SYMMETRIC_AIRFOILS.get(color)).get();
    }

    public static DyeColor symmetricAirfoilColor(Block block) {
        for (Map.Entry<DyeColor, DeferredHolder<Block, SymmetricAirfoilBlock>> entry : SYMMETRIC_AIRFOILS.entrySet()) {
            if (entry.getValue().get() == block) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static VerticalSymmetricAirfoilBlock verticalSymmetricAirfoil(DyeColor color) {
        return Objects.requireNonNull(VERTICAL_SYMMETRIC_AIRFOILS.get(color)).get();
    }

    public static DyeColor verticalSymmetricAirfoilColor(Block block) {
        for (Map.Entry<DyeColor, DeferredHolder<Block, VerticalSymmetricAirfoilBlock>> entry : VERTICAL_SYMMETRIC_AIRFOILS.entrySet()) {
            if (entry.getValue().get() == block) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static DyeColor airfoilColor(Block block) {
        DyeColor color = symmetricAirfoilColor(block);
        return color != null ? color : verticalSymmetricAirfoilColor(block);
    }

    public static Block recoloredAirfoil(Block block, DyeColor color) {
        if (symmetricAirfoilColor(block) != null) {
            return symmetricAirfoil(color);
        }
        if (verticalSymmetricAirfoilColor(block) != null) {
            return verticalSymmetricAirfoil(color);
        }
        return null;
    }

    private static Map<DyeColor, DeferredHolder<Block, SymmetricAirfoilBlock>> registerSymmetricAirfoils() {
        EnumMap<DyeColor, DeferredHolder<Block, SymmetricAirfoilBlock>> airfoils = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            airfoils.put(color, BLOCKS.register(color.getName() + "_symmetric_airfoil",
                    () -> new SymmetricAirfoilBlock(symmetricAirfoilProperties(color))));
        }
        return Collections.unmodifiableMap(airfoils);
    }

    private static Map<DyeColor, DeferredHolder<Block, VerticalSymmetricAirfoilBlock>> registerVerticalSymmetricAirfoils() {
        EnumMap<DyeColor, DeferredHolder<Block, VerticalSymmetricAirfoilBlock>> airfoils = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            airfoils.put(color, BLOCKS.register(color.getName() + "_vertical_symmetric_airfoil",
                    () -> new VerticalSymmetricAirfoilBlock(symmetricAirfoilProperties(color))));
        }
        return Collections.unmodifiableMap(airfoils);
    }

    private static Map<DyeColor, DeferredHolder<Item, BlockItem>> registerSymmetricAirfoilItems() {
        EnumMap<DyeColor, DeferredHolder<Item, BlockItem>> airfoilItems = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            DeferredHolder<Block, SymmetricAirfoilBlock> block = Objects.requireNonNull(SYMMETRIC_AIRFOILS.get(color));
            airfoilItems.put(color, ITEMS.register(color.getName() + "_symmetric_airfoil",
                    () -> new BlockItem(block.get(), new Item.Properties())));
        }
        return Collections.unmodifiableMap(airfoilItems);
    }

    private static Map<DyeColor, DeferredHolder<Item, BlockItem>> registerVerticalSymmetricAirfoilItems() {
        EnumMap<DyeColor, DeferredHolder<Item, BlockItem>> airfoilItems = new EnumMap<>(DyeColor.class);
        for (DyeColor color : DyeColor.values()) {
            DeferredHolder<Block, VerticalSymmetricAirfoilBlock> block = Objects.requireNonNull(VERTICAL_SYMMETRIC_AIRFOILS.get(color));
            airfoilItems.put(color, ITEMS.register(color.getName() + "_vertical_symmetric_airfoil",
                    () -> new BlockItem(block.get(), new Item.Properties())));
        }
        return Collections.unmodifiableMap(airfoilItems);
    }

    private static BlockBehaviour.Properties symmetricAirfoilProperties(DyeColor color) {
        return BlockBehaviour.Properties.of()
                .mapColor(Objects.requireNonNull(color.getMapColor()))
                .strength(2.5F, 5.0F)
                .sound(SoundType.METAL)
                .noOcclusion()
                .requiresCorrectToolForDrops();
    }
}
