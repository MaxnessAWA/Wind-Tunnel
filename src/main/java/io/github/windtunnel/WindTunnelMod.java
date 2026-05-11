package io.github.windtunnel;

import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import io.github.windtunnel.compat.CreateAirfoilAttachment;
import io.github.windtunnel.client.HologramProjectorWorldRenderer;
import io.github.windtunnel.content.AirflowInjectorDiagramService;
import io.github.windtunnel.client.GogglesForceVectorClient;
import io.github.windtunnel.content.GogglesForceVectorService;
import io.github.windtunnel.content.HologramProjectorService;
import io.github.windtunnel.config.WindTunnelConfig;
import io.github.windtunnel.content.WindTunnelMountService;
import io.github.windtunnel.content.WindTunnelWindProvider;
import io.github.windtunnel.data.WindTunnelDataGenerators;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import io.github.windtunnel.registry.WindTunnelBlocks;
import io.github.windtunnel.registry.WindTunnelCreativeTabs;
import io.github.windtunnel.registry.WindTunnelMenus;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.Objects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Main mod entrypoint for the Wind Tunnel mod.
 * <p>
 * Architectural overview:
 * <ul>
 * <li><b>Content registration</b> — Blocks, items, block entities, creative tabs, and menus are
 *     registered on the mod event bus before any other hooks fire.</li>
 * <li><b>Client-side hooks</b> — Screens, renderers, and additional models are only registered
 *     when running on the physical client (dedicated servers skip these entirely).</li>
 * <li><b>Sable integration</b> — Wind tunnels register themselves as ambient air velocity
 *     providers via {@code SubLevelHelper.registerWindProvider}, so Aeronautics treats the
 *     tunnel airflow as a local wind field rather than a direct push force. The mount service
 *     hooks into the Sable physics tick (pre/post) to pin aircraft poses and read accumulated
 *     lift/drag forces.</li>
 * <li><b>Network payloads</b> — Custom packets are registered for controller edits, mount pose
 *     updates, injector configuration, and diagram data sync.</li>
 * </ul>
 * <p>
 * All bootstrap work is centralized here so the rest of the code can focus on gameplay logic.
 */
@Mod(WindTunnelMod.MOD_ID)
public final class WindTunnelMod {
    /** Mod ID used for resource locations, network channels, and registration. */
    public static final String MOD_ID = "windtunnel";

    /** Guards against double-registration in dev environments where the mod may be reloaded. */
    private static boolean windProviderRegistered;
    /** Guards against double-registration of physics tick hooks. */
    private static boolean mountHooksRegistered;

    public WindTunnelMod(IEventBus modBus, ModContainer modContainer) {
        IEventBus bus = Objects.requireNonNull(modBus);
        // ---- Phase 1: Register all content so later hooks can safely reference these types ----
        WindTunnelBlocks.BLOCKS.register(bus);
        WindTunnelBlocks.ITEMS.register(bus);
        WindTunnelBlockEntities.BLOCK_ENTITY_TYPES.register(bus);
        WindTunnelCreativeTabs.CREATIVE_MODE_TABS.register(bus);
        WindTunnelMenus.MENUS.register(bus);

        // Network payload registration
        modBus.addListener(NetworkHooks::registerPayloads);
        modBus.addListener(WindTunnelDataGenerators::gatherData);

        // Client-only registrations are guarded by FMLEnvironment to avoid classloading errors
        // on dedicated servers.
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(ClientHooks::registerScreens);
            modBus.addListener(ClientHooks::registerRenderers);
            modBus.addListener(ClientHooks::registerAdditionalModels);
            modBus.addListener(ClientHooks::registerShaders);
            NeoForge.EVENT_BUS.addListener(GogglesForceVectorClient::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(HologramProjectorWorldRenderer::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(HologramProjectorWorldRenderer::onLoggingOut);
        }

        // ---- Phase 2: Global gameplay event handlers ----
        // Entity interaction events are used for the two-step mount binding workflow
        NeoForge.EVENT_BUS.addListener(WindTunnelCommonEvents::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(WindTunnelCommonEvents::onEntityInteractSpecific);
        // Server shutdown gracefully disables all airflow injectors
        NeoForge.EVENT_BUS.addListener(WindTunnelCommonEvents::onServerStopping);
        CreateAirfoilAttachment.register();

        // ---- Phase 3: Sable physics engine integration ----
        if (!windProviderRegistered) {
            // Feed wind tunnels into Sable as local air velocity, not as direct force.
            // That lets Aeronautics keep using its own lift/drag pipeline with the tunnel air
            // treated as an incoming flow field.
            SubLevelHelper.registerWindProvider(WindTunnelWindProvider::getWindVelocityAt);
            windProviderRegistered = true;
        }
        if (!mountHooksRegistered) {
            // Mount service: pin pose before each physics step, read accumulated forces after.
            SableEventPlatform.INSTANCE.onPhysicsTick(WindTunnelMountService::prePhysicsTick);
            SableEventPlatform.INSTANCE.onPostPhysicsTick(WindTunnelMountService::postPhysicsTick);
            // Injector diagram service: sampled on demand, but needs the same pre/post windows.
            SableEventPlatform.INSTANCE.onPhysicsTick(AirflowInjectorDiagramService::prePhysicsTick);
            SableEventPlatform.INSTANCE.onPostPhysicsTick(AirflowInjectorDiagramService::postPhysicsTick);
            // Hologram projectors continuously sample nearby point forces for world-space arrows.
            SableEventPlatform.INSTANCE.onPhysicsTick(HologramProjectorService::prePhysicsTick);
            SableEventPlatform.INSTANCE.onPostPhysicsTick(HologramProjectorService::postPhysicsTick);
            // Create goggles can show nearby physics force vectors directly in the world.
            SableEventPlatform.INSTANCE.onPhysicsTick(GogglesForceVectorService::prePhysicsTick);
            SableEventPlatform.INSTANCE.onPostPhysicsTick(GogglesForceVectorService::postPhysicsTick);
            mountHooksRegistered = true;
        }

        // ---- Phase 4: Server configuration ----
        modContainer.registerConfig(ModConfig.Type.SERVER, WindTunnelConfig.SPEC);
    }
}
