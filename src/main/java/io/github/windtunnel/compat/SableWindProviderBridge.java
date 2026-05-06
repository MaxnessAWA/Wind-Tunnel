package io.github.windtunnel.compat;

import dev.ryanhcode.sable.api.SubLevelHelper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import java.lang.reflect.Field;
import java.util.function.BiFunction;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;

/**
 * Reads Sable's registered wind-provider list via reflection, without introducing another
 * runtime-visible mixin class into transformed aerodynamic hot-path code.
 * <p>
 * Sable stores wind providers in a package-private {@code ObjectList<BiFunction>} inside
 * {@code SubLevelHelper}. This bridge cracks that field open at mod init time and provides
 * a static accessor that the Mixin can call during the aerodynamic hot path.
 * <p>
 * If the field cannot be resolved (e.g., Sable version mismatch), an empty list is returned
 * and the wind tunnel functionality gracefully degrades.
 */
public final class SableWindProviderBridge {
    /** Shared empty list to avoid allocations when the field is unavailable. */
    private static final ObjectList<BiFunction<Vector3dc, Level, Vector3dc>> EMPTY = new ObjectArrayList<>(0);
    /** Cached reflective field reference, resolved once at class load time. */
    private static final Field WIND_PROVIDERS_FIELD = resolveWindProvidersField();

    private SableWindProviderBridge() {
    }

    /**
     * Returns Sable's registered wind providers, or an empty list if the field is unavailable.
     * The returned list is the live Sable list — mutations by other mods are visible immediately.
     */
    @SuppressWarnings("unchecked")
    public static ObjectList<BiFunction<Vector3dc, Level, Vector3dc>> getWindProviders() {
        if (WIND_PROVIDERS_FIELD == null) {
            return EMPTY;
        }

        try {
            Object value = WIND_PROVIDERS_FIELD.get(null);
            if (value instanceof ObjectList<?> list) {
                return (ObjectList<BiFunction<Vector3dc, Level, Vector3dc>>) list;
            }
        } catch (IllegalAccessException ignored) {
        }

        return EMPTY;
    }

    /**
     * Attempts to resolve the {@code windProviders} field in {@code SubLevelHelper}.
     * The field is made accessible and cached for the lifetime of the JVM.
     */
    private static Field resolveWindProvidersField() {
        try {
            Field field = SubLevelHelper.class.getDeclaredField("windProviders");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
