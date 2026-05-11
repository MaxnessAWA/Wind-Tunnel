package io.github.windtunnel.network;

import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.client.HologramProjectorWorldState;
import io.github.windtunnel.content.HologramForceArrow;
import io.github.windtunnel.content.HologramProjectorBlockEntity;
import io.github.windtunnel.network.DiagramPayloadHelper.Vec3Payload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@SuppressWarnings("null")
public record SyncHologramProjectorPayload(BlockPos pos, List<ForceArrowPayload> arrows, List<UUID> renderedSubLevelIds) implements CustomPacketPayload {
    public static final Type<SyncHologramProjectorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "sync_hologram_projector"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHologramProjectorPayload> STREAM_CODEC = StreamCodec.of(
            SyncHologramProjectorPayload::encode,
            SyncHologramProjectorPayload::decode
    );

    public SyncHologramProjectorPayload {
        arrows = List.copyOf(arrows);
        renderedSubLevelIds = List.copyOf(renderedSubLevelIds);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static SyncHologramProjectorPayload fromArrows(BlockPos pos, List<HologramForceArrow> arrows) {
        return fromSnapshot(pos, arrows, List.of());
    }

    public static SyncHologramProjectorPayload fromSnapshot(BlockPos pos, List<HologramForceArrow> arrows, List<UUID> renderedSubLevelIds) {
        List<ForceArrowPayload> payloads = new ArrayList<>(arrows.size());
        for (HologramForceArrow arrow : arrows) {
            payloads.add(ForceArrowPayload.fromArrow(arrow));
        }
        return new SyncHologramProjectorPayload(pos, payloads, renderedSubLevelIds);
    }

    private static SyncHologramProjectorPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        int count = buffer.readVarInt();
        List<ForceArrowPayload> arrows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arrows.add(ForceArrowPayload.decode(buffer));
        }
        int renderedCount = buffer.readVarInt();
        List<UUID> renderedSubLevelIds = new ArrayList<>(renderedCount);
        for (int i = 0; i < renderedCount; i++) {
            renderedSubLevelIds.add(buffer.readUUID());
        }
        return new SyncHologramProjectorPayload(pos, arrows, renderedSubLevelIds);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SyncHologramProjectorPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeVarInt(payload.arrows().size());
        for (ForceArrowPayload arrow : payload.arrows()) {
            arrow.encode(buffer);
        }
        buffer.writeVarInt(payload.renderedSubLevelIds().size());
        for (UUID renderedSubLevelId : payload.renderedSubLevelIds()) {
            buffer.writeUUID(renderedSubLevelId);
        }
    }

    public static void handle(SyncHologramProjectorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }
            if (!(minecraft.level.getBlockEntity(payload.pos()) instanceof HologramProjectorBlockEntity projector)) {
                return;
            }
            projector.updateHologram(
                    payload.arrows().stream().map(ForceArrowPayload::toArrow).toList(),
                    payload.renderedSubLevelIds(),
                    minecraft.level.getGameTime()
            );
            HologramProjectorWorldState.markKnown(payload.pos());
        });
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
