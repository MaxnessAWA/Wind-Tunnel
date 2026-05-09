package io.github.windtunnel.compat;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.windtunnel.content.WindTunnelWindProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Reflection bridge for optional Synaxis aerodynamics integration.
 * <p>
 * Synaxis is intentionally not a compile-time or metadata dependency of Wind Tunnel. The mixin
 * passes Synaxis-owned objects as {@link Object}s, and this helper reflects only the small surface
 * needed to turn Synaxis flap velocity into velocity relative to Wind Tunnel's air field.
 */
public final class SynaxisWindCompat {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ClassValue<Optional<Method>> FLAP_CENTER_LOCAL_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findPublicMethod(type, "flapCenterLocal");
        }
    };
    private static final ClassValue<Optional<Method>> SELF_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findPublicMethod(type, "self");
        }
    };
    private static final ClassValue<Optional<Method>> MODEL_TO_WORLD_POSITION_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findPublicMethod(type, "modelToWorldPosition", Vector3dc.class, Vector3d.class);
        }
    };
    private static final ClassValue<Optional<Method>> POSITION_WORLD_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findPublicMethod(type, "positionWorld");
        }
    };
    private static final ClassValue<Optional<Method>> FORCES_METHODS = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            return findPublicMethod(type, "forces");
        }
    };
    private static final ClassValue<Optional<Field>> SELF_SUB_LEVEL_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            return findField(type, "selfSubLevel");
        }
    };
    private static final ClassValue<Optional<Field>> SUB_LEVEL_FIELDS = new ClassValue<>() {
        @Override
        protected Optional<Field> computeValue(Class<?> type) {
            return findField(type, "subLevel");
        }
    };
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private static boolean warnedReflectionFailure;

    private SynaxisWindCompat() {
    }

    public static void applyWindToFlapVelocity(Object context, Object config, Vector3d velocityAtFlap) {
        if (context == null || config == null || velocityAtFlap == null) {
            return;
        }

        try {
            Object body = getBody(context);
            if (body == null) {
                return;
            }

            ServerSubLevel subLevel = getSubLevel(context);
            if (subLevel == null) {
                return;
            }

            Scratch scratch = SCRATCH.get();
            if (!getFlapWorldPosition(config, body, scratch.sampleWorld)) {
                return;
            }

            Level level = subLevel.getLevel();
            Vector3dc windVelocity = WindTunnelWindProvider.getWindVelocityAt(scratch.sampleWorld, level, subLevel);
            if (windVelocity != null) {
                velocityAtFlap.sub(windVelocity);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warnReflectionFailure(exception);
        }
    }

    @Nullable
    private static Object getBody(Object context) throws ReflectiveOperationException {
        Optional<Method> selfMethod = SELF_METHODS.get(context.getClass());
        return selfMethod.isPresent() ? selfMethod.get().invoke(context) : null;
    }

    @Nullable
    private static ServerSubLevel getSubLevel(Object context) throws ReflectiveOperationException {
        Optional<Field> selfSubLevelField = SELF_SUB_LEVEL_FIELDS.get(context.getClass());
        if (selfSubLevelField.isPresent()) {
            Object value = selfSubLevelField.get().get(context);
            if (value instanceof ServerSubLevel subLevel) {
                return subLevel;
            }
        }

        Optional<Method> forcesMethod = FORCES_METHODS.get(context.getClass());
        if (forcesMethod.isEmpty()) {
            return null;
        }

        Object forces = forcesMethod.get().invoke(context);
        if (forces == null) {
            return null;
        }

        Optional<Field> subLevelField = SUB_LEVEL_FIELDS.get(forces.getClass());
        if (subLevelField.isEmpty()) {
            return null;
        }

        Object value = subLevelField.get().get(forces);
        return value instanceof ServerSubLevel subLevel ? subLevel : null;
    }

    private static boolean getFlapWorldPosition(Object config, Object body, Vector3d destination)
            throws ReflectiveOperationException {
        Optional<Method> flapCenterMethod = FLAP_CENTER_LOCAL_METHODS.get(config.getClass());
        Optional<Method> modelToWorldMethod = MODEL_TO_WORLD_POSITION_METHODS.get(body.getClass());
        if (flapCenterMethod.isPresent() && modelToWorldMethod.isPresent()) {
            Object flapCenter = flapCenterMethod.get().invoke(config);
            if (flapCenter instanceof Vector3dc flapCenterLocal) {
                Object result = modelToWorldMethod.get().invoke(body, flapCenterLocal, destination);
                return result instanceof Vector3d;
            }
        }

        Optional<Method> positionWorldMethod = POSITION_WORLD_METHODS.get(body.getClass());
        if (positionWorldMethod.isEmpty()) {
            return false;
        }

        Object position = positionWorldMethod.get().invoke(body);
        if (!(position instanceof Vector3dc positionWorld)) {
            return false;
        }

        destination.set(positionWorld);
        return true;
    }

    private static Optional<Method> findPublicMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return Optional.of(method);
        } catch (NoSuchMethodException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Optional<Field> findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException exception) {
                current = current.getSuperclass();
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static void warnReflectionFailure(Exception exception) {
        if (warnedReflectionFailure) {
            return;
        }
        warnedReflectionFailure = true;
        LOGGER.warn("Failed to apply Wind Tunnel airflow to Synaxis flap aerodynamics", exception);
    }

    private static final class Scratch {
        private final Vector3d sampleWorld = new Vector3d();
    }
}
