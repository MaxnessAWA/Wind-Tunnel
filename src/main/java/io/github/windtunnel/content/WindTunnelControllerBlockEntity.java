package io.github.windtunnel.content;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores controller settings for one connected tunnel cluster.
 * <p>
 * The block entity only owns target values (length, airspeed, fan-spin toggle). Cluster
 * propagation — the flood-fill scan and merged-state push to every tunnel segment — is handled by
 * {@link WindTunnelNetwork}.
 * <p>
 * The controller uses {@code SmartBlockEntity} for future Create behavior support, though no
 * behaviours are active in the current version.
 */
public class WindTunnelControllerBlockEntity extends SmartBlockEntity {
    // ---- NBT keys ----
    private static final String TARGET_LENGTH_KEY = "TargetLength";
    private static final String TARGET_AIRSPEED_KEY = "TargetAirspeed";
    private static final String SPIN_FAN_BLADES_KEY = "SpinFanBlades";

    // ---- Value bounds ----
    private static final int MIN_LENGTH = 1;
    /** Maximum tunnel scan range in blocks. */
    public static final int MAX_LENGTH = 256;
    private static final int MIN_AIRSPEED = 0;
    /** Maximum airspeed in blocks/second. */
    public static final int MAX_AIRSPEED = 128;
    /** Default tunnel length (in blocks). */
    public static final int DEFAULT_LENGTH = 16;
    /** Default airspeed (in blocks/second). */
    public static final int DEFAULT_AIRSPEED = 12;

    /** Target tunnel scan range set by the player. */
    private int targetLength = DEFAULT_LENGTH;
    /** Target airspeed set by the player. */
    private int targetAirspeed = DEFAULT_AIRSPEED;
    /** Whether the controller requests fan blades to spin visually. */
    private boolean spinFanBlades = true;

    public WindTunnelControllerBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.WIND_TUNNEL_CONTROLLER.get(), pos, blockState);
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        // No Create behaviours are needed yet; the custom GUI drives state directly.
    }

    public int getTargetLength() { return targetLength; }
    public int getTargetAirspeed() { return targetAirspeed; }
    public boolean shouldSpinFanBlades() { return spinFanBlades; }

    /**
     * Applies settings and triggers a network refresh (the default mode for GUI edits).
     */
    public boolean applySettings(int targetLength, int targetAirspeed) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, true);
    }

    /**
     * Applies settings silently (no network refresh). Used by server-authoritative sync packets
     * to avoid re-triggering the same refresh that already happened.
     */
    public boolean applySettingsSilently(int targetLength, int targetAirspeed) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, false);
    }

    public boolean applySettings(int targetLength, int targetAirspeed, boolean spinFanBlades) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, true);
    }

    public boolean applySettingsSilently(int targetLength, int targetAirspeed, boolean spinFanBlades) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, false);
    }

    /**
     * Core settings logic. Values are clamped to valid ranges and only trigger NBT save + optional
     * network refresh when they actually change.
     *
     * @param refreshNetwork if true, triggers {@link WindTunnelNetwork#refreshFromController}
     */
    private boolean applySettings(int targetLength, int targetAirspeed, boolean spinFanBlades, boolean refreshNetwork) {
        int clampedLength = Mth.clamp(targetLength, MIN_LENGTH, MAX_LENGTH);
        int clampedAirspeed = Mth.clamp(targetAirspeed, MIN_AIRSPEED, MAX_AIRSPEED);
        if (this.targetLength == clampedLength
                && this.targetAirspeed == clampedAirspeed
                && this.spinFanBlades == spinFanBlades) {
            return false;
        }

        this.targetLength = clampedLength;
        this.targetAirspeed = clampedAirspeed;
        this.spinFanBlades = spinFanBlades;
        notifySettingsChanged(refreshNetwork);
        return true;
    }

    @Override
    public void initialize() {
        super.initialize();
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            // On chunk load, ensure the cluster is scanned and tunnels are up to date.
            WindTunnelNetwork.refreshFromController(lvl, worldPosition);
        }
    }

    @Override
    public void remove() {
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            // When the controller is unloaded, adjacent networks need to re-scan.
            WindTunnelNetwork.refreshAfterControllerRemoved(lvl, worldPosition);
        }
        super.remove();
    }

    /**
     * Marks the block entity dirty, syncs to clients, and optionally triggers a network refresh.
     * One controller change can affect every connected tunnel segment, so the refresh is
     * deferred to avoid redundant scans.
     */
    private void notifySettingsChanged(boolean refreshNetwork) {
        setChanged();
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            sendData();
            if (refreshNetwork) {
                WindTunnelNetwork.refreshFromController(lvl, worldPosition);
            }
        }
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt(TARGET_LENGTH_KEY, targetLength);
        tag.putInt(TARGET_AIRSPEED_KEY, targetAirspeed);
        tag.putBoolean(SPIN_FAN_BLADES_KEY, spinFanBlades);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(TARGET_LENGTH_KEY)) {
            targetLength = Mth.clamp(tag.getInt(TARGET_LENGTH_KEY), MIN_LENGTH, MAX_LENGTH);
        }
        if (tag.contains(TARGET_AIRSPEED_KEY)) {
            targetAirspeed = Mth.clamp(tag.getInt(TARGET_AIRSPEED_KEY), MIN_AIRSPEED, MAX_AIRSPEED);
        }
        if (tag.contains(SPIN_FAN_BLADES_KEY)) {
            spinFanBlades = tag.getBoolean(SPIN_FAN_BLADES_KEY);
        }
    }
}
