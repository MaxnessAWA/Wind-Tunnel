package io.github.windtunnel.client;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Client-side tracker for loaded hologram projectors that have received a synced snapshot.
 */
public final class HologramProjectorWorldState {
    private static final Set<BlockPos> KNOWN_PROJECTORS = new LinkedHashSet<>();

    private HologramProjectorWorldState() {
    }

    public static void markKnown(BlockPos pos) {
        KNOWN_PROJECTORS.add(pos.immutable());
    }

    public static void remove(BlockPos pos) {
        KNOWN_PROJECTORS.remove(pos);
    }

    public static void clear() {
        KNOWN_PROJECTORS.clear();
    }

    public static Set<BlockPos> getKnownProjectors() {
        return Set.copyOf(KNOWN_PROJECTORS);
    }
}
