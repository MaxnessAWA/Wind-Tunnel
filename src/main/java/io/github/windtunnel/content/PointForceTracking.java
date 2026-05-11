package io.github.windtunnel.content;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Reference-counted access to Sable's per-point force tracking flag.
 */
public final class PointForceTracking {
    private static final Map<ServerSubLevel, Set<Key>> ACTIVE_KEYS = new ConcurrentHashMap<>();

    private PointForceTracking() {
    }

    public static Key key(String owner, BlockPos pos) {
        return new Key(owner, pos.immutable());
    }

    public static void retain(ServerSubLevel subLevel, Key key) {
        ACTIVE_KEYS.computeIfAbsent(subLevel, unused -> ConcurrentHashMap.newKeySet()).add(key);
        subLevel.enableIndividualQueuedForcesTracking(true);
    }

    public static void release(ServerSubLevel subLevel, Key key) {
        Set<Key> keys = ACTIVE_KEYS.get(subLevel);
        if (keys == null) {
            subLevel.enableIndividualQueuedForcesTracking(false);
            return;
        }

        keys.remove(key);
        if (keys.isEmpty()) {
            ACTIVE_KEYS.remove(subLevel, keys);
            subLevel.enableIndividualQueuedForcesTracking(false);
        } else {
            subLevel.enableIndividualQueuedForcesTracking(true);
        }
    }

    public record Key(String owner, BlockPos pos) {
    }
}
