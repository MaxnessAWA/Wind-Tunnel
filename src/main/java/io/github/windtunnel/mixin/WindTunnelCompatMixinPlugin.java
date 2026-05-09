package io.github.windtunnel.mixin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class WindTunnelCompatMixinPlugin implements IMixinConfigPlugin {
    private static final String SYNAXIS_MOD_ID = "synaxis";
    private static final String SYNAXIS_MIXIN_PACKAGE = "io.github.windtunnel.mixin.compat.synaxis.";

    private boolean synaxisLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        synaxisLoaded = isModLoaded(SYNAXIS_MOD_ID);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(SYNAXIS_MIXIN_PACKAGE)) {
            return synaxisLoaded;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static boolean isModLoaded(String modId) {
        return isModLoadedFromLoadingList(modId) || isModLoadedFromRuntimeList(modId);
    }

    private static boolean isModLoadedFromLoadingList(String modId) {
        try {
            Class<?> loadingModListClass = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Method getMethod = loadingModListClass.getMethod("get");
            Object loadingModList = getMethod.invoke(null);
            Method getModFileByIdMethod = loadingModListClass.getMethod("getModFileById", String.class);
            return getModFileByIdMethod.invoke(loadingModList, modId) != null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private static boolean isModLoadedFromRuntimeList(String modId) {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Method getMethod = modListClass.getMethod("get");
            Object modList = getMethod.invoke(null);
            Method isLoadedMethod = modListClass.getMethod("isLoaded", String.class);
            Object result = isLoadedMethod.invoke(modList, modId);
            return result instanceof Boolean loaded && loaded;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }
}
