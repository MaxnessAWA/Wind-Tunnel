package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorBlock;
import io.github.windtunnel.content.SymmetricAirfoilBlock;
import io.github.windtunnel.content.WindTunnelBlock;
import io.github.windtunnel.content.WindTunnelControllerBlock;
import io.github.windtunnel.content.WindTunnelMountBlock;
import io.github.windtunnel.content.WindTunnelMountInterfaceBlock;
import net.minecraft.world.item.BlockItem;
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

    // ---- Symmetric Airfoil: custom aerodynamic lifting surface ----
    public static final DeferredHolder<Block, SymmetricAirfoilBlock> SYMMETRIC_AIRFOIL = BLOCKS.register("symmetric_airfoil",
            () -> new SymmetricAirfoilBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(2.5F, 5.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .requiresCorrectToolForDrops()));

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

    public static final DeferredHolder<Item, BlockItem> SYMMETRIC_AIRFOIL_ITEM = ITEMS.register("symmetric_airfoil",
            () -> new BlockItem(SYMMETRIC_AIRFOIL.get(), new Item.Properties()));

    private WindTunnelBlocks() {
    }
}
