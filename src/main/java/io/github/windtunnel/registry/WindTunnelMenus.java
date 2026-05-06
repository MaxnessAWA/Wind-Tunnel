package io.github.windtunnel.registry;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorMenu;
import io.github.windtunnel.content.WindTunnelControllerMenu;
import io.github.windtunnel.content.WindTunnelMountMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Menu type registration for server-backed GUIs.
 * <p>
 * Three menu types are registered — each carries only the target block position and exposes no
 * inventory slots. All actual state lives in the block entities and is synced via custom packets.
 */
@SuppressWarnings("null")
public final class WindTunnelMenus {
    /** Deferred register for menu types, scoped to the mod ID. */
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, WindTunnelMod.MOD_ID);

    /** Controller configuration GUI — length, airspeed, fan spin toggle. */
    public static final DeferredHolder<MenuType<?>, MenuType<WindTunnelControllerMenu>> WIND_TUNNEL_CONTROLLER =
            MENUS.register("wind_tunnel_controller", () -> IMenuTypeExtension.create(WindTunnelControllerMenu::new));

    /** Airflow injector configuration GUI — relative wind parameters and force diagram. */
    public static final DeferredHolder<MenuType<?>, MenuType<AirflowInjectorMenu>> AIRFLOW_INJECTOR =
            MENUS.register("airflow_injector", () -> IMenuTypeExtension.create(AirflowInjectorMenu::new));

    /** Mount stand GUI — binding, pose control, lock toggles, and force measurement readout. */
    public static final DeferredHolder<MenuType<?>, MenuType<WindTunnelMountMenu>> WIND_TUNNEL_MOUNT =
            MENUS.register("wind_tunnel_mount", () -> IMenuTypeExtension.create(WindTunnelMountMenu::new));

    private WindTunnelMenus() {
    }
}
