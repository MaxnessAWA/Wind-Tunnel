package io.github.windtunnel.content;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

/**
 * Tracks a monotonically increasing shutdown generation per dimension.
 * Airflow injectors persist the last generation they acknowledged so they can detect that the
 * server was stopped while their chunk was unloaded and force themselves off on next load.
 */
public final class AirflowInjectorShutdownState extends SavedData {
    private static final String FILE_ID = "windtunnel_airflow_injector_shutdown_state";
    private static final String GENERATION_KEY = "Generation";

    private long generation;

    private AirflowInjectorShutdownState() {
    }

    private static AirflowInjectorShutdownState load(CompoundTag tag, HolderLookup.Provider registries) {
        AirflowInjectorShutdownState state = new AirflowInjectorShutdownState();
        state.generation = tag.getLong(GENERATION_KEY);
        return state;
    }

    public static AirflowInjectorShutdownState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(AirflowInjectorShutdownState::new, AirflowInjectorShutdownState::load), FILE_ID);
    }

    public long generation() {
        return generation;
    }

    public long bumpGeneration() {
        generation++;
        setDirty();
        return generation;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        tag.putLong(GENERATION_KEY, generation);
        return tag;
    }
}
