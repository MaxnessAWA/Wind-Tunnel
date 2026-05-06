package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.WindTunnelControllerBlock;
import io.github.windtunnel.content.WindTunnelControllerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-to-client controller state snapshot for players tracking the chunk.
 * This keeps nearby clients in sync even if they are not the player who edited the controller.
 */
@SuppressWarnings("null")
public record SyncWindTunnelControllerPayload(BlockPos pos, int targetLength, int targetAirspeed, boolean spinFanBlades, boolean enabled) implements CustomPacketPayload {
    public static final Type<SyncWindTunnelControllerPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "sync_controller"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncWindTunnelControllerPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,
            SyncWindTunnelControllerPayload::pos,
            ByteBufCodecs.VAR_INT,
            SyncWindTunnelControllerPayload::targetLength,
            ByteBufCodecs.VAR_INT,
            SyncWindTunnelControllerPayload::targetAirspeed,
            ByteBufCodecs.BOOL,
            SyncWindTunnelControllerPayload::spinFanBlades,
            ByteBufCodecs.BOOL,
            SyncWindTunnelControllerPayload::enabled,
            SyncWindTunnelControllerPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncWindTunnelControllerPayload payload, IPayloadContext context) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        if (!(minecraft.level.getBlockEntity(payload.pos()) instanceof WindTunnelControllerBlockEntity controller)) {
            return;
        }

        controller.applySettings(payload.targetLength(), payload.targetAirspeed(), payload.spinFanBlades());
        var state = minecraft.level.getBlockState(payload.pos());
        if (state.getBlock() instanceof WindTunnelControllerBlock && state.getValue(WindTunnelControllerBlock.ENABLED) != payload.enabled()) {
            minecraft.level.setBlock(payload.pos(), state.setValue(WindTunnelControllerBlock.ENABLED, payload.enabled()), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }
}
