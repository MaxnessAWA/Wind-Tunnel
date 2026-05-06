package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.WindTunnelMountBlockEntity;
import io.github.windtunnel.content.WindTunnelMountMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client requests the latest diagram snapshot only while the mount analysis screen is open.
 * This keeps the heavier point-force payload off the regular block entity sync path.
 */
@SuppressWarnings("null")
public record RequestWindTunnelMountDiagramPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RequestWindTunnelMountDiagramPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "request_mount_diagram"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestWindTunnelMountDiagramPayload> STREAM_CODEC = StreamCodec.of(
            RequestWindTunnelMountDiagramPayload::encode,
            RequestWindTunnelMountDiagramPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static RequestWindTunnelMountDiagramPayload decode(RegistryFriendlyByteBuf buffer) {
        return new RequestWindTunnelMountDiagramPayload(BlockPos.STREAM_CODEC.decode(buffer));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RequestWindTunnelMountDiagramPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
    }

    public static void handle(RequestWindTunnelMountDiagramPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer serverPlayer)) {
                return;
            }
            if (!(serverPlayer.containerMenu instanceof WindTunnelMountMenu menu) || !menu.getMountPos().equals(payload.pos())) {
                return;
            }
            if (!(serverPlayer.level().getBlockEntity(payload.pos()) instanceof WindTunnelMountBlockEntity mount)) {
                return;
            }
            mount.requestDiagramCapture();
        });
    }
}
