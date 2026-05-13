package io.github.windtunnel.content;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import io.github.windtunnel.config.WindTunnelConfig;
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
    private static final String CONFIGURED_MAX_LENGTH_KEY = "ConfiguredMaxLength";
    private static final String CONFIGURED_MAX_AIRSPEED_KEY = "ConfiguredMaxAirspeed";

    // ---- Value bounds ----
    private static final int MIN_LENGTH = 1;
    /** Maximum tunnel scan range in blocks. */
    public static final int MAX_LENGTH = 256;
    private static final double MIN_AIRSPEED = 0.0D;
    /** Maximum airspeed in blocks/second. */
    public static final double MAX_AIRSPEED = 256.0D;
    /** Default tunnel length (in blocks). */
    public static final int DEFAULT_LENGTH = 16;
    /** Default airspeed (in blocks/second). */
    public static final double DEFAULT_AIRSPEED = 12.0D;

    /** Target tunnel scan range set by the player. */
    private int targetLength = initialTargetLength();
    /** Target airspeed set by the player. */
    private double targetAirspeed = initialTargetAirspeed();
    /** Whether the controller requests fan blades to spin visually. */
    private boolean spinFanBlades = true;
    /** Server-configured limits mirrored to clients for GUI ranges. */
    private int clientConfiguredMaxLength = configuredMaxLength();
    private double clientConfiguredMaxAirspeed = configuredMaxAirspeed();

    public WindTunnelControllerBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.WIND_TUNNEL_CONTROLLER.get(), pos, blockState);
    }

    @Override
    public void addBehaviours(List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        // No Create behaviours are needed yet; the custom GUI drives state directly.
    }

    public int getTargetLength() { return targetLength; }
    public double getTargetAirspeed() { return targetAirspeed; }
    public boolean shouldSpinFanBlades() { return spinFanBlades; }

    /**
     * Applies settings and triggers a network refresh (the default mode for GUI edits).
     */
    public boolean applySettings(int targetLength, double targetAirspeed) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, true);
    }

    /**
     * Applies settings silently (no network refresh). Used by server-authoritative sync packets
     * to avoid re-triggering the same refresh that already happened.
     */
    public boolean applySettingsSilently(int targetLength, double targetAirspeed) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, false);
    }

    public boolean applySettings(int targetLength, double targetAirspeed, boolean spinFanBlades) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, true);
    }

    public boolean applySettingsSilently(int targetLength, double targetAirspeed, boolean spinFanBlades) {
        return applySettings(targetLength, targetAirspeed, spinFanBlades, false);
    }

    /**
     * Core settings logic. Values are clamped to valid ranges and only trigger NBT save + optional
     * network refresh when they actually change.
     *
     * @param refreshNetwork if true, triggers {@link WindTunnelNetwork#refreshFromController}
     */
    private boolean applySettings(int targetLength, double targetAirspeed, boolean spinFanBlades, boolean refreshNetwork) {
        boolean serverAuthoritative = level != null && !level.isClientSide;
        int clampedLength = Mth.clamp(
                targetLength,
                MIN_LENGTH,
                serverAuthoritative ? configuredMaxLength() : MAX_LENGTH
        );
        double clampedAirspeed = Mth.clamp(
                targetAirspeed,
                MIN_AIRSPEED,
                serverAuthoritative ? configuredMaxAirspeed() : MAX_AIRSPEED
        );
        if (this.targetLength == clampedLength
                && Double.compare(this.targetAirspeed, clampedAirspeed) == 0
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
            sanitizeServerConfiguredLimits();
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
        tag.putDouble(TARGET_AIRSPEED_KEY, targetAirspeed);
        tag.putBoolean(SPIN_FAN_BLADES_KEY, spinFanBlades);
        if (clientPacket) {
            tag.putInt(CONFIGURED_MAX_LENGTH_KEY, configuredMaxLength());
            tag.putDouble(CONFIGURED_MAX_AIRSPEED_KEY, configuredMaxAirspeed());
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains(TARGET_LENGTH_KEY)) {
            targetLength = Mth.clamp(tag.getInt(TARGET_LENGTH_KEY), MIN_LENGTH, MAX_LENGTH);
        }
        if (tag.contains(TARGET_AIRSPEED_KEY)) {
            targetAirspeed = Mth.clamp(tag.getDouble(TARGET_AIRSPEED_KEY), MIN_AIRSPEED, MAX_AIRSPEED);
        }
        if (tag.contains(SPIN_FAN_BLADES_KEY)) {
            spinFanBlades = tag.getBoolean(SPIN_FAN_BLADES_KEY);
        }
        if (tag.contains(CONFIGURED_MAX_LENGTH_KEY)) {
            clientConfiguredMaxLength = Mth.clamp(tag.getInt(CONFIGURED_MAX_LENGTH_KEY), MIN_LENGTH, MAX_LENGTH);
        }
        if (tag.contains(CONFIGURED_MAX_AIRSPEED_KEY)) {
            clientConfiguredMaxAirspeed = Mth.clamp(tag.getDouble(CONFIGURED_MAX_AIRSPEED_KEY), MIN_AIRSPEED, MAX_AIRSPEED);
        }
    }

    public int getConfiguredTargetLength() {
        return Mth.clamp(targetLength, MIN_LENGTH, configuredMaxLength());
    }

    public double getConfiguredTargetAirspeed() {
        return Mth.clamp(targetAirspeed, MIN_AIRSPEED, configuredMaxAirspeed());
    }

    public int getConfiguredMaxLength() {
        return level != null && level.isClientSide ? clientConfiguredMaxLength : configuredMaxLength();
    }

    public double getConfiguredMaxAirspeed() {
        return level != null && level.isClientSide ? clientConfiguredMaxAirspeed : configuredMaxAirspeed();
    }

    public void applyClientConfiguredLimits(int maxLength, double maxAirspeed) {
        clientConfiguredMaxLength = Mth.clamp(maxLength, MIN_LENGTH, MAX_LENGTH);
        clientConfiguredMaxAirspeed = Mth.clamp(maxAirspeed, MIN_AIRSPEED, MAX_AIRSPEED);
    }

    private boolean sanitizeServerConfiguredLimits() {
        int clampedLength = Mth.clamp(targetLength, MIN_LENGTH, configuredMaxLength());
        double clampedAirspeed = Mth.clamp(targetAirspeed, MIN_AIRSPEED, configuredMaxAirspeed());
        if (clampedLength == targetLength && Double.compare(clampedAirspeed, targetAirspeed) == 0) {
            return false;
        }

        targetLength = clampedLength;
        targetAirspeed = clampedAirspeed;
        setChanged();
        sendData();
        return true;
    }

    private static int configuredMaxLength() {
        return Mth.clamp(WindTunnelConfig.maxRange(), MIN_LENGTH, MAX_LENGTH);
    }

    private static double configuredMaxAirspeed() {
        return Mth.clamp(WindTunnelConfig.maxAirspeed(), MIN_AIRSPEED, MAX_AIRSPEED);
    }

    private static int initialTargetLength() {
        return Mth.clamp(DEFAULT_LENGTH, MIN_LENGTH, configuredMaxLength());
    }

    private static double initialTargetAirspeed() {
        return Mth.clamp(WindTunnelConfig.baseAirspeed(), MIN_AIRSPEED, configuredMaxAirspeed());
    }
}
