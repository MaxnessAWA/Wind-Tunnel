package io.github.windtunnel.network;

import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup.PointForce;
import io.github.windtunnel.content.WindTunnelMountDiagramData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared serialization records and helpers for diagram data payloads.
 * <p>
 * Both {@link SyncWindTunnelMountDiagramPayload} and {@link SyncAirflowInjectorDiagramPayload}
 * use these records to serialize point-force diagrams. Extracted here to avoid duplication.
 */
@SuppressWarnings("null")
public final class DiagramPayloadHelper {

    private DiagramPayloadHelper() {
    }

    /* ---- Shared payload records ---- */

    public record Vec3Payload(double x, double y, double z) {
        /** Convenience constructor from a JOML vector. */
        public Vec3Payload(org.joml.Vector3dc vector) {
            this(vector.x(), vector.y(), vector.z());
        }

        public Vector3d toVector() {
            return new Vector3d(x, y, z);
        }

        public void encode(RegistryFriendlyByteBuf buffer) {
            buffer.writeDouble(x);
            buffer.writeDouble(y);
            buffer.writeDouble(z);
        }

        public static Vec3Payload fromVector(Vector3d vector) {
            return new Vec3Payload(vector.x, vector.y, vector.z);
        }

        public static Vec3Payload decode(RegistryFriendlyByteBuf buffer) {
            return new Vec3Payload(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        }

        public static void encode(RegistryFriendlyByteBuf buffer, Vec3Payload payload) {
            buffer.writeDouble(payload.x);
            buffer.writeDouble(payload.y);
            buffer.writeDouble(payload.z);
        }
    }

    public record ForcePointPayload(Vec3Payload point, Vec3Payload force) {
        public PointForce toPointForce() {
            return WindTunnelMountDiagramData.pointForce(point.toVector(), force.toVector());
        }

        public void encode(RegistryFriendlyByteBuf buffer) {
            Vec3Payload.encode(buffer, point);
            Vec3Payload.encode(buffer, force);
        }

        public static ForcePointPayload decode(RegistryFriendlyByteBuf buffer) {
            return new ForcePointPayload(Vec3Payload.decode(buffer), Vec3Payload.decode(buffer));
        }

        public static void encode(RegistryFriendlyByteBuf buffer, ForcePointPayload payload) {
            Vec3Payload.encode(buffer, payload.point);
            Vec3Payload.encode(buffer, payload.force);
        }
    }

    public record ForceGroupPayload(ResourceLocation groupId, List<ForcePointPayload> points) {
        public void encode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation.STREAM_CODEC.encode(buffer, groupId);
            buffer.writeVarInt(points.size());
            for (ForcePointPayload point : points) {
                ForcePointPayload.encode(buffer, point);
            }
        }

        public static ForceGroupPayload decode(RegistryFriendlyByteBuf buffer) {
            ResourceLocation groupId = ResourceLocation.STREAM_CODEC.decode(buffer);
            int count = buffer.readVarInt();
            List<ForcePointPayload> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                points.add(ForcePointPayload.decode(buffer));
            }
            return new ForceGroupPayload(groupId, points);
        }

        public static void encode(RegistryFriendlyByteBuf buffer, ForceGroupPayload payload) {
            ResourceLocation.STREAM_CODEC.encode(buffer, payload.groupId);
            buffer.writeVarInt(payload.points.size());
            for (ForcePointPayload point : payload.points) {
                ForcePointPayload.encode(buffer, point);
            }
        }
    }

    /* ---- Convenience mapping helpers ---- */

    public static List<ForcePointPayload> mapForcePoints(List<PointForce> pointForces) {
        List<ForcePointPayload> mapped = new ArrayList<>(pointForces.size());
        for (PointForce pointForce : pointForces) {
            mapped.add(new ForcePointPayload(
                    Vec3Payload.fromVector(new Vector3d(pointForce.point())),
                    Vec3Payload.fromVector(new Vector3d(pointForce.force()))));
        }
        return mapped;
    }
}
