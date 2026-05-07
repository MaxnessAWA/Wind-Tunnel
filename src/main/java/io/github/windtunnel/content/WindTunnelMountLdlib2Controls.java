package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.windtunnel.network.UpdateWindTunnelMountPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleConsumer;

import org.jetbrains.annotations.NotNull;

/**
 * LDLib2-based wind tunnel mount (measurement stand) controls screen.
 * <p>
 * Replaces the vanilla {@code AbstractContainerScreen} left panel with ldlib2
 * widgets: lock toggle, binding clear, application mode, reference mode,
 * rotation locks, flow direction buttons, and 5 sliders (angle of attack,
 * sideslip, offset X/Y/Z). Measurement readout is shown via {@link Label}.
 * Hosts the stand controls and the shared Simulated-style force diagram element.
 */
public class WindTunnelMountLdlib2Controls {
    private static final String WINDOW_STORAGE_KEY = "wind_tunnel_mount";

    private final BlockPos mountPos;
    private boolean locked;
    private WindTunnelMountBlockEntity.ApplicationMode applicationMode =
            WindTunnelMountBlockEntity.ApplicationMode.SINGLE_BODY;
    private WindTunnelMountBlockEntity.ReferenceMode referenceMode =
            WindTunnelMountBlockEntity.ReferenceMode.INTERFACE;
    private Direction flowDirection = Direction.NORTH;
    private boolean lockPitch = true;
    private boolean lockRoll = true;
    private boolean lockYaw = true;
    private double angleOfAttack;
    private double sideslipAngle;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private WindTunnelMountMeasurement measurement = WindTunnelMountMeasurement.EMPTY;

    // ---- widgets ----
    private Button lockButton;
    private Button clearBindingButton;
    private Button modeButton;
    private Button referenceModeButton;
    private Button pitchLockButton;
    private Button rollLockButton;
    private Button yawLockButton;
    private final Map<Direction, Button> flowDirectionButtons = new EnumMap<>(Direction.class);
    private WindTunnelSlider angleSlider;
    private WindTunnelSlider sideslipSlider;
    private WindTunnelSlider offsetXSlider;
    private WindTunnelSlider offsetYSlider;
    private WindTunnelSlider offsetZSlider;
    private TextField angleField;
    private TextField sideslipField;
    private TextField offsetXField;
    private TextField offsetYField;
    private TextField offsetZField;
    private Label measurementLabel;
    private Label momentLabel;
    private WindTunnelLdlib2DiagramElement diagramElement;
    private WindTunnelResizableUi.SizeState sizeState;

    public WindTunnelMountLdlib2Controls(BlockPos mountPos) {
        this.mountPos = mountPos;
        loadFromBlockEntity();
    }

    /* ---- block entity sync ---- */

    private void loadFromBlockEntity() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;
        var level = mc.level;
        if (level == null) return;
        BlockPos pos = Objects.requireNonNull(mountPos);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WindTunnelMountBlockEntity mount)) return;

        this.locked = mount.isLocked();
        this.applicationMode = mount.getApplicationMode();
        this.referenceMode = mount.getReferenceMode();
        this.flowDirection = mount.getFlowDirection();
        this.lockPitch = mount.isPitchLocked();
        this.lockRoll = mount.isRollLocked();
        this.lockYaw = mount.isYawLocked();
        this.angleOfAttack = mount.getAngleOfAttack();
        this.sideslipAngle = mount.getSideslipAngle();
        this.offsetX = mount.getOffsetX();
        this.offsetY = mount.getOffsetY();
        this.offsetZ = mount.getOffsetZ();
        this.measurement = mount.getMeasurement();
    }

    private void refreshFromBlockEntity() {
        boolean oldLocked = locked;
        WindTunnelMountBlockEntity.ApplicationMode oldApplicationMode = applicationMode;
        WindTunnelMountBlockEntity.ReferenceMode oldReferenceMode = referenceMode;
        Direction oldFlowDirection = flowDirection;
        boolean oldLockPitch = lockPitch;
        boolean oldLockRoll = lockRoll;
        boolean oldLockYaw = lockYaw;
        double oldAngle = angleOfAttack;
        double oldSideslip = sideslipAngle;
        double oldOffsetX = offsetX;
        double oldOffsetY = offsetY;
        double oldOffsetZ = offsetZ;
        WindTunnelMountMeasurement oldMeasurement = measurement;
        loadFromBlockEntity();

        if (oldLocked != locked
                || oldApplicationMode != applicationMode
                || oldReferenceMode != referenceMode
                || oldFlowDirection != flowDirection
                || oldLockPitch != lockPitch
                || oldLockRoll != lockRoll
                || oldLockYaw != lockYaw
                || !oldMeasurement.nearlyEquals(measurement)) {
            updateLabels();
        }
        syncValue(oldAngle, angleOfAttack, angleSlider, angleField);
        syncValue(oldSideslip, sideslipAngle, sideslipSlider, sideslipField);
        syncValue(oldOffsetX, offsetX, offsetXSlider, offsetXField);
        syncValue(oldOffsetY, offsetY, offsetYSlider, offsetYField);
        syncValue(oldOffsetZ, offsetZ, offsetZSlider, offsetZField);
    }

    private void sendToServer(boolean clearBinding) {
        PacketDistributor.sendToServer(new UpdateWindTunnelMountPayload(
                mountPos, locked, applicationMode, referenceMode, flowDirection,
                lockPitch, lockRoll, lockYaw,
                angleOfAttack, sideslipAngle,
                offsetX, offsetY, offsetZ,
                clearBinding));
    }

    private void sendToServer() {
        sendToServer(false);
    }

    /* ---- UI construction ---- */

    public ModularUI createUi() {
        loadFromBlockEntity();
        sizeState = WindTunnelResizableUi.sizeState(WINDOW_STORAGE_KEY, 580, 368, 0.86F, 0.76F);

        var root = new UIElement()
                .layout(l -> l.widthStretch().heightStretch().minWidth(580).minHeight(368)
                        .paddingLeft(5).paddingRight(5).paddingTop(5).paddingBottom(5)
                        .gapAll(6).flexDirection(FlexDirection.ROW))
                .style(s -> s.background(WindTunnelLdlib2Theme.PANEL))
                .selfCall(sizeState::attach);

        var controlsPanel = new UIElement()
                .layout(l -> l.width(270).heightStretch().flexShrink(0).paddingAll(5).gapAll(4).flexDirection(FlexDirection.COLUMN))
                .style(s -> s.background(WindTunnelLdlib2Theme.PANEL));

        // --- row 1: lock + clear binding ---
        lockButton = new Button()
                .setText(Objects.requireNonNull(lockButtonLabel()))
                .setOnClick(e -> { locked = !locked; updateLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(lockButton);
        lockButton.layout(l -> l.widthStretch().height(20));

        clearBindingButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable("block.windtunnel.wind_tunnel_mount.clear_binding")))
                .setOnClick(e -> sendToServer(true));
        WindTunnelLdlib2Theme.styleButton(clearBindingButton);
        clearBindingButton.layout(l -> l.widthStretch().height(20));

        // --- row 2: application mode + reference mode ---
        modeButton = new Button()
                .setText(Objects.requireNonNull(modeButtonLabel()))
                .setOnClick(e -> {
                    applicationMode = applicationMode.next();
                    updateLabels();
                    sendToServer();
                });
        WindTunnelLdlib2Theme.styleButton(modeButton);
        modeButton.layout(l -> l.widthStretch().height(20));

        referenceModeButton = new Button()
                .setText(Objects.requireNonNull(referenceButtonLabel()))
                .setOnClick(e -> {
                    referenceMode = referenceMode.next();
                    updateLabels();
                    sendToServer();
                });
        WindTunnelLdlib2Theme.styleButton(referenceModeButton);
        referenceModeButton.layout(l -> l.widthStretch().height(20));

        // --- row 3: rotation locks ---
        pitchLockButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(lockPitch
                        ? "block.windtunnel.wind_tunnel_mount.pitch_locked"
                        : "block.windtunnel.wind_tunnel_mount.pitch_unlocked")))
                .setOnClick(e -> { lockPitch = !lockPitch; updateLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(pitchLockButton);
        pitchLockButton.layout(l -> l.flexGrow(1).flexShrink(1).height(20));

        rollLockButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(lockRoll
                        ? "block.windtunnel.wind_tunnel_mount.roll_locked"
                        : "block.windtunnel.wind_tunnel_mount.roll_unlocked")))
                .setOnClick(e -> { lockRoll = !lockRoll; updateLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(rollLockButton);
        rollLockButton.layout(l -> l.flexGrow(1).flexShrink(1).height(20));

        yawLockButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(lockYaw
                        ? "block.windtunnel.wind_tunnel_mount.yaw_locked"
                        : "block.windtunnel.wind_tunnel_mount.yaw_unlocked")))
                .setOnClick(e -> { lockYaw = !lockYaw; updateLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(yawLockButton);
        yawLockButton.layout(l -> l.flexGrow(1).flexShrink(1).height(20));

        UIElement rotationLockRow = new UIElement()
                .layout(l -> l.widthStretch().height(20).gapAll(4).flexDirection(FlexDirection.ROW))
                .addChildren(pitchLockButton, rollLockButton, yawLockButton);

        measurementLabel = new Label();
        momentLabel = new Label();
        WindTunnelLdlib2Theme.styleLabel(measurementLabel);
        WindTunnelLdlib2Theme.styleLabel(momentLabel);
        updateMeasurementLabel();
        UIElement measurementSection = new UIElement()
                .layout(l -> l.widthStretch().gapAll(2).flexDirection(FlexDirection.COLUMN))
                .addChildren(measurementLabel, momentLabel);
        UIElement bottomSpacer = new UIElement()
                .layout(l -> l.widthStretch().flexGrow(1).flexShrink(1));

        // --- sliders ---
        angleSlider = new WindTunnelSlider(
                WindTunnelMountBlockEntity.MIN_ANGLE, WindTunnelMountBlockEntity.MAX_ANGLE,
                angleOfAttack,
                (DoubleConsumer) (v -> { angleOfAttack = v; syncField(angleField, angleOfAttack); sendToServer(); }));
        angleField = decimalField(angleOfAttack, WindTunnelMountBlockEntity.MIN_ANGLE, WindTunnelMountBlockEntity.MAX_ANGLE, value -> {
            angleOfAttack = value;
            if (angleSlider != null) angleSlider.setValue(value);
            sendToServer();
        });

        sideslipSlider = new WindTunnelSlider(
                WindTunnelMountBlockEntity.MIN_ANGLE, WindTunnelMountBlockEntity.MAX_ANGLE,
                sideslipAngle,
                (DoubleConsumer) (v -> { sideslipAngle = v; syncField(sideslipField, sideslipAngle); sendToServer(); }));
        sideslipField = decimalField(sideslipAngle, WindTunnelMountBlockEntity.MIN_ANGLE, WindTunnelMountBlockEntity.MAX_ANGLE, value -> {
            sideslipAngle = value;
            if (sideslipSlider != null) sideslipSlider.setValue(value);
            sendToServer();
        });

        offsetXSlider = new WindTunnelSlider(
                WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET,
                offsetX,
                (DoubleConsumer) (v -> { offsetX = v; syncField(offsetXField, offsetX); sendToServer(); }));
        offsetXField = decimalField(offsetX, WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET, value -> {
            offsetX = value;
            if (offsetXSlider != null) offsetXSlider.setValue(value);
            sendToServer();
        });

        offsetYSlider = new WindTunnelSlider(
                WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET,
                offsetY,
                (DoubleConsumer) (v -> { offsetY = v; syncField(offsetYField, offsetY); sendToServer(); }));
        offsetYField = decimalField(offsetY, WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET, value -> {
            offsetY = value;
            if (offsetYSlider != null) offsetYSlider.setValue(value);
            sendToServer();
        });

        offsetZSlider = new WindTunnelSlider(
                WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET,
                offsetZ,
                (DoubleConsumer) (v -> { offsetZ = v; syncField(offsetZField, offsetZ); sendToServer(); }));
        offsetZField = decimalField(offsetZ, WindTunnelMountBlockEntity.MIN_OFFSET, WindTunnelMountBlockEntity.MAX_OFFSET, value -> {
            offsetZ = value;
            if (offsetZSlider != null) offsetZSlider.setValue(value);
            sendToServer();
        });

        UIElement directionGrid = createDirectionGrid();

        controlsPanel.addChildren(
                WindTunnelLdlib2Theme.label(Component.translatable("block.windtunnel.wind_tunnel_mount")),
                lockButton, clearBindingButton,
                modeButton, referenceModeButton,
                rotationLockRow,
                WindTunnelLdlib2Theme.label(Component.translatable("block.windtunnel.wind_tunnel_mount.flow_direction")),
                directionGrid,
                valueRow("block.windtunnel.wind_tunnel_mount.angle_of_attack", angleSlider, angleField),
                valueRow("block.windtunnel.wind_tunnel_mount.sideslip_angle", sideslipSlider, sideslipField),
                valueRow("block.windtunnel.wind_tunnel_mount.offset_x", offsetXSlider, offsetXField),
                valueRow("block.windtunnel.wind_tunnel_mount.offset_y", offsetYSlider, offsetYField),
                valueRow("block.windtunnel.wind_tunnel_mount.offset_z", offsetZSlider, offsetZField),
                bottomSpacer,
                measurementSection
        );

        diagramElement = new WindTunnelLdlib2DiagramElement(
                WindTunnelLdlib2DiagramElement.Source.MOUNT,
                mountPos,
                () -> locked);
        root.addChildren(controlsPanel, diagramElement);
        root.addChild(Objects.requireNonNull(WindTunnelResizableUi.windowChrome(sizeState)));

        return ModularUI.of(Objects.requireNonNull(UI.of(root, sizeState)));
    }

    /* ---- label updaters ---- */

    private String modeValueKey() {
        return applicationMode == WindTunnelMountBlockEntity.ApplicationMode.MULTI_BODY
                ? "block.windtunnel.wind_tunnel_mount.mode_multi_body_short"
                : "block.windtunnel.wind_tunnel_mount.mode_single_body_short";
    }

    private String referenceValueKey() {
        return referenceMode == WindTunnelMountBlockEntity.ReferenceMode.CENTER_OF_MASS
                ? "block.windtunnel.wind_tunnel_mount.reference_center_of_mass_short"
                : "block.windtunnel.wind_tunnel_mount.reference_interface_short";
    }

    private Component labeledButtonText(@NotNull String labelKey,@NotNull String valueKey) {
        return Component.translatable(
                "block.windtunnel.wind_tunnel_mount.field_value",
                Component.translatable(labelKey),
                Component.translatable(valueKey));
    }

    private Component lockButtonLabel() {
        return labeledButtonText(
                "block.windtunnel.wind_tunnel_mount.lock_status",
                locked ? "block.windtunnel.wind_tunnel_mount.locked"
                        : "block.windtunnel.wind_tunnel_mount.unlocked");
    }
    @SuppressWarnings("null")
    private Component modeButtonLabel() {
        return labeledButtonText(
                "block.windtunnel.wind_tunnel_mount.mode_label",
                modeValueKey());
    }
    @SuppressWarnings("null")
    private Component referenceButtonLabel() {
        return labeledButtonText(
                "block.windtunnel.wind_tunnel_mount.reference_label",
                referenceValueKey());
    }

    private void updateMeasurementLabel() {
        if (measurementLabel != null) {
            measurementLabel.setText(Objects.requireNonNull(String.format(
                    "L %.2f  D %.2f  Y %.2f",
                    measurement.lift(), measurement.drag(), measurement.sideForce())));
        }
        if (momentLabel != null) {
            momentLabel.setText(Objects.requireNonNull(String.format(
                    "P %.2f  R %.2f  N %.2f",
                    measurement.pitchMoment(), measurement.rollMoment(), measurement.yawMoment())));
        }
    }

    private void updateLabels() {
        if (lockButton != null) lockButton.setText(Objects.requireNonNull(lockButtonLabel()));
        if (modeButton != null) modeButton.setText(Objects.requireNonNull(modeButtonLabel()));
        if (referenceModeButton != null) referenceModeButton.setText(Objects.requireNonNull(referenceButtonLabel()));
        if (pitchLockButton != null) pitchLockButton.setText(Objects.requireNonNull(Component.translatable(lockPitch
                ? "block.windtunnel.wind_tunnel_mount.pitch_locked"
                : "block.windtunnel.wind_tunnel_mount.pitch_unlocked")));
        if (rollLockButton != null) rollLockButton.setText(Objects.requireNonNull(Component.translatable(lockRoll
                ? "block.windtunnel.wind_tunnel_mount.roll_locked"
                : "block.windtunnel.wind_tunnel_mount.roll_unlocked")));
        if (yawLockButton != null) yawLockButton.setText(Objects.requireNonNull(Component.translatable(lockYaw
                ? "block.windtunnel.wind_tunnel_mount.yaw_locked"
                : "block.windtunnel.wind_tunnel_mount.yaw_unlocked")));
        for (Map.Entry<Direction, Button> entry : flowDirectionButtons.entrySet()) {
            Direction direction = entry.getKey();
            Button button = entry.getValue();
            button.setText(Objects.requireNonNull(Component.translatable(
                    Objects.requireNonNull(worldFlowDirectionKey(direction)))));
            WindTunnelLdlib2ButtonStyles.setDirectionButtonState(button, direction == flowDirection, true);
        }
        updateMeasurementLabel();
    }

    private UIElement createDirectionGrid() {
        flowDirectionButtons.clear();
        UIElement grid = new UIElement().layout(l -> l.widthStretch().height(46).gapAll(2).flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP));
        for (Direction direction : Direction.values()) {
            Button button = new Button()
                    .setText(Objects.requireNonNull(Component.translatable(
                            Objects.requireNonNull(worldFlowDirectionKey(direction)))))
                    .setOnClick(e -> {
                        flowDirection = direction;
                        updateLabels();
                        sendToServer();
                    });
            WindTunnelLdlib2Theme.styleButton(button);
            button.layout(l -> l.width(80).height(20));
            flowDirectionButtons.put(direction, button);
            grid.addChild(button);
        }
        updateLabels();
        return grid;
    }

    private TextField decimalField(double initialValue, double min, double max, DoubleConsumer consumer) {
        TextField field = new TextField()
                .setText(Objects.requireNonNull(formatValue(initialValue)))
                .setNumbersOnlyDouble(min, max);
        WindTunnelLdlib2Theme.styleTextField(field);
        field.layout(l -> l.width(48).height(20));
        field.registerValueListener(value -> {
            Double parsed = parseDouble(value, min, max);
            if (parsed != null) {
                consumer.accept(parsed);
            }
        });
        return field;
    }

    private UIElement valueRow(String labelKey, WindTunnelSlider slider, TextField field) {
        Label label = new Label();
        label.setText(Objects.requireNonNull(Component.translatable(
                Objects.requireNonNull(labelKey))));
        WindTunnelLdlib2Theme.styleLabel(label);
        label.textStyle(style -> style.textAlignVertical(Vertical.CENTER));
        label.layout(l -> l.width(64).height(20).flexShrink(0));
        slider.layout(l -> l.height(20).flexGrow(1).flexShrink(1));
        field.layout(l -> l.width(48).height(20).marginLeft(10));
        return new UIElement()
                .layout(l -> l.widthStretch().height(22).gapAll(3).flexDirection(FlexDirection.ROW))
                .addChildren(label, slider, field);
    }

    private static void syncValue(double oldValue, double newValue, WindTunnelSlider slider, TextField field) {
        if (Double.compare(oldValue, newValue) == 0) {
            return;
        }
        if (slider != null && !slider.isSliding()) {
            slider.setValue(newValue);
        }
        syncField(field, newValue);
    }

    private static void syncField(TextField field, double value) {
        if (field == null) return;
        String text = Objects.requireNonNull(formatValue(value));
        if (!field.getText().equals(text)) {
            field.setText(text, false);
        }
    }

    private static String formatValue(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static Double parseDouble(String value, double min, double max) {
        try {
            return net.minecraft.util.Mth.clamp(Double.parseDouble(value), min, max);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String worldFlowDirectionKey(Direction direction) {
        return "block.windtunnel.wind_tunnel_mount.world_direction_" + direction.getName();
    }

    public static WindTunnelLdlib2MenuScreen<WindTunnelMountMenu> createScreen(
            WindTunnelMountMenu menu, Inventory inventory, Component title) {
        var controls = new WindTunnelMountLdlib2Controls(menu.getMountPos());
        ModularUI ui = controls.createUi();
        return new WindTunnelLdlib2MenuScreen<>(menu, ui, title, controls::refreshFromBlockEntity,
                controls.diagramElement, controls.sizeState);
    }
}
