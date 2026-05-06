package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorBlockEntity;
import io.github.windtunnel.content.WindTunnelBlockEntity;
import io.github.windtunnel.content.WindTunnelControllerBlockEntity;
import io.github.windtunnel.content.WindTunnelMountBlockEntity;
import io.github.windtunnel.content.WindTunnelMountInterfaceBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block entity type registration for logic-bearing blocks.
 * <p>
 * Five block entity types are registered:
 * <ul>
 * <li><b>Wind Tunnel</b> — Stores airflow settings, exposes IAirCurrentSource for fan particles.</li>
 * <li><b>Controller</b> — Stores target length/airspeed/fan-spin settings for the cluster.</li>
 * <li><b>Airflow Injector</b> — Stores onboard injector configuration and tracks sublevel bindings.</li>
 * <li><b>Mount</b> — Stores binding info, pose parameters, and accumulated force measurements.</li>
 * <li><b>Mount Interface</b> — Lightweight render carrier for the animated probe.</li>
 * </ul>
 */
@SuppressWarnings("null")
public final class WindTunnelBlockEntities {
    /** Deferred register for block entity types, scoped to the mod ID. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, WindTunnelMod.MOD_ID);

    /** Wind tunnel segments — carries airflow settings and generates fan particles. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WindTunnelBlockEntity>> WIND_TUNNEL =
            BLOCK_ENTITY_TYPES.register("wind_tunnel",
                    () -> BlockEntityType.Builder.of(WindTunnelBlockEntity::new, WindTunnelBlocks.WIND_TUNNEL.get()).build(null));

    /** Controller block entity — stores per-controller target parameters. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WindTunnelControllerBlockEntity>> WIND_TUNNEL_CONTROLLER =
            BLOCK_ENTITY_TYPES.register("wind_tunnel_controller",
                    () -> BlockEntityType.Builder.of(WindTunnelControllerBlockEntity::new, WindTunnelBlocks.WIND_TUNNEL_CONTROLLER.get()).build(null));

    /** Aircraft-mounted airflow injector — configures relative airflow on a per-sublevel basis. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AirflowInjectorBlockEntity>> AIRFLOW_INJECTOR =
            BLOCK_ENTITY_TYPES.register("airflow_injector",
                    () -> BlockEntityType.Builder.of(AirflowInjectorBlockEntity::new, WindTunnelBlocks.AIRFLOW_INJECTOR.get()).build(null));

    /** Measurement stand — binds to aircraft and records aerodynamic forces. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WindTunnelMountBlockEntity>> WIND_TUNNEL_MOUNT =
            BLOCK_ENTITY_TYPES.register("wind_tunnel_mount",
                    () -> BlockEntityType.Builder.of(WindTunnelMountBlockEntity::new, WindTunnelBlocks.WIND_TUNNEL_MOUNT.get()).build(null));

    /** Mount interface anchor — stateless render carrier for the animated probe insert. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WindTunnelMountInterfaceBlockEntity>> WIND_TUNNEL_MOUNT_INTERFACE =
            BLOCK_ENTITY_TYPES.register("wind_tunnel_mount_interface",
                    () -> BlockEntityType.Builder.of(WindTunnelMountInterfaceBlockEntity::new, WindTunnelBlocks.WIND_TUNNEL_MOUNT_INTERFACE.get()).build(null));

    private WindTunnelBlockEntities() {
    }
}
