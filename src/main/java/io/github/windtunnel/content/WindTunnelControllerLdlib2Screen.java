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

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * LDLib2-based wind tunnel controller configuration screen.
 */
public class WindTunnelControllerLdlib2Screen {

    private final BlockPos controllerPos;
    private boolean enabled;
    private boolean spinFanBlades;
    private int targetLength = WindTunnelControllerBlockEntity.DEFAULT_LENGTH;
    private int targetAirspeed = WindTunnelControllerBlockEntity.DEFAULT_AIRSPEED;

    private Button enabledButton;
    private Button spinFanButton;
    private WindTunnelSlider lengthSlider;
    private WindTunnelSlider airspeedSlider;
    private TextField lengthField;
    private TextField airspeedField;

    public WindTunnelControllerLdlib2Screen(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        loadFromBlockEntity();
    }

    /* ---- block entity sync ---- */

    private void loadFromBlockEntity() {
        var mc = Minecraft.getInstance();
        if (mc == null) return;
        var level = mc.level;
        if (level == null) return;
        BlockPos pos = Objects.requireNonNull(controllerPos);
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WindTunnelControllerBlockEntity ctrl)) return;

        var state = level.getBlockState(pos);
        if (state.getBlock() instanceof WindTunnelControllerBlock) {
            enabled = state.getValue(Objects.requireNonNull(WindTunnelControllerBlock.ENABLED));
        }
        targetLength = ctrl.getTargetLength();
        targetAirspeed = ctrl.getTargetAirspeed();
        spinFanBlades = ctrl.shouldSpinFanBlades();
    }

    private void refreshFromBlockEntity() {
        int oldLength = targetLength;
        int oldAirspeed = targetAirspeed;
        boolean oldEnabled = enabled;
        boolean oldSpinFanBlades = spinFanBlades;
        loadFromBlockEntity();

        if (oldLength != targetLength) {
            if (lengthSlider != null && !lengthSlider.isSliding()) {
                lengthSlider.setValue(targetLength);
            }
            syncLengthField();
        }
        if (oldAirspeed != targetAirspeed) {
            if (airspeedSlider != null && !airspeedSlider.isSliding()) {
                airspeedSlider.setValue(targetAirspeed);
            }
            syncAirspeedField();
        }
        if (oldEnabled != enabled || oldSpinFanBlades != spinFanBlades) {
            updateButtonLabels();
        }
    }

    private void sendToServer() {
        PacketDistributor.sendToServer(new UpdateWindTunnelControllerPayload(
                controllerPos, targetLength, targetAirspeed, spinFanBlades, enabled));
    }

    /* ---- UI construction ---- */

    public ModularUI createUi() {
        loadFromBlockEntity();

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
        lengthSlider = new WindTunnelSlider(1, WindTunnelControllerBlockEntity.MAX_LENGTH,
                targetLength, (IntConsumer) (v -> { targetLength = v; syncLengthField(); sendToServer(); }));

        lengthField = new TextField()
                .setText(Objects.requireNonNull(Integer.toString(targetLength)))
                .setNumbersOnlyInt(1, WindTunnelControllerBlockEntity.MAX_LENGTH);
        WindTunnelLdlib2Theme.styleTextField(lengthField);
        lengthField.layout(l -> l.width(46).height(20));
        lengthField.registerValueListener(value -> {
            int parsed = parseInt(value, targetLength, 1, WindTunnelControllerBlockEntity.MAX_LENGTH);
            if (parsed != targetLength) {
                targetLength = parsed;
                if (lengthSlider != null) {
                    lengthSlider.setValue(targetLength);
                }
                sendToServer();
            }
        });

        // --- airspeed slider + field ---
        airspeedSlider = new WindTunnelSlider(0, WindTunnelControllerBlockEntity.MAX_AIRSPEED,
                targetAirspeed, (IntConsumer) (v -> { targetAirspeed = v; syncAirspeedField(); sendToServer(); }));

        airspeedField = new TextField()
                .setText(Objects.requireNonNull(Integer.toString(targetAirspeed)))
                .setNumbersOnlyInt(0, WindTunnelControllerBlockEntity.MAX_AIRSPEED);
        WindTunnelLdlib2Theme.styleTextField(airspeedField);
        airspeedField.layout(l -> l.width(46).height(20));
        airspeedField.registerValueListener(value -> {
            int parsed = parseInt(value, targetAirspeed, 0, WindTunnelControllerBlockEntity.MAX_AIRSPEED);
            if (parsed != targetAirspeed) {
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
            String text = Objects.requireNonNull(Integer.toString(targetAirspeed));
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

    public static WindTunnelLdlib2MenuScreen<WindTunnelControllerMenu> createScreen(
            WindTunnelControllerMenu menu, Inventory inventory, Component title) {
        var controls = new WindTunnelControllerLdlib2Screen(menu.getControllerPos());
        return new WindTunnelLdlib2MenuScreen<>(menu, controls.createUi(), title, controls::refreshFromBlockEntity);
    }
}
