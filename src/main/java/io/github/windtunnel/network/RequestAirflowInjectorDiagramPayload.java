package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.AirflowInjectorBlockEntity;
import io.github.windtunnel.content.AirflowInjectorDiagramService;
import io.github.windtunnel.content.AirflowInjectorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client requests the latest aircraft-airflow diagram only while the injector screen is open.
 * This keeps the larger point-force payload out of the normal block-entity sync path.
 */
@SuppressWarnings("null")
public record RequestAirflowInjectorDiagramPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestAirflowInjectorDiagramPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "request_airflow_injector_diagram"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestAirflowInjectorDiagramPayload> STREAM_CODEC = StreamCodec.of(
            RequestAirflowInjectorDiagramPayload::encode,
            RequestAirflowInjectorDiagramPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static RequestAirflowInjectorDiagramPayload decode(RegistryFriendlyByteBuf buffer) {
        return new RequestAirflowInjectorDiagramPayload(BlockPos.STREAM_CODEC.decode(buffer));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RequestAirflowInjectorDiagramPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
    }

    public static void handle(RequestAirflowInjectorDiagramPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.containerMenu instanceof AirflowInjectorMenu menu) || !menu.getInjectorPos().equals(payload.pos())) {
                return;
            }
            if (!(serverPlayer.level().getBlockEntity(payload.pos()) instanceof AirflowInjectorBlockEntity injector)) {
                return;
            }
            AirflowInjectorDiagramService.requestCapture(serverPlayer, injector);
        });
    }
}
