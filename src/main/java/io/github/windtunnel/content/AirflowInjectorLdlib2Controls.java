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
import io.github.windtunnel.network.UpdateAirflowInjectorPayload;
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

/**
 * LDLib2-based airflow injector screen — left panel controls only.
 * Hosts the control widgets and the shared Simulated-style force diagram element.
 */
public class AirflowInjectorLdlib2Controls {
    private static final String WINDOW_STORAGE_KEY = "airflow_injector";

    private final BlockPos injectorPos;
    private boolean enabled;
    private AirflowInjectorBlockEntity.ApplicationMode applicationMode =
            AirflowInjectorBlockEntity.ApplicationMode.SINGLE_BODY;
    private AirflowInjectorBlockEntity.ReferenceMode referenceMode =
            AirflowInjectorBlockEntity.ReferenceMode.BODY_RELATIVE;
    private Direction worldFlowDirection = Direction.NORTH;
    private double angleOfAttack;
    private double sideslipAngle;
    private double airspeed = AirflowInjectorBlockEntity.DEFAULT_AIRSPEED;

    private Button enabledButton;
    private Button applicationModeButton;
    private Button referenceModeButton;
    private final Map<Direction, Button> worldFlowDirectionButtons = new EnumMap<>(Direction.class);
    private WindTunnelSlider angleSlider;
    private WindTunnelSlider sideslipSlider;
    private WindTunnelSlider airspeedSlider;
    private TextField angleField;
    private TextField sideslipField;
    private TextField airspeedField;
    private WindTunnelLdlib2DiagramElement diagramElement;
    private WindTunnelResizableUi.SizeState sizeState;

    public AirflowInjectorLdlib2Controls(BlockPos injectorPos) {
        this.injectorPos = injectorPos;
        loadFromBlockEntity();
    }

    private void loadFromBlockEntity() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;
        var level = mc.level;
        if (level == null) return;
        BlockPos pos = Objects.requireNonNull(injectorPos);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AirflowInjectorBlockEntity injector)) return;

        this.enabled = injector.isEnabled();
        this.applicationMode = injector.getApplicationMode();
        this.referenceMode = injector.getReferenceMode();
        this.worldFlowDirection = injector.getWorldFlowDirection();
        this.angleOfAttack = injector.getAngleOfAttack();
        this.sideslipAngle = injector.getSideslipAngle();
        this.airspeed = injector.getAirspeed();
    }

    private void refreshFromBlockEntity() {
        double oldAngleOfAttack = angleOfAttack;
        double oldSideslipAngle = sideslipAngle;
        double oldAirspeed = airspeed;
        boolean oldEnabled = enabled;
        Direction oldWorldFlowDirection = worldFlowDirection;
        AirflowInjectorBlockEntity.ApplicationMode oldApplicationMode = applicationMode;
        AirflowInjectorBlockEntity.ReferenceMode oldReferenceMode = referenceMode;
        loadFromBlockEntity();

        if (Double.compare(oldAngleOfAttack, angleOfAttack) != 0) {
            if (angleSlider != null && !angleSlider.isSliding()) angleSlider.setValue(angleOfAttack);
            syncField(angleField, angleOfAttack);
        }
        if (Double.compare(oldSideslipAngle, sideslipAngle) != 0) {
            if (sideslipSlider != null && !sideslipSlider.isSliding()) sideslipSlider.setValue(sideslipAngle);
            syncField(sideslipField, sideslipAngle);
        }
        if (Double.compare(oldAirspeed, airspeed) != 0) {
            if (airspeedSlider != null && !airspeedSlider.isSliding()) airspeedSlider.setValue(airspeed);
            syncField(airspeedField, airspeed);
        }
        if (oldEnabled != enabled || oldApplicationMode != applicationMode || oldReferenceMode != referenceMode || oldWorldFlowDirection != worldFlowDirection) {
            updateLabels();
        }
    }

    private void sendToServer() {
        PacketDistributor.sendToServer(new UpdateAirflowInjectorPayload(
                injectorPos, enabled,
                applicationMode.serializedName(),
                referenceMode.serializedName(),
                worldFlowDirection.getName(),
                angleOfAttack, sideslipAngle, airspeed));
    }

    public ModularUI createUi() {
        loadFromBlockEntity();
        sizeState = WindTunnelResizableUi.sizeState(WINDOW_STORAGE_KEY, 570, 366, 0.84F, 0.76F);

        var root = new UIElement()
                .layout(l -> l.widthStretch().heightStretch().minWidth(570).minHeight(366).paddingAll(5).gapAll(6).flexDirection(FlexDirection.ROW))
                .style(s -> s.background(WindTunnelLdlib2Theme.PANEL))
                .selfCall(sizeState::attach);

        // --- left controls panel ---
        var controlsPanel = new UIElement()
                .layout(l -> l.width(260).heightStretch().flexShrink(0).paddingAll(5).gapAll(4).flexDirection(FlexDirection.COLUMN))
                .style(s -> s.background(WindTunnelLdlib2Theme.PANEL));

        // --- row 1: enabled + application mode ---
        enabledButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(enabled
                        ? "block.windtunnel.airflow_injector.enabled"
                        : "block.windtunnel.airflow_injector.disabled")))
                .setOnClick(e -> { enabled = !enabled; updateLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(enabledButton);
        enabledButton.layout(l -> l.widthStretch().height(20));

        applicationModeButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(applicationMode == AirflowInjectorBlockEntity.ApplicationMode.MULTI_BODY
                        ? "block.windtunnel.airflow_injector.mode_multi_body"
                        : "block.windtunnel.airflow_injector.mode_single_body")))
                .setOnClick(e -> {
                    applicationMode = applicationMode.next();
                    updateLabels();
                    sendToServer();
                });
        WindTunnelLdlib2Theme.styleButton(applicationModeButton);
        applicationModeButton.layout(l -> l.widthStretch().height(20));

        // --- row 2: reference mode ---
        referenceModeButton = new Button()
                .setText(Objects.requireNonNull(Component.translatable(referenceMode == AirflowInjectorBlockEntity.ReferenceMode.WORLD
                        ? "block.windtunnel.airflow_injector.reference_mode_world"
                        : "block.windtunnel.airflow_injector.reference_mode_body")))
                .setOnClick(e -> {
                    referenceMode = referenceMode.next();
                    updateLabels();
                    sendToServer();
                });
        WindTunnelLdlib2Theme.styleButton(referenceModeButton);
        referenceModeButton.layout(l -> l.widthStretch().height(20));

        // --- row 3-5: sliders ---
        angleSlider = new WindTunnelSlider(
                AirflowInjectorBlockEntity.MIN_ANGLE, AirflowInjectorBlockEntity.MAX_ANGLE,
                angleOfAttack,
                (DoubleConsumer) (v -> { angleOfAttack = v; syncField(angleField, angleOfAttack); sendToServer(); }));
        angleField = decimalField(angleOfAttack, AirflowInjectorBlockEntity.MIN_ANGLE, AirflowInjectorBlockEntity.MAX_ANGLE, value -> {
            angleOfAttack = value;
            if (angleSlider != null) angleSlider.setValue(value);
            sendToServer();
        });

        sideslipSlider = new WindTunnelSlider(
                AirflowInjectorBlockEntity.MIN_ANGLE, AirflowInjectorBlockEntity.MAX_ANGLE,
                sideslipAngle,
                (DoubleConsumer) (v -> { sideslipAngle = v; syncField(sideslipField, sideslipAngle); sendToServer(); }));
        sideslipField = decimalField(sideslipAngle, AirflowInjectorBlockEntity.MIN_ANGLE, AirflowInjectorBlockEntity.MAX_ANGLE, value -> {
            sideslipAngle = value;
            if (sideslipSlider != null) sideslipSlider.setValue(value);
            sendToServer();
        });

        airspeedSlider = new WindTunnelSlider(
                0.0, AirflowInjectorBlockEntity.MAX_AIRSPEED,
                airspeed,
                (DoubleConsumer) (v -> { airspeed = v; syncField(airspeedField, airspeed); sendToServer(); }));
        airspeedField = decimalField(airspeed, 0.0, AirflowInjectorBlockEntity.MAX_AIRSPEED, value -> {
            airspeed = value;
            if (airspeedSlider != null) airspeedSlider.setValue(value);
            sendToServer();
        });

        UIElement directionGrid = createDirectionGrid();

        controlsPanel.addChildren(
                WindTunnelLdlib2Theme.label(Component.translatable("block.windtunnel.airflow_injector")),
                enabledButton,
                applicationModeButton,
                referenceModeButton,
                WindTunnelLdlib2Theme.label(Component.translatable("block.windtunnel.airflow_injector.world_flow_direction")),
                directionGrid,
                valueRow("block.windtunnel.airflow_injector.angle_of_attack", angleSlider, angleField),
                valueRow("block.windtunnel.airflow_injector.sideslip_angle", sideslipSlider, sideslipField),
                valueRow("block.windtunnel.airflow_injector.airspeed", airspeedSlider, airspeedField)
        );

        diagramElement = new WindTunnelLdlib2DiagramElement(
                WindTunnelLdlib2DiagramElement.Source.AIRFLOW_INJECTOR,
                injectorPos,
                () -> enabled);
        root.addChildren(controlsPanel, diagramElement);
        root.addChild(Objects.requireNonNull(WindTunnelResizableUi.windowChrome(sizeState)));

        return ModularUI.of(Objects.requireNonNull(UI.of(root, sizeState)));
    }

    private void updateLabels() {
        if (enabledButton != null) enabledButton.setText(Objects.requireNonNull(Component.translatable(enabled
                ? "block.windtunnel.airflow_injector.enabled"
                : "block.windtunnel.airflow_injector.disabled")));
        if (applicationModeButton != null) applicationModeButton.setText(Objects.requireNonNull(Component.translatable(
                applicationMode == AirflowInjectorBlockEntity.ApplicationMode.MULTI_BODY
                        ? "block.windtunnel.airflow_injector.mode_multi_body"
                        : "block.windtunnel.airflow_injector.mode_single_body")));
        if (referenceModeButton != null) referenceModeButton.setText(Objects.requireNonNull(Component.translatable(
                referenceMode == AirflowInjectorBlockEntity.ReferenceMode.WORLD
                        ? "block.windtunnel.airflow_injector.reference_mode_world"
                        : "block.windtunnel.airflow_injector.reference_mode_body")));
        for (Map.Entry<Direction, Button> entry : worldFlowDirectionButtons.entrySet()) {
            Direction direction = entry.getKey();
            Button button = entry.getValue();
            button.setText(Objects.requireNonNull(Component.translatable(
                    Objects.requireNonNull(worldFlowDirectionKey(direction)))));
            WindTunnelLdlib2ButtonStyles.setDirectionButtonState(
                    button,
                    direction == worldFlowDirection,
                    referenceMode == AirflowInjectorBlockEntity.ReferenceMode.WORLD);
        }
    }

    private UIElement createDirectionGrid() {
        worldFlowDirectionButtons.clear();
        UIElement grid = new UIElement().layout(l -> l.widthStretch().height(46).gapAll(2).flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP));
        for (Direction direction : Direction.values()) {
            Button button = new Button()
                    .setText(Objects.requireNonNull(Component.translatable(
                            Objects.requireNonNull(worldFlowDirectionKey(direction)))))
                    .setOnClick(e -> {
                        worldFlowDirection = direction;
                        updateLabels();
                        sendToServer();
                    });
            WindTunnelLdlib2Theme.styleButton(button);
            button.layout(l -> l.width(76).height(20));
            worldFlowDirectionButtons.put(direction, button);
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
        label.layout(l -> l.width(62).height(20).flexShrink(0));
        slider.layout(l -> l.height(20).flexGrow(1).flexShrink(1));
        field.layout(l -> l.width(48).height(20).marginLeft(10));
        return new UIElement()
                .layout(l -> l.widthStretch().height(22).gapAll(3).flexDirection(FlexDirection.ROW))
                .addChildren(label, slider, field);
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
        return "block.windtunnel.airflow_injector.world_direction_" + direction.getName();
    }

    public static WindTunnelLdlib2MenuScreen<AirflowInjectorMenu> createScreen(
            AirflowInjectorMenu menu, Inventory inventory, Component title) {
        var controls = new AirflowInjectorLdlib2Controls(menu.getInjectorPos());
        ModularUI ui = controls.createUi();
        return new WindTunnelLdlib2MenuScreen<>(menu, ui, title, controls::refreshFromBlockEntity,
                controls.diagramElement, controls.sizeState);
    }
}
