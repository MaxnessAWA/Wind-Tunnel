package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.windtunnel.network.UpdateWindTunnelControllerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * LDLib2-based wind tunnel controller configuration screen.
 */
public class WindTunnelControllerLdlib2Screen {
    private static final int CLIENT_EDIT_GRACE_TICKS = 8;

    private final BlockPos controllerPos;
    private boolean enabled;
    private boolean spinFanBlades;
    private int targetLength = WindTunnelControllerBlockEntity.DEFAULT_LENGTH;
    private double targetAirspeed = WindTunnelControllerBlockEntity.DEFAULT_AIRSPEED;
    private int maxLength = WindTunnelControllerBlockEntity.MAX_LENGTH;
    private double maxAirspeed = WindTunnelControllerBlockEntity.MAX_AIRSPEED;
    private boolean suppressUpdates;
    private boolean waitingForServerState;
    private int pendingSyncTicks;
    private int lastSentLength = WindTunnelControllerBlockEntity.DEFAULT_LENGTH;
    private double lastSentAirspeed = WindTunnelControllerBlockEntity.DEFAULT_AIRSPEED;
    private boolean lastSentEnabled;
    private boolean lastSentSpinFanBlades = true;

    private Button enabledButton;
    private Button spinFanButton;
    private WindTunnelSlider lengthSlider;
    private WindTunnelSlider airspeedSlider;
    private TextField lengthField;
    private TextField airspeedField;

    public WindTunnelControllerLdlib2Screen(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        ControllerState state = readControllerState();
        if (state != null) {
            applyLocalState(state);
            rememberLastSentState();
        }
    }

    /* ---- block entity sync ---- */

    private ControllerState readControllerState() {
        var mc = Minecraft.getInstance();
        if (mc == null) return null;
        var level = mc.level;
        if (level == null) return null;
        BlockPos pos = Objects.requireNonNull(controllerPos);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WindTunnelControllerBlockEntity ctrl)) return null;

        boolean blockEnabled = enabled;
        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof WindTunnelControllerBlock) {
            blockEnabled = state.getValue(Objects.requireNonNull(WindTunnelControllerBlock.ENABLED));
        }
        return new ControllerState(
                blockEnabled,
                ctrl.shouldSpinFanBlades(),
                ctrl.getTargetLength(),
                ctrl.getTargetAirspeed(),
                ctrl.getConfiguredMaxLength(),
                ctrl.getConfiguredMaxAirspeed()
        );
    }

    private void applyLocalState(ControllerState state) {
        enabled = state.enabled();
        spinFanBlades = state.spinFanBlades();
        targetLength = state.targetLength();
        targetAirspeed = state.targetAirspeed();
        maxLength = state.maxLength();
        maxAirspeed = state.maxAirspeed();
    }

    private void rememberLastSentState() {
        lastSentLength = targetLength;
        lastSentAirspeed = targetAirspeed;
        lastSentEnabled = enabled;
        lastSentSpinFanBlades = spinFanBlades;
    }

    private void refreshFromBlockEntity() {
        if (pendingSyncTicks > 0) {
            pendingSyncTicks--;
        }

        if ((lengthSlider != null && lengthSlider.isSliding())
                || (airspeedSlider != null && airspeedSlider.isSliding())) {
            return;
        }

        ControllerState state = readControllerState();
        if (state == null) {
            return;
        }

        if (waitingForServerState) {
            boolean serverCaughtUp = state.enabled() == lastSentEnabled
                    && state.spinFanBlades() == lastSentSpinFanBlades
                    && state.targetLength() == lastSentLength
                    && sameValue(state.targetAirspeed(), lastSentAirspeed);
            if (serverCaughtUp) {
                waitingForServerState = false;
                pendingSyncTicks = 0;
            } else if (pendingSyncTicks > 0) {
                return;
            } else {
                waitingForServerState = false;
            }
        }

        if (state.enabled() == enabled
                && state.spinFanBlades() == spinFanBlades
                && state.targetLength() == targetLength
                && sameValue(state.targetAirspeed(), targetAirspeed)
                && state.maxLength() == maxLength
                && sameValue(state.maxAirspeed(), maxAirspeed)) {
            return;
        }

        int oldLength = targetLength;
        double oldAirspeed = targetAirspeed;
        boolean oldEnabled = enabled;
        boolean oldSpinFanBlades = spinFanBlades;

        suppressUpdates = true;
        applyLocalState(state);

        if (oldLength != targetLength) {
            if (lengthSlider != null && !lengthSlider.isSliding()) {
                lengthSlider.setValue(targetLength);
            }
            if (lengthField != null && !lengthField.isFocused()) {
                syncLengthField();
            }
        }
        if (!sameValue(oldAirspeed, targetAirspeed)) {
            if (airspeedSlider != null && !airspeedSlider.isSliding()) {
                airspeedSlider.setValue(targetAirspeed);
            }
            if (airspeedField != null && !airspeedField.isFocused()) {
                syncAirspeedField();
            }
        }
        if (oldEnabled != enabled || oldSpinFanBlades != spinFanBlades) {
            updateButtonLabels();
        }
        suppressUpdates = false;
    }

    private void sendToServer() {
        if (suppressUpdates) {
            return;
        }

        waitingForServerState = true;
        pendingSyncTicks = CLIENT_EDIT_GRACE_TICKS;
        rememberLastSentState();
        PacketDistributor.sendToServer(new UpdateWindTunnelControllerPayload(
                controllerPos, targetLength, targetAirspeed, spinFanBlades, enabled));
    }

    /* ---- UI construction ---- */

    public ModularUI createUi() {
        ControllerState state = readControllerState();
        if (state != null) {
            applyLocalState(state);
        }

        var root = new UIElement()
                .layout(l -> l.width(260).height(124).paddingAll(5).gapAll(4).flexDirection(FlexDirection.COLUMN))
                .style(s -> s.background(WindTunnelLdlib2Theme.PANEL))
                .selfCall(UIElement::adaptPositionToScreen);

        // --- enabled toggle ---
        enabledButton = new Button()
                .setOnClick(e -> { enabled = !enabled; updateButtonLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(enabledButton);
        enabledButton.layout(l -> l.widthStretch().height(20));
        updateEnabledLabel();

        // --- fan spin toggle ---
        spinFanButton = new Button()
                .setOnClick(e -> { spinFanBlades = !spinFanBlades; updateButtonLabels(); sendToServer(); });
        WindTunnelLdlib2Theme.styleButton(spinFanButton);
        spinFanButton.layout(l -> l.widthStretch().height(20));
        updateFanSpinLabel();

        // --- length slider + field ---
        lengthSlider = new WindTunnelSlider(1, maxLength,
                targetLength, (IntConsumer) (v -> { targetLength = v; syncLengthField(); sendToServer(); }));

        lengthField = new TextField()
                .setText(Objects.requireNonNull(Integer.toString(targetLength)))
                .setNumbersOnlyInt(1, maxLength);
        WindTunnelLdlib2Theme.styleTextField(lengthField);
        lengthField.layout(l -> l.width(46).height(20));
        lengthField.registerValueListener(value -> {
            int parsed = parseInt(value, targetLength, 1, maxLength);
            if (parsed != targetLength) {
                targetLength = parsed;
                if (lengthSlider != null) {
                    lengthSlider.setValue(targetLength);
                }
                sendToServer();
            }
        });

        // --- airspeed slider + field ---
        airspeedSlider = new WindTunnelSlider(0.0D, maxAirspeed,
                targetAirspeed, (DoubleConsumer) (v -> { targetAirspeed = v; syncAirspeedField(); sendToServer(); }));

        airspeedField = new TextField()
                .setText(Objects.requireNonNull(formatValue(targetAirspeed)))
                .setNumbersOnlyDouble(0.0D, maxAirspeed);
        WindTunnelLdlib2Theme.styleTextField(airspeedField);
        airspeedField.layout(l -> l.width(46).height(20));
        airspeedField.registerValueListener(value -> {
            Double parsed = parseDouble(value, 0.0D, maxAirspeed);
            if (parsed != null && !sameValue(parsed, targetAirspeed)) {
                targetAirspeed = parsed;
                if (airspeedSlider != null) {
                    airspeedSlider.setValue(targetAirspeed);
                }
                sendToServer();
            }
        });

        root.addChildren(
                enabledButton,
                spinFanButton,
                valueRow("block.windtunnel.wind_tunnel_controller.target_length", lengthSlider, lengthField),
                valueRow("block.windtunnel.wind_tunnel_controller.target_airspeed", airspeedSlider, airspeedField)
        );

        UI ui = Objects.requireNonNull(UI.of(root));
        return ModularUI.of(ui);
    }

    /* ---- label updaters ---- */

    private void updateEnabledLabel() {
        if (enabledButton != null) {
            Component text = Objects.requireNonNull(Component.translatable(
                    enabled ? "block.windtunnel.wind_tunnel_controller.enabled"
                            : "block.windtunnel.wind_tunnel_controller.disabled"));
            enabledButton.setText(text);
        }
    }

    private void updateFanSpinLabel() {
        if (spinFanButton != null) {
            Component text = Objects.requireNonNull(Component.translatable(
                    spinFanBlades ? "block.windtunnel.wind_tunnel_controller.fan_spin_enabled"
                            : "block.windtunnel.wind_tunnel_controller.fan_spin_disabled"));
            spinFanButton.setText(text);
        }
    }

    private void syncLengthField() {
        if (lengthField != null) {
            String text = Objects.requireNonNull(Integer.toString(targetLength));
            if (!lengthField.getText().equals(text)) {
                lengthField.setText(text, false);
            }
        }
    }

    private void syncAirspeedField() {
        if (airspeedField != null) {
            String text = Objects.requireNonNull(formatValue(targetAirspeed));
            if (!airspeedField.getText().equals(text)) {
                airspeedField.setText(text, false);
            }
        }
    }

    private void updateButtonLabels() {
        updateEnabledLabel();
        updateFanSpinLabel();
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            return net.minecraft.util.Mth.clamp(Integer.parseInt(value), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Double parseDouble(String value, double min, double max) {
        try {
            return net.minecraft.util.Mth.clamp(Double.parseDouble(value), min, max);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatValue(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean sameValue(double first, double second) {
        return Math.abs(first - second) <= 1.0E-4D;
    }

    private UIElement valueRow(String labelKey, WindTunnelSlider slider, TextField field) {
        Label label = new Label();
        Component labelText = Objects.requireNonNull(Component.translatable(
                Objects.requireNonNull(labelKey)));
        label.setText(labelText);
        WindTunnelLdlib2Theme.styleLabel(label);
        label.layout(l -> l.width(60).height(20).flexShrink(0));
        slider.layout(l -> l.height(20).flexGrow(1).flexShrink(1));
        field.layout(l -> l.width(46).height(20).marginLeft(10));
        return new UIElement()
                .layout(l -> l.widthStretch().height(22).gapAll(3).flexDirection(FlexDirection.ROW))
                .addChildren(label, slider, field);
    }

    private record ControllerState(boolean enabled, boolean spinFanBlades, int targetLength, double targetAirspeed,
                                   int maxLength, double maxAirspeed) {
    }

    public static WindTunnelLdlib2MenuScreen<WindTunnelControllerMenu> createScreen(
            WindTunnelControllerMenu menu, Inventory inventory, Component title) {
        var controls = new WindTunnelControllerLdlib2Screen(menu.getControllerPos());
        return new WindTunnelLdlib2MenuScreen<>(menu, controls.createUi(), title, controls::refreshFromBlockEntity);
    }
}
