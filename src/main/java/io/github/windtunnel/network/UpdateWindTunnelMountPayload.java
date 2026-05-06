package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.WindTunnelMountBlockEntity;
import io.github.windtunnel.content.WindTunnelMountMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-to-server payload for editing mount pose parameters and binding state.
 */
@SuppressWarnings("null")
public record UpdateWindTunnelMountPayload(
        BlockPos pos,
        boolean locked,
        WindTunnelMountBlockEntity.ApplicationMode applicationMode,
        WindTunnelMountBlockEntity.ReferenceMode referenceMode,
        Direction flowDirection,
        boolean lockPitch,
        boolean lockRoll,
        boolean lockYaw,
        double angleOfAttack,
        double sideslipAngle,
        double offsetX,
        double offsetY,
        double offsetZ,
        boolean clearBinding
) implements CustomPacketPayload {
    public static final Type<UpdateWindTunnelMountPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "update_mount"));
    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateWindTunnelMountPayload> STREAM_CODEC = StreamCodec.of(
            UpdateWindTunnelMountPayload::encode,
            UpdateWindTunnelMountPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static UpdateWindTunnelMountPayload decode(RegistryFriendlyByteBuf buffer) {
        return new UpdateWindTunnelMountPayload(
                BlockPos.STREAM_CODEC.decode(buffer),
                buffer.readBoolean(),
                WindTunnelMountBlockEntity.ApplicationMode.fromSerializedName(buffer.readUtf()),
                WindTunnelMountBlockEntity.ReferenceMode.fromSerializedName(buffer.readUtf()),
                Direction.from3DDataValue(buffer.readVarInt()),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readDouble(),
                buffer.readBoolean()
        );
    }

    private static void encode(RegistryFriendlyByteBuf buffer, UpdateWindTunnelMountPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeBoolean(payload.locked());
        buffer.writeUtf(payload.applicationMode().serializedName());
        buffer.writeUtf(payload.referenceMode().serializedName());
        buffer.writeVarInt(payload.flowDirection().get3DDataValue());
        buffer.writeBoolean(payload.lockPitch());
        buffer.writeBoolean(payload.lockRoll());
        buffer.writeBoolean(payload.lockYaw());
        buffer.writeDouble(payload.angleOfAttack());
        buffer.writeDouble(payload.sideslipAngle());
        buffer.writeDouble(payload.offsetX());
        buffer.writeDouble(payload.offsetY());
        buffer.writeDouble(payload.offsetZ());
        buffer.writeBoolean(payload.clearBinding());
    }

    public static void handle(UpdateWindTunnelMountPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !canEdit(player, payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof WindTunnelMountBlockEntity mount)) {
            return;
        }

        // Binding clear is handled as a distinct intent so the server can tear down related mount
        // state immediately instead of routing through the normal pose-setting path.
        if (payload.clearBinding()) {
            mount.clearBinding();
            return;
        }

        // Geometry is resolved server-side during the next physics step, so the packet only carries
        // intent-level parameters.
        mount.applySettings(
                payload.locked(),
                payload.applicationMode(),
                payload.referenceMode(),
                payload.flowDirection(),
                payload.lockPitch(),
                payload.lockRoll(),
                payload.lockYaw(),
                payload.angleOfAttack(),
                payload.sideslipAngle(),
                payload.offsetX(),
                payload.offsetY(),
                payload.offsetZ()
        );
    }

    private static boolean canEdit(ServerPlayer player, BlockPos pos) {
        return player.containerMenu instanceof WindTunnelMountMenu menu && menu.getMountPos().equals(pos);
    }
}
