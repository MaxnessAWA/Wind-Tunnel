package io.github.windtunnel.network;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup.PointForce;
import io.github.windtunnel.WindTunnelMod;
import io.github.windtunnel.content.WindTunnelDiagramReceiver;
import io.github.windtunnel.content.WindTunnelLdlib2MenuScreen;
import io.github.windtunnel.content.WindTunnelMountDiagramData;
import io.github.windtunnel.network.DiagramPayloadHelper.ForceGroupPayload;
import io.github.windtunnel.network.DiagramPayloadHelper.ForcePointPayload;
import io.github.windtunnel.network.DiagramPayloadHelper.Vec3Payload;
import static io.github.windtunnel.network.DiagramPayloadHelper.mapForcePoints;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

/**
 * Server-to-client diagram snapshot for the aircraft airflow injector.
 * It uses the same point-force layout as the stand diagram so both screens can render the same
 * Simulated-style analysis view.
 */
@SuppressWarnings("null")
public record SyncAirflowInjectorDiagramPayload(
        BlockPos pos,
        boolean hasDiagram,
        List<ForceGroupPayload> groups,
        double mass,
        UUID referenceSubLevelId,
        List<UUID> renderedSubLevelIds,
        Vec3Payload centerOfMassLocal
) implements CustomPacketPayload {
    public static final Type<SyncAirflowInjectorDiagramPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "sync_airflow_injector_diagram"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAirflowInjectorDiagramPayload> STREAM_CODEC = StreamCodec.of(
            SyncAirflowInjectorDiagramPayload::encode,
            SyncAirflowInjectorDiagramPayload::decode
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static SyncAirflowInjectorDiagramPayload fromData(BlockPos pos, WindTunnelMountDiagramData data) {
        if (data == null || data.isEmpty()) {
            return new SyncAirflowInjectorDiagramPayload(
                    pos,
                    false,
                    List.of(),
                    0.0D,
                    null,
                    List.of(),
                    new Vec3Payload(new Vector3d())
            );
        }

        List<ForceGroupPayload> groups = new ArrayList<>(data.forces().size());
        for (Map.Entry<ForceGroup, List<PointForce>> entry : data.forces().entrySet()) {
            ResourceLocation groupId = ForceGroups.REGISTRY.getKey(entry.getKey());
            if (groupId == null) {
                continue;
            }
            groups.add(new ForceGroupPayload(groupId, mapForcePoints(entry.getValue())));
        }
        return new SyncAirflowInjectorDiagramPayload(
                pos,
                true,
                groups,
                data.mass(),
                data.referenceSubLevelId(),
                data.renderedSubLevelIds(),
                new Vec3Payload(data.centerOfMassLocal())
        );
    }

    public WindTunnelMountDiagramData toDiagramData() {
        if (!hasDiagram || referenceSubLevelId == null) {
            return WindTunnelMountDiagramData.EMPTY;
        }

        Object2ObjectOpenHashMap<ForceGroup, List<PointForce>> forces = new Object2ObjectOpenHashMap<>();
        for (ForceGroupPayload group : groups) {
            ForceGroup forceGroup = ForceGroups.REGISTRY.get(group.groupId());
            if (forceGroup == null) {
                continue;
            }
            forces.put(forceGroup, group.points().stream().map(ForcePointPayload::toPointForce).toList());
        }
        return new WindTunnelMountDiagramData(
                forces,
                mass,
                referenceSubLevelId,
                renderedSubLevelIds,
                centerOfMassLocal.toVector()
        );
    }

    private static SyncAirflowInjectorDiagramPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        boolean hasDiagram = buffer.readBoolean();
        int groupCount = buffer.readVarInt();
        List<ForceGroupPayload> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            groups.add(ForceGroupPayload.decode(buffer));
        }
        double mass = buffer.readDouble();
        UUID referenceSubLevelId = buffer.readBoolean() ? buffer.readUUID() : null;
        int renderedCount = buffer.readVarInt();
        List<UUID> renderedSubLevelIds = new ArrayList<>(renderedCount);
        for (int i = 0; i < renderedCount; i++) {
            renderedSubLevelIds.add(buffer.readUUID());
        }
        Vec3Payload centerOfMassLocal = Vec3Payload.decode(buffer);
        return new SyncAirflowInjectorDiagramPayload(pos, hasDiagram, groups, mass, referenceSubLevelId, renderedSubLevelIds, centerOfMassLocal);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SyncAirflowInjectorDiagramPayload payload) {
        BlockPos.STREAM_CODEC.encode(buffer, payload.pos());
        buffer.writeBoolean(payload.hasDiagram());
        buffer.writeVarInt(payload.groups().size());
        for (ForceGroupPayload group : payload.groups()) {
            group.encode(buffer);
        }
        buffer.writeDouble(payload.mass());
        buffer.writeBoolean(payload.referenceSubLevelId() != null);
        if (payload.referenceSubLevelId() != null) {
            buffer.writeUUID(payload.referenceSubLevelId());
        }
        buffer.writeVarInt(payload.renderedSubLevelIds().size());
        for (UUID id : payload.renderedSubLevelIds()) {
            buffer.writeUUID(id);
        }
        payload.centerOfMassLocal().encode(buffer);
    }

    public static void handle(SyncAirflowInjectorDiagramPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            WindTunnelDiagramReceiver receiver = resolveReceiver(minecraft);
            if (receiver == null) {
                return;
            }
            if (!receiver.windtunnel$getDiagramPos().equals(payload.pos())) {
                return;
            }

            receiver.windtunnel$updateDiagramData(payload.toDiagramData());
        });
    }

    private static WindTunnelDiagramReceiver resolveReceiver(Minecraft minecraft) {
        if (minecraft.screen instanceof WindTunnelDiagramReceiver receiver) {
            return receiver;
        }
        if (minecraft.screen instanceof WindTunnelLdlib2MenuScreen<?> screen) {
            return screen.getDiagramReceiver();
        }
        return null;
    }

    // Shared records and mapForcePoints are now in DiagramPayloadHelper.
}
