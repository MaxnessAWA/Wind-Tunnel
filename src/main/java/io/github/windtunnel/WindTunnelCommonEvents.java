package io.github.windtunnel;

import io.github.windtunnel.content.AirflowInjectorShutdownState;
import io.github.windtunnel.content.WindTunnelMountSelection;
import io.github.windtunnel.registry.WindTunnelBlocks;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerLevel;

/**
 * Common-side gameplay events that are easier to express as global interaction hooks than as
 * per-item or per-block overrides.
 * <p>
 * Responsibilities:
 * <ul>
 * <li><b>Entity selection</b> — When a player right-clicks an entity while holding the mount
 *     interface item, the selection is stored in persistent player data for the two-step
 *     mount binding workflow.</li>
 * <li><b>Server shutdown</b> — Bumps the per-dimension shutdown generation counter so airflow
 *     injectors in unloaded chunks can detect that the server restarted and force-disable
 *     themselves on next load.</li>
 * </ul>
 */
public final class WindTunnelCommonEvents {
    private WindTunnelCommonEvents() {
    }

    /**
     * Handles {@code PlayerInteractEvent.EntityInteract} — the broader entity interaction event.
     * <p>
     * If the player is holding the mount interface item and targets a non-player entity,
     * the interaction is cancelled and the entity selection is stored for later binding.
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!shouldCaptureEntitySelection(event.getEntity(), event.getItemStack(), event.getTarget())) {
            return;
        }

        // Cancel the interaction so it doesn't trigger default entity behavior
        event.setCancellationResult(Objects.requireNonNull(InteractionResult.sidedSuccess(event.getLevel().isClientSide)));
        event.setCanceled(true);
        if (event.getLevel().isClientSide) {
            return;
        }

        storeEntitySelection(event.getEntity(), event.getTarget());
    }

    /**
     * Handles {@code PlayerInteractEvent.EntityInteractSpecific} — the more specific interaction
     * (e.g., right-click on a specific entity part). Uses the same logic as the broader event.
     */
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!shouldCaptureEntitySelection(event.getEntity(), event.getItemStack(), event.getTarget())) {
            return;
        }

        event.setCancellationResult(Objects.requireNonNull(InteractionResult.sidedSuccess(event.getLevel().isClientSide)));
        event.setCanceled(true);
        if (event.getLevel().isClientSide) {
            return;
        }

        storeEntitySelection(event.getEntity(), event.getTarget());
    }

    /**
     * Called when the server is stopping. Bumps the per-dimension shutdown generation counter
     * so airflow injectors can detect that the server restarted and force themselves off.
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Bump one persistent "server stop generation" per dimension. Airflow injectors store
            // the last generation they acknowledged, so even chunks that are unloaded right now
            // will force themselves off next time they load.
            AirflowInjectorShutdownState.get(level).bumpGeneration();
        }
    }

    /**
     * Determines whether the current interaction should be treated as an entity selection.
     * <p>
     * Conditions:
     * <ul>
     * <li>Player must be holding the mount interface item.</li>
     * <li>Target must NOT be a player (binding players would fight the normal movement/input loop).</li>
     * </ul>
     */
    private static boolean shouldCaptureEntitySelection(Player player, ItemStack heldStack, Entity target) {
        if (!heldStack.is(Objects.requireNonNull(WindTunnelBlocks.WIND_TUNNEL_MOUNT_INTERFACE_ITEM.get()))) {
            return false;
        }

        // The mount service can safely pin ordinary entities, but binding players would fight the
        // normal movement/input loop and create confusing side effects.
        return !(target instanceof Player);
    }

    /**
     * Persists the selected entity in the player's persistent NBT data.
     * The facing direction is resolved from the entity's current orientation.
     */
    private static void storeEntitySelection(Player player, Entity target) {
        Direction facing = resolveEntityFacing(target);
        WindTunnelMountSelection.storeEntity(player, player.level().dimension(), target.getUUID(), facing);
        player.displayClientMessage(Objects.requireNonNull(Component.translatable("block." + WindTunnelMod.MOD_ID + ".wind_tunnel_mount_interface.entity_selected")), true);
    }

    /**
     * Resolves an entity's cardinal facing direction from its yaw, defaulting to NORTH.
     */
    private static Direction resolveEntityFacing(Entity entity) {
        Direction direction = entity.getDirection();
        return direction == null ? Direction.NORTH : direction;
    }
}
