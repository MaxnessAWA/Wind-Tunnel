package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorBlockEntity;
import io.github.windtunnel.content.AirflowInjectorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-to-server payload for editing the aircraft-mounted airflow injector.
 */
@SuppressWarnings("null")
public record UpdateAirflowInjectorPayload(
        BlockPos pos,
        boolean enabled,
        String applicationMode,
        String referenceMode,
        String worldFlowDirection,
        double angleOfAttack,
        double sideslipAngle,
        double airspeed
) implements CustomPacketPayload {
    public static final Type<UpdateAirflowInjectorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "update_airflow_injector"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateAirflowInjectorPayload> STREAM_CODEC = StreamCodec.of(
            UpdateAirflowInjectorPayload::encode,
            UpdateAirflowInjectorPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static UpdateAirflowInjectorPayload decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateAirflowInjectorPayload(
                BlockPos.STREAM_CODEC.decode(buffer),
                buffer.readBoolean(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readUtf(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble()
        );
    }

    private static void encode(RegistryFriendlyByteBuf buffer, UpdateAirflowInjectorPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeBoolean(payload.enabled());
        buffer.writeUtf(payload.applicationMode());
        buffer.writeUtf(payload.referenceMode());
        buffer.writeUtf(payload.worldFlowDirection());
        buffer.writeDouble(payload.angleOfAttack());
        buffer.writeDouble(payload.sideslipAngle());
        buffer.writeDouble(payload.airspeed());
    }

    public static void handle(UpdateAirflowInjectorPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !canEdit(player, payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof AirflowInjectorBlockEntity injector)) {
            return;
        }

        injector.applySettings(
                payload.enabled(),
                AirflowInjectorBlockEntity.ApplicationMode.fromSerializedName(payload.applicationMode()),
                AirflowInjectorBlockEntity.ReferenceMode.fromSerializedName(payload.referenceMode()),
                Direction.byName(payload.worldFlowDirection()),
                payload.angleOfAttack(),
                payload.sideslipAngle(),
                payload.airspeed()
        );
    }

    private static boolean canEdit(ServerPlayer player, BlockPos pos) {
        return player.containerMenu instanceof AirflowInjectorMenu menu && menu.getInjectorPos().equals(pos);
    }
}
