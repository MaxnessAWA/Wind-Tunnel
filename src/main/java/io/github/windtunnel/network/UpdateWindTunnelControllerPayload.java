package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.WindTunnelControllerBlock;
import io.github.windtunnel.content.WindTunnelControllerBlockEntity;
import io.github.windtunnel.content.WindTunnelControllerMenu;
import io.github.windtunnel.content.WindTunnelNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-to-server controller edit packet.
 * The screen sends this after user interaction; the server clamps values and applies them to the
 * controller block entity.
 */
@SuppressWarnings("null")
public record UpdateWindTunnelControllerPayload(BlockPos pos, int targetLength, double targetAirspeed, boolean spinFanBlades, boolean enabled) implements CustomPacketPayload {
    public static final Type<UpdateWindTunnelControllerPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "update_controller"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateWindTunnelControllerPayload> STREAM_CODEC = StreamCodec.of(
            UpdateWindTunnelControllerPayload::encode,
            UpdateWindTunnelControllerPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static UpdateWindTunnelControllerPayload decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateWindTunnelControllerPayload(
                BlockPos.STREAM_CODEC.decode(buffer),
                buffer.readVarInt(),
                buffer.readDouble(),
                buffer.readBoolean(),
                buffer.readBoolean()
        );
    }

    private static void encode(RegistryFriendlyByteBuf buffer, UpdateWindTunnelControllerPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeVarInt(payload.targetLength());
        buffer.writeDouble(payload.targetAirspeed());
        buffer.writeBoolean(payload.spinFanBlades());
        buffer.writeBoolean(payload.enabled());
    }

    public static void handle(UpdateWindTunnelControllerPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !canEdit(player, payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof WindTunnelControllerBlockEntity controller)) {
            return;
        }

        if (!(player.level().getBlockState(payload.pos()).getBlock() instanceof WindTunnelControllerBlock)) {
            return;
        }

        // The server is authoritative for both numeric settings and the enabled state.
        boolean settingsChanged = controller.applySettingsSilently(payload.targetLength(), payload.targetAirspeed(), payload.spinFanBlades());
        var level = player.level();
        var state = level.getBlockState(payload.pos());
        boolean enabledChanged = false;
        if (state.getValue(WindTunnelControllerBlock.ENABLED) != payload.enabled()) {
            level.setBlock(payload.pos(), state.setValue(WindTunnelControllerBlock.ENABLED, payload.enabled()), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            enabledChanged = true;
        }

        if (settingsChanged || enabledChanged) {
            // Batch numeric edits and enabled toggles into one cluster refresh.
            WindTunnelNetwork.refreshFromController(level, payload.pos());
        }
    }

    private static boolean canEdit(ServerPlayer player, BlockPos pos) {
        return player.containerMenu instanceof WindTunnelControllerMenu menu && menu.getControllerPos().equals(pos);
    }
}
