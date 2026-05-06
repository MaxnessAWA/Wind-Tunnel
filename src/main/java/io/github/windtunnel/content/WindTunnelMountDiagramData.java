package io.github.windtunnel.content;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup.PointForce;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Snapshot sent to the client-side mount analysis diagram.
 * Point forces are stored in the same shape used by Simulated's diagram system so the mount UI can
 * reuse that rendering model directly, while still carrying the extra metadata needed for
 * multi-body mount views.
 */
public record WindTunnelMountDiagramData(
        Map<ForceGroup, List<PointForce>> forces,
        double mass,
        UUID referenceSubLevelId,
        List<UUID> renderedSubLevelIds,
        Vector3d centerOfMassLocal
) {
    public static final WindTunnelMountDiagramData EMPTY = new WindTunnelMountDiagramData(
            Map.of(),
            0.0D,
            null,
            List.of(),
            new Vector3d()
    );

    public WindTunnelMountDiagramData {
        forces = copyForces(forces);
        renderedSubLevelIds = List.copyOf(renderedSubLevelIds);
        centerOfMassLocal = new Vector3d(centerOfMassLocal);
    }

    public boolean isEmpty() {
        return forces.isEmpty() || mass <= 0.0D || referenceSubLevelId == null || renderedSubLevelIds.isEmpty();
    }

    private static Map<ForceGroup, List<PointForce>> copyForces(Map<ForceGroup, List<PointForce>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Object2ObjectOpenHashMap<ForceGroup, List<PointForce>> copied = new Object2ObjectOpenHashMap<>();
        for (Map.Entry<ForceGroup, List<PointForce>> entry : source.entrySet()) {
            List<PointForce> original = entry.getValue();
            if (original == null || original.isEmpty()) {
                continue;
            }

            copied.put(entry.getKey(), original.stream()
                    .map(pointForce -> new PointForce(new Vector3d(pointForce.point()), new Vector3d(pointForce.force())))
                    .toList());
        }
        return copied;
    }

    public static PointForce copyPointForce(PointForce pointForce) {
        return new PointForce(new Vector3d(pointForce.point()), new Vector3d(pointForce.force()));
    }

    public static PointForce pointForce(Vector3dc point, Vector3dc force) {
        return new PointForce(new Vector3d(point), new Vector3d(force));
    }
}
