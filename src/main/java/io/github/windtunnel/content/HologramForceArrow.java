package io.github.windtunnel.content;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * World-space point force rendered by the hologram projector.
 */
public record HologramForceArrow(ResourceLocation groupId, Vector3d point, Vector3d force) {
    public HologramForceArrow {
        point = new Vector3d(point);
        force = new Vector3d(force);
    }

    public static HologramForceArrow of(ResourceLocation groupId, Vector3dc point, Vector3dc force) {
        return new HologramForceArrow(groupId, new Vector3d(point), new Vector3d(force));
    }
}
