package io.github.windtunnel;

import io.github.windtunnel.content.AirflowInjectorLdlib2Controls;
import io.github.windtunnel.content.AirflowInjectorRenderer;
import io.github.windtunnel.content.WindTunnelFanRenderer;
import io.github.windtunnel.content.WindTunnelControllerLdlib2Screen;

import io.github.windtunnel.content.WindTunnelMountInterfaceRenderer;
import io.github.windtunnel.content.WindTunnelMountLdlib2Controls;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import io.github.windtunnel.registry.WindTunnelBlocks;
import io.github.windtunnel.registry.WindTunnelMenus;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * Client-only registration hub.
 * <p>
 * Keeping screens, renderers and model registration here avoids loading client classes on a
 * dedicated server. The mod bus listeners in {@link WindTunnelMod} are guarded by
 * {@code FMLEnvironment.dist.isClient()} so none of these classes are ever touched on a server.
 * <p>
 * Registered resources:
 * <ul>
 * <li><b>Screens</b> — Controller GUI, mount stand GUI, and airflow injector GUI.</li>
 * <li><b>Block entity renderers</b> — Animated fan core, injector core, and mount interface probe.</li>
 * <li><b>Additional models</b> — Side-loaded moving-part models that are rendered directly by
 *     BERs and are not referenced in any blockstate JSON.</li>
 * <li><b>Render layers</b> — All transparent blocks are set to {@code cutoutMipped} so the
 *     alpha channel works correctly.</li>
 * </ul>
 */
@SuppressWarnings({"null", "deprecation"})
public final class ClientHooks {
    /** Side-loaded model for the animated airflow injector core. */
    public static final ModelResourceLocation AIRFLOW_INJECTOR_MOVING_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "block/airflow_injector_moving"));
    /** Side-loaded model for the animated wind tunnel fan blades. */
    public static final ModelResourceLocation WIND_TUNNEL_MOVING_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "block/wind_tunnel_moving"));
    /** Side-loaded model for the animated mount interface probe. */
    public static final ModelResourceLocation WIND_TUNNEL_MOUNT_INTERFACE_MOVING_MODEL =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "block/wind_tunnel_mount_interface_moving"));

    private ClientHooks() {
    }

    /** Registers client-side screens mapped to their menu types. */
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(WindTunnelMenus.AIRFLOW_INJECTOR.get(), AirflowInjectorLdlib2Controls::createScreen);
        event.register(WindTunnelMenus.WIND_TUNNEL_CONTROLLER.get(), WindTunnelControllerLdlib2Screen::createScreen);
        event.register(WindTunnelMenus.WIND_TUNNEL_MOUNT.get(), WindTunnelMountLdlib2Controls::createScreen);
    }

    /** Registers block entity renderers and sets render layers for transparent blocks. */
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The injector block renders its static shell through the normal block-model path; the
        // block entity renderer only draws the animated center core from a side-loaded model.
        event.registerBlockEntityRenderer(WindTunnelBlockEntities.AIRFLOW_INJECTOR.get(), AirflowInjectorRenderer::new);
        // The tunnel block reuses Create-style fan internals, so it needs a block entity renderer
        // to spin the fan blades independently from the static block model.
        event.registerBlockEntityRenderer(WindTunnelBlockEntities.WIND_TUNNEL.get(), WindTunnelFanRenderer::new);
        event.registerBlockEntityRenderer(WindTunnelBlockEntities.WIND_TUNNEL_MOUNT_INTERFACE.get(), WindTunnelMountInterfaceRenderer::new);
        // All blocks with transparency use cutoutMipped for correct alpha rendering
        ItemBlockRenderTypes.setRenderLayer(WindTunnelBlocks.WIND_TUNNEL_CONTROLLER.get(), RenderType.cutoutMipped());
        ItemBlockRenderTypes.setRenderLayer(WindTunnelBlocks.WIND_TUNNEL.get(), RenderType.cutoutMipped());
        ItemBlockRenderTypes.setRenderLayer(WindTunnelBlocks.AIRFLOW_INJECTOR.get(), RenderType.cutoutMipped());
        ItemBlockRenderTypes.setRenderLayer(WindTunnelBlocks.WIND_TUNNEL_MOUNT_INTERFACE.get(), RenderType.cutoutMipped());
    }

    /**
     * Registers additional baked models that are not referenced in any blockstate JSON.
     * These are the moving-part models rendered directly by block entity renderers.
     */
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        // The moving core model is rendered directly by the BER, so it must be loaded explicitly
        // instead of only through blockstates.
        event.register(AIRFLOW_INJECTOR_MOVING_MODEL);
        event.register(WIND_TUNNEL_MOVING_MODEL);
        event.register(WIND_TUNNEL_MOUNT_INTERFACE_MOVING_MODEL);
    }
}
