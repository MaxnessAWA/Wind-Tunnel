package io.github.windtunnel.content;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.AirFlowParticleData;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import io.github.windtunnel.config.WindTunnelConfig;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Stores the effective airflow settings for one tunnel segment and exposes them in two ways:
 * to Create as an {@link IAirCurrentSource} for native fan particles, and to Sable through the
 * cached {@link WindTunnelFlowField} used by {@link WindTunnelWindProvider}.
 * <p>
 * The block entity does NOT tick on the server — airflow state is pushed by
 * {@link WindTunnelNetwork} when controllers change or redstone updates occur. The only tick
 * happens client-side for particle spawning.
 */
@SuppressWarnings("null")
public class WindTunnelBlockEntity extends SyncedBlockEntity implements IAirCurrentSource {
    // ---- NBT keys for persistent storage ----
    private static final String CONTROLLER_LENGTH_KEY = "ControllerLength";
    private static final String CONTROLLER_AIRSPEED_KEY = "ControllerAirspeed";
    private static final String CONTROLLER_SPIN_FAN_BLADES_KEY = "ControllerSpinFanBlades";

    /** Particle spawn chance range, mapped from airspeed. */
    private static final float MIN_PARTICLE_SPAWN_CHANCE = 0.15F;
    private static final float MAX_PARTICLE_SPAWN_CHANCE = 0.85F;

    // ---- Controller-pushed settings ----
    private int controllerLength = Mth.clamp(
            WindTunnelControllerBlockEntity.DEFAULT_LENGTH,
            1,
            WindTunnelConfig.maxRange()
    );
    private double controllerAirspeed = Mth.clamp(
            WindTunnelControllerBlockEntity.DEFAULT_AIRSPEED,
            0.0D,
            WindTunnelConfig.maxAirspeed()
    );
    private boolean controllerSpinFanBlades = true;

    // ---- Create fan integration ----
    private final AirCurrent airCurrent;
    /** Set when controller settings change to trigger airflow field rebuild. */
    private boolean airCurrentDirty = true;

    // ---- Cached derived state (avoids recomputation on every query) ----
    private Direction cachedFacing = Direction.NORTH;
    private boolean cachedActive;
    private int cachedLength = -1;
    private double cachedAirspeed = -1.0D;
    @Nullable
    private WindTunnelFlowField cachedFlowField;
    private AABB cachedSearchBounds;
    private boolean trackedActive;

    public WindTunnelBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.WIND_TUNNEL.get(), pos, blockState);
        this.airCurrent = new AirCurrent(this);
        this.cachedSearchBounds = new AABB(pos);
    }

    /**
     * Client-side tick: spawns airflow particles when the tunnel is active.
     * Server-side airflow state is pushed by {@link WindTunnelNetwork}, not by per-tick polling.
     */
    public static void clientTick(Level level, BlockPos pos, BlockState state, WindTunnelBlockEntity blockEntity) {
        blockEntity.tickAirFlowParticles();
    }

    /** Returns whether this tunnel segment is currently powered (redstone or controller). */
    public boolean isActive() {
        BlockState state = this.getBlockState();
        return state.getBlock() instanceof WindTunnelBlock && state.getValue(WindTunnelBlock.POWERED);
    }

    /** Returns the facing direction from the block state. */
    public Direction getFacing() {
        return this.getBlockState().getValue(WindTunnelBlock.FACING);
    }

    /** Returns the direction airflow is projected — same as facing for the tunnel block. */
    public Direction getFlowDirection() {
        return getFacing();
    }

    /** Returns the current controller-assigned airspeed (in blocks/second). */
    public double getControllerAirspeed() {
        return controllerAirspeed;
    }

    /**
     * Computes the rendered fan rotation speed from controller airspeed.
     * The model reuses the encased fan animation, but its RPM comes from airspeed.
     */
    public float getRenderedFanSpeed() {
        if (!isActive() || !controllerSpinFanBlades) {
            return 0.0F;
        }

        // Map airspeed to rotation: multiply by 4 and clamp to Create fan speed range.
        float renderedSpeed = (float) controllerAirspeed * 4.0F;
        if (renderedSpeed > 0.0F) {
            return Mth.clamp(renderedSpeed, 80.0F, 64.0F * 20.0F);
        }
        return Mth.clamp(renderedSpeed, -64.0F * 20.0F, -80.0F);
    }

    /** Returns the controller-assigned tunnel length. */
    public int getControllerLength() {
        return controllerLength;
    }

    /** Checks whether the given controller settings match the current stored values. */
    public boolean matchesControllerSettings(int controllerLength, double controllerAirspeed, boolean controllerSpinFanBlades) {
        return this.controllerLength == controllerLength
                && Double.compare(this.controllerAirspeed, controllerAirspeed) == 0
                && this.controllerSpinFanBlades == controllerSpinFanBlades;
    }

    /**
     * Applies new controller settings to this tunnel segment.
     * <p>
     * Only airflow-relevant inputs (length, airspeed) invalidate the cached flow field.
     * Fan spin is a pure render toggle and does NOT force the expensive airflow volume rebuild.
     */
    public void applyControllerSettings(int controllerLength, double controllerAirspeed, boolean controllerSpinFanBlades) {
        int clampedLength = Mth.clamp(controllerLength, 1, WindTunnelConfig.maxRange());
        double clampedAirspeed = Mth.clamp(controllerAirspeed, 0.0D, WindTunnelConfig.maxAirspeed());
        boolean airflowChanged = this.controllerLength != clampedLength
                || Double.compare(this.controllerAirspeed, clampedAirspeed) != 0;
        boolean visualChanged = this.controllerSpinFanBlades != controllerSpinFanBlades;
        if (!airflowChanged && !visualChanged) {
            return;
        }

        if (airflowChanged) {
            this.controllerLength = clampedLength;
            this.controllerAirspeed = clampedAirspeed;
            markAirCurrentDirty();
        }
        this.controllerSpinFanBlades = controllerSpinFanBlades;
        setChanged();
        if (level != null && !level.isClientSide) {
            if (airflowChanged) {
                refreshAirCurrentIfNeeded();
            }
            sendData();
        }
    }

    /** Returns the current search bounds (rebuilt lazily if needed). */
    public AABB getSearchBounds() {
        refreshAirCurrentIfNeeded();
        return cachedSearchBounds;
    }

    /** Returns the current cached flow field (rebuilt lazily if needed). */
    @Nullable
    public WindTunnelFlowField getFlowField() {
        refreshAirCurrentIfNeeded();
        return cachedFlowField;
    }

    /**
     * Called when network power/controller state changes without necessarily changing NBT data.
     * Forces a full airflow field rebuild.
     */
    public void onTunnelStateChanged() {
        markAirCurrentDirty();
        refreshAirCurrentIfNeeded();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        markAirCurrentDirty();
        if (this.level != null && !this.level.isClientSide) {
            // On chunk load, ensure the tunnel is registered in the correct network cluster.
            WindTunnelNetwork.refreshTunnel(this.level, this.worldPosition);
            refreshAirCurrentIfNeeded();
        }
    }

    @Override
    public void setRemoved() {
        if (this.level != null && !this.level.isClientSide) {
            // Remove this tunnel from the wind provider's tracking index.
            WindTunnelWindProvider.unregister(this);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt(CONTROLLER_LENGTH_KEY, controllerLength);
        tag.putDouble(CONTROLLER_AIRSPEED_KEY, controllerAirspeed);
        tag.putBoolean(CONTROLLER_SPIN_FAN_BLADES_KEY, controllerSpinFanBlades);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(CONTROLLER_LENGTH_KEY)) {
            controllerLength = Mth.clamp(tag.getInt(CONTROLLER_LENGTH_KEY), 1, WindTunnelConfig.maxRange());
        }
        if (tag.contains(CONTROLLER_AIRSPEED_KEY)) {
            controllerAirspeed = Mth.clamp(tag.getDouble(CONTROLLER_AIRSPEED_KEY), 0.0D, WindTunnelConfig.maxAirspeed());
        }
        if (tag.contains(CONTROLLER_SPIN_FAN_BLADES_KEY)) {
            controllerSpinFanBlades = tag.getBoolean(CONTROLLER_SPIN_FAN_BLADES_KEY);
        }
        markAirCurrentDirty();
    }

    // ---- IAirCurrentSource implementation (Create integration) ----

    @Override
    public AirCurrent getAirCurrent() {
        refreshAirCurrentIfNeeded();
        return airCurrent;
    }

    @Override
    public Level getAirCurrentWorld() {
        return level;
    }

    @Override
    public BlockPos getAirCurrentPos() {
        return worldPosition;
    }

    @Override
    public float getSpeed() {
        return isActive() ? (float) controllerAirspeed : 0.0F;
    }

    @Override
    public Direction getAirflowOriginSide() {
        return getFlowDirection();
    }

    @Override
    public Direction getAirFlowDirection() {
        return isActive() && controllerAirspeed > 0.0D ? getFlowDirection() : null;
    }

    @Override
    public float getMaxDistance() {
        refreshAirCurrentIfNeeded();
        return airCurrent.maxDistance;
    }

    @Override
    public boolean isSourceRemoved() {
        return isRemoved();
    }

    private void tickAirFlowParticles() {
        if (level == null || !level.isClientSide) {
            return;
        }

        AirCurrent current = getAirCurrent();
        if (current.maxDistance <= 0.0F || current.direction == null) {
            return;
        }

        float normalizedSpeed = normalizedConfiguredAirspeed();
        float spawnChance = Mth.lerp(normalizedSpeed, MIN_PARTICLE_SPAWN_CHANCE, MAX_PARTICLE_SPAWN_CHANCE);
        if (level.random.nextFloat() > spawnChance) {
            return;
        }

        // Match Create's fan presentation loosely: faster airspeed means both more frequent and
        // slightly denser particles, but the actual force still comes from Sable's air query.
        int particleCount = 1 + Mth.floor(normalizedSpeed * 2.0F);
        Vec3 spawnPos = Vec3.atCenterOf(worldPosition).add(Vec3.atLowerCornerOf(current.direction.getNormal()).scale(0.5F));
        for (int i = 0; i < particleCount; i++) {
            level.addParticle(new AirFlowParticleData(worldPosition), spawnPos.x, spawnPos.y, spawnPos.z, 0.0D, 0.0D, 0.0D);
        }
    }

    private void refreshAirCurrentIfNeeded() {
        if (level == null) {
            clearAirCurrent();
            return;
        }

        Direction facing = getFacing();
        Direction flowDirection = getFlowDirection();
        boolean active = isActive();
        // Rebuilding the flow field can scan multiple blocks ahead, so only do it when a tracked
        // input actually changed.
        if (!airCurrentDirty
                && cachedFacing == facing
                && cachedActive == active
                && cachedLength == controllerLength
                && Double.compare(cachedAirspeed, controllerAirspeed) == 0) {
            return;
        }

        cachedFacing = facing;
        cachedActive = active;
        cachedLength = controllerLength;
        cachedAirspeed = controllerAirspeed;
        airCurrentDirty = false;

        if (!active || controllerAirspeed <= 0.0D) {
            clearAirCurrent();
            return;
        }

        WindTunnelFlowField field = WindTunnelFlowField.create(level, worldPosition, facing, controllerLength, controllerAirspeed);
        if (field == null || field.length() <= 0) {
            clearAirCurrent();
            return;
        }

        // One resolved field backs both systems:
        // 1. Sable reads ambient air velocity through WindTunnelWindProvider.
        // 2. Create reads AirCurrent state for native fan particles and visuals.
        // One cached field drives both Sable's air-velocity lookup and Create's particle bounds.
        cachedFlowField = field;
        cachedSearchBounds = field.bounds();
        if (!level.isClientSide) {
            WindTunnelWindProvider.updateTracking(this, true);
            trackedActive = true;
        }

        airCurrent.direction = flowDirection;
        airCurrent.pushing = true;
        airCurrent.bounds = field.bounds();
        airCurrent.maxDistance = computeVisualDistance(field);
        airCurrent.segments.clear();
    }

    private float computeVisualDistance(WindTunnelFlowField field) {
        // Give particles a little extra visual reach at higher speed so the stream feels less
        // abruptly clipped than the strict physics volume.
        float baseLength = (float) field.length();
        float normalizedSpeed = normalizedConfiguredAirspeed();
        float speedBonus = Math.max(1.0F, baseLength * 0.45F) * normalizedSpeed;
        return baseLength + speedBonus;
    }

    private void clearAirCurrent() {
        cachedFlowField = null;
        cachedSearchBounds = new AABB(worldPosition);
        if (level != null && !level.isClientSide && trackedActive) {
            WindTunnelWindProvider.updateTracking(this, false);
            trackedActive = false;
        }
        // Keep the AirCurrent object valid even while disabled so Create-side code can still query
        // it without null checks.
        airCurrent.direction = getFlowDirection();
        airCurrent.pushing = true;
        airCurrent.maxDistance = 0.0F;
        airCurrent.bounds = new AABB(worldPosition);
        airCurrent.segments.clear();
    }

    private void markAirCurrentDirty() {
        airCurrentDirty = true;
    }

    private float normalizedConfiguredAirspeed() {
        double maxAirspeed = WindTunnelConfig.maxAirspeed();
        if (maxAirspeed <= 1.0E-8D) {
            return 0.0F;
        }
        return (float) Mth.clamp(controllerAirspeed / maxAirspeed, 0.0D, 1.0D);
    }
}
