package io.github.windtunnel.content;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.windtunnel.registry.WindTunnelBlockEntities;
import java.util.Collection;
import java.util.UUID;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;

/**
 * Persistent state for the measurement stand.
 * <p>
 * It stores binding information (which aircraft or entity to hold), the requested aerodynamic
 * test pose (offset from interface, angle of attack, sideslip), and the most recent measured
 * forces/moments coming back from the physics hook.
 * <p>
 * Key concepts:
 * <ul>
 * <li><b>Binding</b> — Links the stand to a sublevel (aircraft) or entity. The binding is
 *     established via the two-step workflow: select interface, then bind at stand.</li>
 * <li><b>Lock</b> — When locked, the stand pins the aircraft's pose (translating and optionally
 *     constraining rotation axes) before each physics step, then releases after the step.</li>
 * <li><b>Interface anchor</b> — The sublevel-local coordinate of the interface block at binding
 *     time, stored so the lock solver does not depend on the continued existence of the
 *     interface block.</li>
 * <li><b>Measurement</b> — Aggregated lift/drag/side forces and pitch/roll/yaw moments,
 *     accumulated from Sable's force groups after each physics step.</li>
 * </ul>
 */
@SuppressWarnings("null")
public class WindTunnelMountBlockEntity extends SyncedBlockEntity {
    // ---- NBT keys ----
    private static final String BINDING_KIND_KEY = "BindingKind";
    private static final String BOUND_SUBLEVEL_KEY = "BoundSubLevel";
    private static final String BOUND_ENTITY_KEY = "BoundEntity";
    private static final String INTERFACE_POS_KEY = "InterfacePos";
    private static final String INTERFACE_FACING_KEY = "InterfaceFacing";
    private static final String INTERFACE_ANCHOR_LOCAL_X_KEY = "InterfaceAnchorLocalX";
    private static final String INTERFACE_ANCHOR_LOCAL_Y_KEY = "InterfaceAnchorLocalY";
    private static final String INTERFACE_ANCHOR_LOCAL_Z_KEY = "InterfaceAnchorLocalZ";
    private static final String APPLICATION_MODE_KEY = "ApplicationMode";
    private static final String REFERENCE_MODE_KEY = "ReferenceMode";
    private static final String FLOW_DIRECTION_KEY = "FlowDirection";
    private static final String LOCKED_KEY = "Locked";
    private static final String LOCK_PITCH_KEY = "LockPitch";
    private static final String LOCK_ROLL_KEY = "LockRoll";
    private static final String LOCK_YAW_KEY = "LockYaw";
    private static final String OFFSET_X_KEY = "OffsetX";
    private static final String OFFSET_Y_KEY = "OffsetY";
    private static final String OFFSET_Z_KEY = "OffsetZ";
    private static final String ANGLE_OF_ATTACK_KEY = "AngleOfAttack";
    private static final String SIDESLIP_ANGLE_KEY = "SideslipAngle";
    private static final String MEASUREMENT_KEY = "Measurement";
    private static final String MEASUREMENT_LIFT_KEY = "Lift";
    private static final String MEASUREMENT_DRAG_KEY = "Drag";
    private static final String MEASUREMENT_SIDE_KEY = "Side";
    private static final String MEASUREMENT_PITCH_KEY = "Pitch";
    private static final String MEASUREMENT_ROLL_KEY = "Roll";
    private static final String MEASUREMENT_YAW_KEY = "Yaw";

    // ---- Value bounds ----
    /** Offset from interface point in blocks (±64). */
    public static final double MIN_OFFSET = -64.0D;
    public static final double MAX_OFFSET = 64.0D;
    /** Angle bounds in degrees (±90). */
    public static final double MIN_ANGLE = -90.0D;
    public static final double MAX_ANGLE = 90.0D;
    /** Minimum interval between measurement syncs to avoid packet spam. */
    private static final long MEASUREMENT_SYNC_INTERVAL_TICKS = 2L;

    // ---- Binding state ----
    private BindingKind bindingKind = BindingKind.NONE;
    @Nullable
    private UUID boundSubLevelId;
    @Nullable
    private UUID boundEntityId;
    private BlockPos interfacePos = BlockPos.ZERO;
    private Direction interfaceFacing = Direction.NORTH;
    /** Sublevel-local coordinate of the interface at binding time. */
    @Nullable
    private Vector3d interfaceAnchorLocal;

    // ---- Pose/test settings ----
    private ApplicationMode applicationMode = ApplicationMode.SINGLE_BODY;
    private ReferenceMode referenceMode = ReferenceMode.INTERFACE;
    private Direction flowDirection = Direction.NORTH;
    private boolean locked;
    private boolean lockPitch = true;
    private boolean lockRoll = true;
    private boolean lockYaw = true;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double angleOfAttack;
    private double sideslipAngle;

    // ---- Measurement state ----
    private WindTunnelMountMeasurement measurement = WindTunnelMountMeasurement.EMPTY;
    private transient WindTunnelMountDiagramData diagramData = WindTunnelMountDiagramData.EMPTY;

    // ---- Transient physics state (not persisted) ----
    @Nullable
    private transient PhysicsConstraintHandle activeConstraint;
    @Nullable
    private transient UUID activeConstraintSubLevelId;
    /** Set whenever pose parameters change — triggers re-application in the next physics step. */
    private transient boolean poseDirty = true;
    private transient long lastMeasurementSyncTick = Long.MIN_VALUE;
    private transient boolean diagramCaptureRequested;

    public WindTunnelMountBlockEntity(BlockPos pos, BlockState blockState) {
        super(WindTunnelBlockEntities.WIND_TUNNEL_MOUNT.get(), pos, blockState);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel) {
            // Register with the global mount service so pre/post physics hooks can find this stand.
            WindTunnelMountService.register(this);
        }
        // Re-evaluate the pose after chunk reload even if the saved data itself did not change.
        poseDirty = true;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel) {
            WindTunnelMountService.unregister(this);
        }
        // Release any active physics constraint to avoid leaving the aircraft pinned.
        releaseConstraint();
        super.setRemoved();
    }

    /** Returns true if this mount has a valid binding to an aircraft or entity. */
    public boolean hasBinding() {
        return switch (bindingKind) {
            case SUBLEVEL -> boundSubLevelId != null;
            case ENTITY -> boundEntityId != null;
            case NONE -> false;
        };
    }

    public boolean isEntityBinding() { return bindingKind == BindingKind.ENTITY && boundEntityId != null; }
    public boolean isSubLevelBinding() { return bindingKind == BindingKind.SUBLEVEL && boundSubLevelId != null; }
    public BindingKind getBindingKind() { return bindingKind; }
    @Nullable public UUID getBoundSubLevelId() { return boundSubLevelId; }
    @Nullable public UUID getBoundEntityId() { return boundEntityId; }
    public BlockPos getInterfacePos() { return interfacePos; }
    public Direction getInterfaceFacing() { return interfaceFacing; }

    /**
     * Updates the stored interface facing direction when the interface block is wrenched.
     * This triggers pose re-evaluation so the stand axes stay aligned with the marker.
     */
    public void updateInterfaceFacing(Direction interfaceFacing) {
        if (interfaceFacing == null || this.interfaceFacing == interfaceFacing) {
            return;
        }
        this.interfaceFacing = interfaceFacing;
        this.poseDirty = true;
        setChanged();
        if (level != null && !level.isClientSide) {
            sendData();
        }
    }

    @Nullable
    public Vector3d getStoredInterfaceAnchorLocal() {
        return interfaceAnchorLocal == null ? null : new Vector3d(interfaceAnchorLocal);
    }

    /**
     * Stores the interface anchor in sublevel-local coordinates.
     * This decouples the lock solver from the continued existence of the interface block itself,
     * which is important once players start editing the aircraft while it is mounted.
     */
    public void setStoredInterfaceAnchorLocal(Vector3d interfaceAnchorLocal) {
        if (this.interfaceAnchorLocal != null && this.interfaceAnchorLocal.distanceSquared(interfaceAnchorLocal) <= 1.0E-6D) {
            return;
        }
        this.interfaceAnchorLocal = new Vector3d(interfaceAnchorLocal);
        setChanged();
    }

    /** Returns the horizontal facing of the stand block. */
    public Direction getMountFacing() {
        return getBlockState().getValue(WindTunnelMountBlock.FACING);
    }

    /** Returns true if this mount block is placed on a Sable aircraft sublevel. */
    public boolean isPlacedOnAircraft() {
        return level != null
                && !level.isClientSide
                && Sable.HELPER.getContaining(level, worldPosition) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel;
    }

    public Direction getFlowDirection() { return flowDirection; }

    public ApplicationMode getApplicationMode() {
        return applicationMode;
    }

    public ReferenceMode getReferenceMode() {
        return referenceMode;
    }

    public boolean isLocked() {
        return locked && hasBinding();
    }

    public boolean isPitchLocked() {
        return lockPitch;
    }

    public boolean isRollLocked() {
        return lockRoll;
    }

    public boolean isYawLocked() {
        return lockYaw;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    public double getAngleOfAttack() {
        return angleOfAttack;
    }

    public double getSideslipAngle() {
        return sideslipAngle;
    }

    public WindTunnelMountMeasurement getMeasurement() {
        return measurement;
    }

    public WindTunnelMountDiagramData getDiagramData() {
        return diagramData;
    }

    public void bind(ServerLevel level, UUID subLevelId, BlockPos interfacePos, Direction interfaceFacing) {
        // Persist both the selected block position and the resolved local anchor. The block
        // position is still useful for UI/debugging, but the local anchor is the stable datum
        // actually used by the pose solver after binding.
        this.bindingKind = BindingKind.SUBLEVEL;
        this.boundSubLevelId = subLevelId;
        this.boundEntityId = null;
        this.interfacePos = interfacePos.immutable();
        this.interfaceFacing = interfaceFacing;
        this.interfaceAnchorLocal = resolveInterfaceAnchorLocalForBinding(level, subLevelId, interfacePos);
        this.applicationMode = resolveDefaultApplicationMode(level, subLevelId);
        initializeOffsetsFromBinding(level, subLevelId, interfacePos, interfaceAnchorLocal, referenceMode, applicationMode);
        this.measurement = WindTunnelMountMeasurement.EMPTY;
        this.diagramData = WindTunnelMountDiagramData.EMPTY;
        this.poseDirty = true;
        this.diagramCaptureRequested = false;
        setChanged();
        sendData();
    }

    public void bindEntity(ServerLevel level, Entity entity, Direction facing) {
        this.bindingKind = BindingKind.ENTITY;
        this.boundSubLevelId = null;
        this.boundEntityId = entity.getUUID();
        this.interfacePos = entity.blockPosition().immutable();
        this.interfaceFacing = facing;
        this.interfaceAnchorLocal = null;
        this.applicationMode = ApplicationMode.SINGLE_BODY;
        initializeOffsetsFromEntityBinding(entity);
        this.measurement = WindTunnelMountMeasurement.EMPTY;
        this.diagramData = WindTunnelMountDiagramData.EMPTY;
        this.poseDirty = true;
        this.diagramCaptureRequested = false;
        setChanged();
        sendData();
    }

    private void initializeOffsetsFromBinding(ServerLevel level, UUID subLevelId, BlockPos interfacePos, @Nullable Vector3d interfaceAnchorLocal,
                                              ReferenceMode referenceMode, ApplicationMode applicationMode) {
        // Offset sliders are defined in world-space X/Y/Z relative to the mount center, so seed
        // them from the interface's current world position instead of some mount-local basis.
        Vector3d mountCenter = toCenter(worldPosition);
        Vector3d referencePointWorld = resolveReferencePointWorld(level, subLevelId, interfacePos, interfaceAnchorLocal, referenceMode, applicationMode);
        this.offsetX = clampOffset(referencePointWorld.x() - mountCenter.x());
        this.offsetY = clampOffset(referencePointWorld.y() - mountCenter.y());
        this.offsetZ = clampOffset(referencePointWorld.z() - mountCenter.z());
    }

    private void initializeOffsetsFromEntityBinding(Entity entity) {
        Vector3d mountCenter = toCenter(worldPosition);
        Vector3d entityPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        this.offsetX = clampOffset(entityPosition.x() - mountCenter.x());
        this.offsetY = clampOffset(entityPosition.y() - mountCenter.y());
        this.offsetZ = clampOffset(entityPosition.z() - mountCenter.z());
    }

    private static Vector3d resolveInterfaceAnchorLocalForBinding(ServerLevel level, UUID subLevelId, BlockPos interfacePos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel) {
            Vector3d storedAnchor = toCenter(interfacePos);
            BoundingBox3ic plotBounds = subLevel.getPlot().getBoundingBox();

            // Bindings are captured from the live selected block, but older revisions may already
            // have stored plot-local coordinates. Normalize both cases into a stable local anchor.
            return plotBounds != null && plotBounds.contains(storedAnchor)
                    ? storedAnchor
                    : subLevel.logicalPose().transformPositionInverse(storedAnchor, new Vector3d());
        }

        return toCenter(interfacePos);
    }

    private static Vector3d resolveInterfaceAnchorWorld(ServerLevel level, UUID subLevelId, BlockPos interfacePos, @Nullable Vector3d interfaceAnchorLocal) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (interfaceAnchorLocal != null && container != null && container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel) {
            return subLevel.logicalPose().transformPosition(interfaceAnchorLocal, new Vector3d());
        }
        return toCenter(interfacePos);
    }

    private static Vector3d resolveReferencePointWorld(ServerLevel level, UUID subLevelId, BlockPos interfacePos, @Nullable Vector3d interfaceAnchorLocal,
                                                       ReferenceMode referenceMode, ApplicationMode applicationMode) {
        if (referenceMode == ReferenceMode.CENTER_OF_MASS) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(subLevelId) instanceof ServerSubLevel subLevel) {
                return resolveCenterOfMassWorld(subLevel, applicationMode);
            }
        }
        return resolveInterfaceAnchorWorld(level, subLevelId, interfacePos, interfaceAnchorLocal);
    }

    private static Vector3d resolveCenterOfMassWorld(ServerSubLevel referenceSubLevel, ApplicationMode applicationMode) {
        if (applicationMode != ApplicationMode.MULTI_BODY) {
            return referenceSubLevel.logicalPose().transformPosition(referenceSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
        }

        double totalMass = 0.0D;
        Vector3d weightedCenterWorld = new Vector3d();
        Collection<dev.ryanhcode.sable.sublevel.SubLevel> connected = SubLevelHelper.getConnectedChain(referenceSubLevel);
        for (dev.ryanhcode.sable.sublevel.SubLevel connectedSubLevel : connected) {
            if (!(connectedSubLevel instanceof ServerSubLevel serverSubLevel)) {
                continue;
            }
            double mass = serverSubLevel.getMassTracker().getMass();
            if (mass <= 0.0D) {
                continue;
            }
            Vector3d centerWorld = serverSubLevel.logicalPose().transformPosition(serverSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
            weightedCenterWorld.fma(mass, centerWorld);
            totalMass += mass;
        }

        if (totalMass > 1.0E-6D) {
            return weightedCenterWorld.div(totalMass);
        }
        return referenceSubLevel.logicalPose().transformPosition(referenceSubLevel.getMassTracker().getCenterOfMass(), new Vector3d());
    }

    public void clearBinding() {
        bindingKind = BindingKind.NONE;
        boundSubLevelId = null;
        boundEntityId = null;
        interfacePos = BlockPos.ZERO;
        interfaceFacing = Direction.NORTH;
        interfaceAnchorLocal = null;
        applicationMode = ApplicationMode.SINGLE_BODY;
        locked = false;
        measurement = WindTunnelMountMeasurement.EMPTY;
        diagramData = WindTunnelMountDiagramData.EMPTY;
        poseDirty = false;
        diagramCaptureRequested = false;
        releaseConstraint();
        setChanged();
        if (level != null && !level.isClientSide) {
            sendData();
        }
    }

    public void applySettings(boolean locked, ApplicationMode applicationMode, ReferenceMode referenceMode, Direction flowDirection,
                              boolean lockPitch, boolean lockRoll, boolean lockYaw, double angleOfAttack, double sideslipAngle,
                              double offsetX, double offsetY, double offsetZ) {
        ApplicationMode resolvedMode = applicationMode == null ? ApplicationMode.SINGLE_BODY : applicationMode;
        ReferenceMode resolvedReferenceMode = referenceMode == null ? ReferenceMode.INTERFACE : referenceMode;
        if (isEntityBinding()) {
            resolvedMode = ApplicationMode.SINGLE_BODY;
        }
        double clampedOffsetX = clampOffset(offsetX);
        double clampedOffsetY = clampOffset(offsetY);
        double clampedOffsetZ = clampOffset(offsetZ);
        boolean shouldRecenterOffsets = level instanceof ServerLevel
                && isSubLevelBinding()
                && boundSubLevelId != null
                && (this.referenceMode != resolvedReferenceMode
                || (this.applicationMode != resolvedMode && resolvedReferenceMode == ReferenceMode.CENTER_OF_MASS));
        if (shouldRecenterOffsets && level instanceof ServerLevel serverLevel) {
            Vector3d mountCenter = toCenter(worldPosition);
            Vector3d referencePointWorld = resolveReferencePointWorld(serverLevel, boundSubLevelId, interfacePos, interfaceAnchorLocal, resolvedReferenceMode, resolvedMode);
            clampedOffsetX = clampOffset(referencePointWorld.x() - mountCenter.x());
            clampedOffsetY = clampOffset(referencePointWorld.y() - mountCenter.y());
            clampedOffsetZ = clampOffset(referencePointWorld.z() - mountCenter.z());
        }
        boolean changed = this.locked != locked
                || this.applicationMode != resolvedMode
                || this.referenceMode != resolvedReferenceMode
                || this.flowDirection != flowDirection
                || this.lockPitch != lockPitch
                || this.lockRoll != lockRoll
                || this.lockYaw != lockYaw
                || Double.compare(this.angleOfAttack, clampAngle(angleOfAttack)) != 0
                || Double.compare(this.sideslipAngle, clampAngle(sideslipAngle)) != 0
                || Double.compare(this.offsetX, clampedOffsetX) != 0
                || Double.compare(this.offsetY, clampedOffsetY) != 0
                || Double.compare(this.offsetZ, clampedOffsetZ) != 0;

        this.locked = locked;
        this.applicationMode = resolvedMode;
        this.referenceMode = resolvedReferenceMode;
        this.flowDirection = flowDirection;
        this.lockPitch = lockPitch;
        this.lockRoll = lockRoll;
        this.lockYaw = lockYaw;
        this.angleOfAttack = clampAngle(angleOfAttack);
        this.sideslipAngle = clampAngle(sideslipAngle);
        this.offsetX = clampedOffsetX;
        this.offsetY = clampedOffsetY;
        this.offsetZ = clampedOffsetZ;

        if (changed) {
            // The service consumes this flag during the next physics step and recomputes target
            // pose only when something geometric actually changed.
            poseDirty = true;
            setChanged();
            if (level != null && !level.isClientSide) {
                sendData();
            }
        }
    }

    public boolean isPoseDirty() {
        return poseDirty;
    }

    /**
     * The service clears this after consuming the latest geometry edit during the next physics
     * step. It is intentionally separate from NBT dirtiness because not every pose recomputation
     * needs a full save.
     */
    public void clearPoseDirty() {
        poseDirty = false;
    }

    public void setMeasurement(WindTunnelMountMeasurement measurement) {
        if (this.measurement.nearlyEquals(measurement)) {
            return;
        }

        this.measurement = measurement;
        setChanged();
        if (level != null && !level.isClientSide) {
            long gameTime = level.getGameTime();
            // Force readings can change every physics step, so sync at a lower cadence to avoid
            // flooding clients while still feeling live in the UI.
            if (gameTime - lastMeasurementSyncTick >= MEASUREMENT_SYNC_INTERVAL_TICKS) {
                lastMeasurementSyncTick = gameTime;
                sendData();
            }
        }
    }

    public void clearMeasurement() {
        setMeasurement(WindTunnelMountMeasurement.EMPTY);
    }

    public void setDiagramData(WindTunnelMountDiagramData diagramData) {
        this.diagramData = diagramData == null ? WindTunnelMountDiagramData.EMPTY : diagramData;
    }

    public void requestDiagramCapture() {
        diagramCaptureRequested = true;
    }

    /**
     * Diagram capture is edge-triggered. The screen can request a fresh frame at any time, but
     * the server only arms one capture sequence and clears the flag once consumed.
     */
    public boolean consumeDiagramCaptureRequest() {
        boolean requested = diagramCaptureRequested;
        diagramCaptureRequested = false;
        return requested;
    }

    @Nullable
    public PhysicsConstraintHandle getActiveConstraint() {
        return activeConstraint;
    }

    public boolean hasValidConstraintFor(UUID subLevelId) {
        return activeConstraint != null
                && activeConstraint.isValid()
                && subLevelId.equals(activeConstraintSubLevelId);
    }

    public void setActiveConstraint(@Nullable PhysicsConstraintHandle activeConstraint, @Nullable UUID subLevelId) {
        this.activeConstraint = activeConstraint;
        this.activeConstraintSubLevelId = subLevelId;
    }

    /**
     * The current mount implementation mostly teleports and zeroes velocity every physics step,
     * but the constraint handle is still cleaned up here for older/alternate lock paths.
     */
    public void releaseConstraint() {
        if (activeConstraint != null) {
            activeConstraint.remove();
        }
        activeConstraint = null;
        activeConstraintSubLevelId = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(BINDING_KIND_KEY, bindingKind.serializedName());
        if (boundSubLevelId != null) {
            tag.putUUID(BOUND_SUBLEVEL_KEY, boundSubLevelId);
            tag.putLong(INTERFACE_POS_KEY, interfacePos.asLong());
            tag.putString(INTERFACE_FACING_KEY, interfaceFacing.getName());
            if (interfaceAnchorLocal != null) {
                tag.putDouble(INTERFACE_ANCHOR_LOCAL_X_KEY, interfaceAnchorLocal.x());
                tag.putDouble(INTERFACE_ANCHOR_LOCAL_Y_KEY, interfaceAnchorLocal.y());
                tag.putDouble(INTERFACE_ANCHOR_LOCAL_Z_KEY, interfaceAnchorLocal.z());
            }
        }
        if (boundEntityId != null) {
            tag.putUUID(BOUND_ENTITY_KEY, boundEntityId);
        }
        tag.putString(APPLICATION_MODE_KEY, applicationMode.serializedName());
        tag.putString(REFERENCE_MODE_KEY, referenceMode.serializedName());
        tag.putString(FLOW_DIRECTION_KEY, flowDirection.getName());
        tag.putBoolean(LOCKED_KEY, locked);
        tag.putBoolean(LOCK_PITCH_KEY, lockPitch);
        tag.putBoolean(LOCK_ROLL_KEY, lockRoll);
        tag.putBoolean(LOCK_YAW_KEY, lockYaw);
        tag.putDouble(OFFSET_X_KEY, offsetX);
        tag.putDouble(OFFSET_Y_KEY, offsetY);
        tag.putDouble(OFFSET_Z_KEY, offsetZ);
        tag.putDouble(ANGLE_OF_ATTACK_KEY, angleOfAttack);
        tag.putDouble(SIDESLIP_ANGLE_KEY, sideslipAngle);
        CompoundTag measurementTag = new CompoundTag();
        measurementTag.putDouble(MEASUREMENT_LIFT_KEY, measurement.lift());
        measurementTag.putDouble(MEASUREMENT_DRAG_KEY, measurement.drag());
        measurementTag.putDouble(MEASUREMENT_SIDE_KEY, measurement.sideForce());
        measurementTag.putDouble(MEASUREMENT_PITCH_KEY, measurement.pitchMoment());
        measurementTag.putDouble(MEASUREMENT_ROLL_KEY, measurement.rollMoment());
        measurementTag.putDouble(MEASUREMENT_YAW_KEY, measurement.yawMoment());
        tag.put(MEASUREMENT_KEY, measurementTag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        bindingKind = tag.contains(BINDING_KIND_KEY, Tag.TAG_STRING)
                ? BindingKind.fromSerializedName(tag.getString(BINDING_KIND_KEY))
                : (tag.hasUUID(BOUND_SUBLEVEL_KEY) ? BindingKind.SUBLEVEL : (tag.hasUUID(BOUND_ENTITY_KEY) ? BindingKind.ENTITY : BindingKind.NONE));
        boundSubLevelId = tag.hasUUID(BOUND_SUBLEVEL_KEY) ? tag.getUUID(BOUND_SUBLEVEL_KEY) : null;
        boundEntityId = tag.hasUUID(BOUND_ENTITY_KEY) ? tag.getUUID(BOUND_ENTITY_KEY) : null;
        if (boundSubLevelId != null && tag.contains(INTERFACE_POS_KEY)) {
            interfacePos = BlockPos.of(tag.getLong(INTERFACE_POS_KEY));
            interfaceFacing = readDirection(tag.getString(INTERFACE_FACING_KEY), Direction.NORTH);
            if (tag.contains(INTERFACE_ANCHOR_LOCAL_X_KEY, Tag.TAG_DOUBLE)
                    && tag.contains(INTERFACE_ANCHOR_LOCAL_Y_KEY, Tag.TAG_DOUBLE)
                    && tag.contains(INTERFACE_ANCHOR_LOCAL_Z_KEY, Tag.TAG_DOUBLE)) {
                interfaceAnchorLocal = new Vector3d(
                        tag.getDouble(INTERFACE_ANCHOR_LOCAL_X_KEY),
                        tag.getDouble(INTERFACE_ANCHOR_LOCAL_Y_KEY),
                        tag.getDouble(INTERFACE_ANCHOR_LOCAL_Z_KEY)
                );
            } else {
                interfaceAnchorLocal = null;
            }
        } else {
            interfacePos = BlockPos.ZERO;
            interfaceFacing = Direction.NORTH;
            interfaceAnchorLocal = null;
        }

        applicationMode = ApplicationMode.fromSerializedName(tag.getString(APPLICATION_MODE_KEY));
        referenceMode = ReferenceMode.fromSerializedName(tag.getString(REFERENCE_MODE_KEY));
        flowDirection = readDirection(tag.getString(FLOW_DIRECTION_KEY), Direction.NORTH);
        locked = tag.getBoolean(LOCKED_KEY);
        lockPitch = !tag.contains(LOCK_PITCH_KEY, Tag.TAG_BYTE) || tag.getBoolean(LOCK_PITCH_KEY);
        lockRoll = !tag.contains(LOCK_ROLL_KEY, Tag.TAG_BYTE) || tag.getBoolean(LOCK_ROLL_KEY);
        lockYaw = !tag.contains(LOCK_YAW_KEY, Tag.TAG_BYTE) || tag.getBoolean(LOCK_YAW_KEY);
        offsetX = clampOffset(tag.getDouble(OFFSET_X_KEY));
        offsetY = clampOffset(tag.getDouble(OFFSET_Y_KEY));
        offsetZ = clampOffset(tag.getDouble(OFFSET_Z_KEY));
        angleOfAttack = clampAngle(tag.getDouble(ANGLE_OF_ATTACK_KEY));
        sideslipAngle = clampAngle(tag.getDouble(SIDESLIP_ANGLE_KEY));

        if (tag.contains(MEASUREMENT_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag measurementTag = tag.getCompound(MEASUREMENT_KEY);
            measurement = new WindTunnelMountMeasurement(
                    measurementTag.getDouble(MEASUREMENT_LIFT_KEY),
                    measurementTag.getDouble(MEASUREMENT_DRAG_KEY),
                    measurementTag.getDouble(MEASUREMENT_SIDE_KEY),
                    measurementTag.getDouble(MEASUREMENT_PITCH_KEY),
                    measurementTag.getDouble(MEASUREMENT_ROLL_KEY),
                    measurementTag.getDouble(MEASUREMENT_YAW_KEY)
            );
        } else {
            measurement = WindTunnelMountMeasurement.EMPTY;
        }

        poseDirty = true;
    }

    private static double clampOffset(double value) {
        return Mth.clamp(value, MIN_OFFSET, MAX_OFFSET);
    }

    private static double clampAngle(double value) {
        return Mth.clamp(value, MIN_ANGLE, MAX_ANGLE);
    }

    private static Vector3d toCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private static Direction readDirection(String name, Direction fallback) {
        Direction direction = Direction.byName(name);
        return direction != null ? direction : fallback;
    }

    private static ApplicationMode resolveDefaultApplicationMode(ServerLevel level, UUID subLevelId) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return ApplicationMode.SINGLE_BODY;
        }
        if (!(container.getSubLevel(subLevelId) instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel subLevel)) {
            return ApplicationMode.SINGLE_BODY;
        }

        Collection<dev.ryanhcode.sable.sublevel.SubLevel> connected = SubLevelHelper.getConnectedChain(subLevel);
        if (connected.size() > 1) {
            return ApplicationMode.MULTI_BODY;
        }
        return ApplicationMode.SINGLE_BODY;
    }

    public enum BindingKind {
        NONE("none"),
        SUBLEVEL("sublevel"),
        ENTITY("entity");

        private final String serializedName;

        BindingKind(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public static BindingKind fromSerializedName(String name) {
            for (BindingKind kind : values()) {
                if (kind.serializedName.equals(name)) {
                    return kind;
                }
            }
            return NONE;
        }
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
        INTERFACE("interface"),
        CENTER_OF_MASS("center_of_mass");

        private final String serializedName;

        ReferenceMode(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return serializedName;
        }

        public ReferenceMode next() {
            return this == INTERFACE ? CENTER_OF_MASS : INTERFACE;
        }

        public static ReferenceMode fromSerializedName(String name) {
            for (ReferenceMode mode : values()) {
                if (mode.serializedName.equals(name)) {
                    return mode;
                }
            }
            return INTERFACE;
        }
    }
}
