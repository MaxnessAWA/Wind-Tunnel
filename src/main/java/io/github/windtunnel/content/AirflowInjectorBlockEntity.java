package io.github.windtunnel.content;

import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;

/**
 * Persistent settings for the aircraft-mounted airflow injector.
 * <p>
 * The block entity only pushes updates into {@link WindTunnelWindProvider} when its resolved
 * sublevel or configuration changes, so it does not add a per-tick polling cost to the
 * aerodynamic hot path.
 * <p>
 * Key features:
 * <ul>
 * <li><b>Shutdown detection</b> — Uses a per-dimension monotonic generation counter
 *     ({@link AirflowInjectorShutdownState}) to detect server restarts and force-disable
 *     injectors in unloaded chunks.</li>
 * <li><b>Sublevel tracking</b> — Resolves the owning SubLevel from Sable's container system
 *     and tracks all sublevels that should receive the configured relative airflow.</li>
 * <li><b>Two reference modes</b> — Body-relative (angles relative to craft orientation) and
 *     world-absolute (fixed direction irrespective of craft rotation).</li>
 * </ul>
 */
public class AirflowInjectorBlockEntity extends SyncedBlockEntity {
    // ---- NBT keys ----
    private static final String ENABLED_KEY = "Enabled";
    private static final String APPLICATION_MODE_KEY = "ApplicationMode";
    private static final String REFERENCE_MODE_KEY = "ReferenceMode";
    private static final String WORLD_FLOW_DIRECTION_KEY = "WorldFlowDirection";
    private static final String ANGLE_OF_ATTACK_KEY = "AngleOfAttack";
    private static final String SIDESLIP_ANGLE_KEY = "SideslipAngle";
    private static final String AIRSPEED_KEY = "Airspeed";
    private static final String ACKNOWLEDGED_SHUTDOWN_GENERATION_KEY = "AcknowledgedShutdownGeneration";

    // ---- Value bounds ----
    private static final double MIN_AIRSPEED = 0.0D;
    /** Maximum airspeed in blocks/second. */
    public static final double MAX_AIRSPEED = 128.0D;
    /** Minimum and maximum angle values (degrees). */
    public static final double MIN_ANGLE = -90.0D;
    public static final double MAX_ANGLE = 90.0D;
    /** Default airspeed when first placed. */
    public static final double DEFAULT_AIRSPEED = 12.0D;

    /** Whether the injector is currently producing airflow. */
    private boolean enabled;
    /** Single-body vs multi-body application mode. */
    private ApplicationMode applicationMode = ApplicationMode.SINGLE_BODY;
    /** Body-relative vs world-absolute reference frame. */
    private ReferenceMode referenceMode = ReferenceMode.BODY_RELATIVE;
    /** World-absolute flow direction (used when reference mode is WORLD_ABSOLUTE). */
    private Direction worldFlowDirection = Direction.NORTH;
    /** Angle of attack in degrees (-90 to +90). */
    private double angleOfAttack;
    /** Sideslip angle in degrees (-90 to +90). */
    private double sideslipAngle;
    /** Target airspeed in blocks/second. */
    private double airspeed = DEFAULT_AIRSPEED;
    /** Last acknowledged server shutdown generation — used for restart detection. */
    private long acknowledgedShutdownGeneration;
    /** Cached set of tracked sublevel UUIDs for the current configuration. */
    private final Set<UUID> trackedSubLevelIds = new LinkedHashSet<>();
    /** Set when configuration changes and tracking needs to be re-evaluated. */
    private transient boolean trackingDirty = true;

    public AirflowInjectorBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.AIRFLOW_INJECTOR.get(), pos, blockState);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        Level lvl = level;
        if (lvl instanceof ServerLevel serverLevel) {
            // Check if the server stopped while this chunk was unloaded. If so, force-disable
            // the injector to avoid stale state.
            long currentGeneration = AirflowInjectorShutdownState.get(serverLevel).generation();
            if (enabled && acknowledgedShutdownGeneration < currentGeneration) {
                enabled = false;
                acknowledgedShutdownGeneration = currentGeneration;
                setChanged();
            }
        }
        trackingDirty = true;
        refreshProviderTracking();
    }

    @Override
    public void setRemoved() {
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            WindTunnelWindProvider.unregister(this);
        }
        super.setRemoved();
    }

    // ---- Accessors ----

    public boolean isEnabled() { return enabled; }
    public double getAngleOfAttack() { return angleOfAttack; }
    public double getSideslipAngle() { return sideslipAngle; }
    public double getAirspeed() { return airspeed; }
    public ApplicationMode getApplicationMode() { return applicationMode; }
    public ReferenceMode getReferenceMode() { return referenceMode; }
    public Direction getWorldFlowDirection() { return worldFlowDirection; }

    /** Returns the facing direction from the block state. */
    public Direction getMountFacing() {
        return getBlockState().getValue(Objects.requireNonNull(AirflowInjectorBlock.FACING));
    }

    /**
     * Checks whether this injector is currently placed on a Sable aircraft sublevel.
     * Only injectors on aircraft can produce relative airflow.
     */
    public boolean isInstalledOnAircraft() {
        return resolveSubLevel() != null;
    }

    /** Returns an unmodifiable copy of the currently tracked sublevel UUIDs. */
    public Set<UUID> getTrackedSubLevelIds() {
        return Set.copyOf(trackedSubLevelIds);
    }

    /**
     * Applies new settings from the GUI or a network packet.
     * Only triggers a provider tracking refresh when values actually change.
     */
    public void applySettings(boolean enabled, ApplicationMode applicationMode, ReferenceMode referenceMode, Direction worldFlowDirection,
                              double angleOfAttack, double sideslipAngle, double airspeed) {
        ApplicationMode resolvedMode = applicationMode == null ? ApplicationMode.SINGLE_BODY : applicationMode;
        ReferenceMode resolvedReferenceMode = referenceMode == null ? ReferenceMode.BODY_RELATIVE : referenceMode;
        Direction resolvedWorldFlowDirection = worldFlowDirection == null ? Direction.NORTH : worldFlowDirection;
        boolean changed = this.enabled != enabled
                || this.applicationMode != resolvedMode
                || this.referenceMode != resolvedReferenceMode
                || this.worldFlowDirection != resolvedWorldFlowDirection
                || Double.compare(this.angleOfAttack, clampAngle(angleOfAttack)) != 0
                || Double.compare(this.sideslipAngle, clampAngle(sideslipAngle)) != 0
                || Double.compare(this.airspeed, clampAirspeed(airspeed)) != 0;

        this.enabled = enabled;
        this.applicationMode = resolvedMode;
        this.referenceMode = resolvedReferenceMode;
        this.worldFlowDirection = resolvedWorldFlowDirection;
        this.angleOfAttack = clampAngle(angleOfAttack);
        this.sideslipAngle = clampAngle(sideslipAngle);
        this.airspeed = clampAirspeed(airspeed);
        if (level instanceof ServerLevel serverLevel) {
            acknowledgedShutdownGeneration = AirflowInjectorShutdownState.get(serverLevel).generation();
        }

        if (changed) {
            trackingDirty = true;
            setChanged();
            Level lvl = level;
            if (lvl != null && !lvl.isClientSide) {
                refreshProviderTracking();
                sendData();
            }
        }
    }

    /** Called when the block's facing direction changes (placement, wrench rotation). */
    public void onFacingChanged() {
        trackingDirty = true;
        setChanged();
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            refreshProviderTracking();
            sendData();
        }
    }

    /**
     * Creates a {@link RelativeFlowSource} for a specific sublevel based on the current
     * injector configuration. Returns {@code null} if the injector is disabled, has zero
     * airspeed, or cannot resolve the owning sublevel.
     */
    @Nullable
    public WindTunnelWindProvider.RelativeFlowSource createRelativeFlowSource(UUID subLevelId) {
        if (!enabled || airspeed <= 0.0D) {
            return null;
        }

        ServerSubLevel originSubLevel = resolveSubLevel();
        if (originSubLevel == null) {
            return null;
        }

        WindTunnelWindProvider.RelativeFlowSource flowSource = computeRelativeFlowSource(originSubLevel, subLevelId);
        if (flowSource == null || flowSource.velocity().lengthSquared() <= 1.0E-8D) {
            return null;
        }

        return flowSource;
    }

    private void refreshProviderTracking() {
        Level lvl = level;
        if (lvl == null || lvl.isClientSide) {
            return;
        }

        if (!trackingDirty) {
            return;
        }

        trackingDirty = false;
        ServerSubLevel subLevel = resolveSubLevel();
        Set<UUID> newTrackedSubLevelIds = resolveTargetSubLevelIds(subLevel);
        if (!trackedSubLevelIds.equals(newTrackedSubLevelIds)) {
            WindTunnelWindProvider.unregister(this);
            trackedSubLevelIds.clear();
            trackedSubLevelIds.addAll(newTrackedSubLevelIds);
        }

        // Tracking and enabling are separate concerns: disabled injectors still keep their target
        // rigid-body set up to date so re-enabling is immediate.
        if (trackedSubLevelIds.isEmpty()) {
            return;
        }

        WindTunnelWindProvider.updateTracking(this, true);
        if (enabled && airspeed > 0.0D) {
            wakeUpSubLevels(subLevel);
        }
    }

    @Nullable
    private ServerSubLevel resolveSubLevel() {
        Level lvl = level;
        if (lvl == null || lvl.isClientSide) {
            return null;
        }
        if (Sable.HELPER.getContaining(lvl, worldPosition) instanceof ServerSubLevel subLevel) {
            return subLevel;
        }
        return null;
    }

    private void wakeUpSubLevels(@Nullable ServerSubLevel originSubLevel) {
        Level lvl = level;
        if (originSubLevel == null || lvl == null || lvl.isClientSide) {
            return;
        }

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(lvl);
        ServerSubLevelContainer container = lvl instanceof ServerLevel serverLevel ? SubLevelContainer.getContainer(serverLevel) : null;
        if (physicsSystem != null && container != null) {
            // Updating ambient airflow does not count as a block change to Sable, so wake the
            // rigid body explicitly or the aircraft may remain asleep until some other event
            // touches its structure.
            for (UUID subLevelId : trackedSubLevelIds) {
                if (container.getSubLevel(subLevelId) instanceof ServerSubLevel target) {
                    physicsSystem.getPipeline().wakeUp(target);
                }
            }
        }
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean(ENABLED_KEY, enabled);
        tag.putString(APPLICATION_MODE_KEY, Objects.requireNonNull(applicationMode.serializedName()));
        tag.putString(REFERENCE_MODE_KEY, Objects.requireNonNull(referenceMode.serializedName()));
        tag.putString(WORLD_FLOW_DIRECTION_KEY, Objects.requireNonNull(worldFlowDirection.getName()));
        tag.putDouble(ANGLE_OF_ATTACK_KEY, angleOfAttack);
        tag.putDouble(SIDESLIP_ANGLE_KEY, sideslipAngle);
        tag.putDouble(AIRSPEED_KEY, airspeed);
        tag.putLong(ACKNOWLEDGED_SHUTDOWN_GENERATION_KEY, acknowledgedShutdownGeneration);
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, @NotNull HolderLookup.Provider registries) {
        super.loadAdditional(Objects.requireNonNull(tag), Objects.requireNonNull(registries));
        enabled = tag.getBoolean(ENABLED_KEY);
        applicationMode = ApplicationMode.fromSerializedName(tag.getString(APPLICATION_MODE_KEY));
        referenceMode = ReferenceMode.fromSerializedName(tag.getString(REFERENCE_MODE_KEY));
        worldFlowDirection = Direction.byName(tag.getString(WORLD_FLOW_DIRECTION_KEY));
        if (worldFlowDirection == null) {
            worldFlowDirection = Direction.NORTH;
        }
        angleOfAttack = clampAngle(tag.getDouble(ANGLE_OF_ATTACK_KEY));
        sideslipAngle = clampAngle(tag.getDouble(SIDESLIP_ANGLE_KEY));
        airspeed = clampAirspeed(tag.getDouble(AIRSPEED_KEY));
        acknowledgedShutdownGeneration = tag.getLong(ACKNOWLEDGED_SHUTDOWN_GENERATION_KEY);
        trackingDirty = true;
    }

    public void disableForShutdown(long shutdownGeneration) {
        boolean changed = enabled || acknowledgedShutdownGeneration != shutdownGeneration;
        enabled = false;
        acknowledgedShutdownGeneration = shutdownGeneration;
        if (!changed) {
            return;
        }
        trackingDirty = true;
        setChanged();
        Level lvl = level;
        if (lvl != null && !lvl.isClientSide) {
            refreshProviderTracking();
            sendData();
        }
    }

    private static double clampAngle(double value) {
        return Mth.clamp(value, MIN_ANGLE, MAX_ANGLE);
    }

    private static double clampAirspeed(double value) {
        return Mth.clamp(value, MIN_AIRSPEED, MAX_AIRSPEED);
    }

    private Set<UUID> resolveTargetSubLevelIds(@Nullable ServerSubLevel originSubLevel) {
        if (originSubLevel == null) {
            return Set.of();
        }

        // Multi-body mode deliberately targets every connected rigid body, but each one still gets
        // its own local-space velocity vector after world-space reconciliation below.
        if (applicationMode == ApplicationMode.MULTI_BODY) {
            Collection<SubLevel> connected = SubLevelHelper.getConnectedChain(originSubLevel);
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            for (SubLevel subLevel : connected) {
                if (subLevel instanceof ServerSubLevel serverSubLevel) {
                    ids.add(serverSubLevel.getUniqueId());
                }
            }
            if (!ids.isEmpty()) {
                return ids;
            }
        }

        return Set.of(originSubLevel.getUniqueId());
    }

    @Nullable
    private WindTunnelWindProvider.RelativeFlowSource computeRelativeFlowSource(ServerSubLevel originSubLevel, UUID targetSubLevelId) {
        if (referenceMode == ReferenceMode.WORLD) {
            Vector3d worldAirVelocity = new Vector3d(WindTunnelSharedAerodynamics.computeWorldAirVelocity(worldFlowDirection, airspeed));
            return new WindTunnelWindProvider.RelativeFlowSource(
                    worldPosition.immutable(),
                    targetSubLevelId,
                    WindTunnelWindProvider.VelocitySpace.WORLD,
                    worldAirVelocity
            );
        }

        Vector3d originLocalAirVelocity = new Vector3d(WindTunnelSharedAerodynamics.computeLocalAirVelocity(
                getMountFacing(),
                angleOfAttack,
                sideslipAngle,
                airspeed
        ));
        if (applicationMode == ApplicationMode.SINGLE_BODY || originSubLevel.getUniqueId().equals(targetSubLevelId)) {
            return new WindTunnelWindProvider.RelativeFlowSource(
                    worldPosition.immutable(),
                    targetSubLevelId,
                    WindTunnelWindProvider.VelocitySpace.LOCAL,
                    originLocalAirVelocity
            );
        }

        if (level == null) {
            return new WindTunnelWindProvider.RelativeFlowSource(
                    worldPosition.immutable(),
                    targetSubLevelId,
                    WindTunnelWindProvider.VelocitySpace.LOCAL,
                    originLocalAirVelocity
            );
        }

        SubLevelPhysicsSystem physicsSystem = SubLevelPhysicsSystem.get(level);
        ServerSubLevelContainer container = level instanceof ServerLevel serverLevel ? SubLevelContainer.getContainer(serverLevel) : null;
        if (physicsSystem == null || container == null) {
            return new WindTunnelWindProvider.RelativeFlowSource(
                    worldPosition.immutable(),
                    targetSubLevelId,
                    WindTunnelWindProvider.VelocitySpace.LOCAL,
                    originLocalAirVelocity
            );
        }

        if (!(container.getSubLevel(targetSubLevelId) instanceof ServerSubLevel targetSubLevel)) {
            return new WindTunnelWindProvider.RelativeFlowSource(
                    worldPosition.immutable(),
                    targetSubLevelId,
                    WindTunnelWindProvider.VelocitySpace.LOCAL,
                    originLocalAirVelocity
            );
        }

        // Multi-body body-relative mode is defined in world space first, then converted back into
        // each body's local frame so all bodies see the same incoming direction globally.
        Vector3d worldAirVelocity = originSubLevel.logicalPose().transformNormal(originLocalAirVelocity, new Vector3d());
        Vector3d targetLocalAirVelocity = targetSubLevel.logicalPose().transformNormalInverse(worldAirVelocity, new Vector3d());
        return new WindTunnelWindProvider.RelativeFlowSource(
                worldPosition.immutable(),
                targetSubLevelId,
                WindTunnelWindProvider.VelocitySpace.LOCAL,
                targetLocalAirVelocity
        );
    }

    public enum ApplicationMode {
        SINGLE_BODY("single_body"),
        MULTI_BODY("multi_body");

        private final String serializedName;

        ApplicationMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public ApplicationMode next() {
            return this == SINGLE_BODY ? MULTI_BODY : SINGLE_BODY;
        }

        public static ApplicationMode fromSerializedName(String name) {
            for (ApplicationMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return SINGLE_BODY;
        }
    }

    public enum ReferenceMode {
        BODY_RELATIVE("body_relative"),
        WORLD("world");

        private final String serializedName;

        ReferenceMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public ReferenceMode next() {
            return this == BODY_RELATIVE ? WORLD : BODY_RELATIVE;
        }

        public static ReferenceMode fromSerializedName(String name) {
            for (ReferenceMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return BODY_RELATIVE;
        }
    }

}
