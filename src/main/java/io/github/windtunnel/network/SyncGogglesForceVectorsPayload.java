package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.client.GogglesForceVectorClient;
import io.github.windtunnel.content.HologramForceArrow;
import io.github.windtunnel.network.DiagramPayloadHelper.Vec3Payload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record SyncGogglesForceVectorsPayload(List<ForceArrowPayload> arrows) implements CustomPacketPayload {
    public static final Type<SyncGogglesForceVectorsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "sync_goggles_force_vectors"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncGogglesForceVectorsPayload> STREAM_CODEC = StreamCodec.of(
            SyncGogglesForceVectorsPayload::encode,
            SyncGogglesForceVectorsPayload::decode
    );

    public SyncGogglesForceVectorsPayload {
        arrows = List.copyOf(arrows);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static SyncGogglesForceVectorsPayload empty() {
        return new SyncGogglesForceVectorsPayload(List.of());
    }

    public static SyncGogglesForceVectorsPayload fromArrows(List<HologramForceArrow> arrows) {
        List<ForceArrowPayload> payloads = new ArrayList<>(arrows.size());
        for (HologramForceArrow arrow : arrows) {
            payloads.add(ForceArrowPayload.fromArrow(arrow));
        }
        return new SyncGogglesForceVectorsPayload(payloads);
    }

    private static SyncGogglesForceVectorsPayload decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<ForceArrowPayload> arrows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arrows.add(ForceArrowPayload.decode(buffer));
        }
        return new SyncGogglesForceVectorsPayload(arrows);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SyncGogglesForceVectorsPayload payload) {
        buffer.writeVarInt(payload.arrows().size());
        for (ForceArrowPayload arrow : payload.arrows()) {
            arrow.encode(buffer);
        }
    }

    public static void handle(SyncGogglesForceVectorsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> GogglesForceVectorClient.handlePayload(
                payload.arrows().stream().map(ForceArrowPayload::toArrow).toList()
        ));
    }

    public record ForceArrowPayload(ResourceLocation groupId, Vec3Payload point, Vec3Payload force) {
        private static ForceArrowPayload fromArrow(HologramForceArrow arrow) {
            return new ForceArrowPayload(arrow.groupId(), new Vec3Payload(arrow.point()), new Vec3Payload(arrow.force()));
        }

        private HologramForceArrow toArrow() {
            return new HologramForceArrow(groupId, point.toVector(), force.toVector());
        }

        private void encode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation.STREAM_CODEC.encode(buffer, groupId);
            point.encode(buffer);
            force.encode(buffer);
        }

        private static ForceArrowPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation groupId = ResourceLocation.STREAM_CODEC.decode(buffer);
            return new ForceArrowPayload(groupId, Vec3Payload.decode(buffer), Vec3Payload.decode(buffer));
        }
    }
}
