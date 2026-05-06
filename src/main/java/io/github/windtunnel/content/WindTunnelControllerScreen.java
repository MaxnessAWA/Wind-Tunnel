package io.github.windtunnel.content;

import io.github.windtunnel.network.UpdateWindTunnelControllerPayload;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Custom controller GUI with two editable numeric parameters and an enable toggle.
 * <p>
 * The screen keeps a short-lived local edit buffer so slider dragging feels immediate even though
 * the authoritative state still lives on the server. Settings are sent optimistically via
 * {@link UpdateWindTunnelControllerPayload} and reconciled against server echo packets.
 * <p>
 * Widgets:
 * <ul>
 * <li><b>Enabled toggle</b> — Shortcut for shift-right-click; toggles the ENABLED block state.</li>
 * <li><b>Fan spin toggle</b> — Controls whether fan blades animate visually (independent of airflow).</li>
 * <li><b>Length slider + text field</b> — Target tunnel scan range (1-256 blocks).</li>
 * <li><b>Airspeed slider + text field</b> — Target airspeed (0-128 blocks/second).</li>
 * </ul>
 */
@SuppressWarnings("null")
public class WindTunnelControllerScreen extends AbstractContainerScreen<WindTunnelControllerMenu> {
    // A compact editor: one toggle plus two numeric parameters, all using the same optimistic
    // client-side editing pattern as the larger analysis screens.
    private static final int WIDTH = 196;
    private static final int HEIGHT = 156;
    private static final int SLIDER_WIDTH = 120;
    private static final int FIELD_WIDTH = 42;
    /** Ticks to wait before accepting server state reconciliation. */
    private static final int CLIENT_EDIT_GRACE_TICKS = 8;
    /** Step size for ±5 quick-adjust buttons. */
    private static final int LARGE_STEP = 5;
    // Widget Y positions (relative to screen top)
    private static final int ENABLED_BUTTON_Y = 28;
    private static final int FAN_SPIN_BUTTON_Y = 52;
    private static final int LENGTH_ROW_Y = 82;
    private static final int AIRSPEED_ROW_Y = 116;

    // ---- Optimistic local editor state ----
    private int targetLength;
    private int targetAirspeed;
    private boolean enabled;
    private boolean spinFanBlades;
    private boolean suppressUpdates;
    private boolean waitingForServerState;
    private int pendingSyncTicks;
    // Last-sent values to avoid redundant packets
    private int lastSentLength;
    private int lastSentAirspeed;
    private boolean lastSentEnabled;
    private boolean lastSentSpinFanBlades;
    // ---- Widgets ----
    private NumericSlider lengthSlider;
    private NumericSlider airspeedSlider;
    private NumericEditBox lengthField;
    private NumericEditBox airspeedField;
    private Button enabledButton;
    private Button spinFanButton;

    public WindTunnelControllerScreen(WindTunnelControllerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelY = 10000; // Hide the player inventory label
        this.targetLength = WindTunnelControllerBlockEntity.DEFAULT_LENGTH;
        this.targetAirspeed = WindTunnelControllerBlockEntity.DEFAULT_AIRSPEED;
        this.spinFanBlades = true;
        this.lastSentLength = this.targetLength;
        this.lastSentAirspeed = this.targetAirspeed;
        this.lastSentSpinFanBlades = this.spinFanBlades;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 12;
        this.titleLabelY = 10;
        // Seed the screen from the live block entity before widgets are created.
        loadControllerState();

        int left = this.leftPos;
        int top = this.topPos;

        // Enabled toggle button
        enabledButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            enabled = !enabled;
            updateEnabledButton();
            sendSettings();
        }).bounds(left + 12, top + ENABLED_BUTTON_Y, 172, 20).build());

        // Fan spin toggle button
        spinFanButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            spinFanBlades = !spinFanBlades;
            updateFanSpinButton();
            sendSettings();
        }).bounds(left + 12, top + FAN_SPIN_BUTTON_Y, 172, 20).build());

        // Length slider (1-256)
        lengthSlider = addRenderableWidget(new NumericSlider(
                left + 12,
                top + LENGTH_ROW_Y,
                SLIDER_WIDTH,
                Component.translatable("block.windtunnel.wind_tunnel_controller.target_length"),
                1,
                WindTunnelControllerBlockEntity.MAX_LENGTH,
                targetLength,
                value -> {
                    targetLength = value;
                    if (lengthField != null) {
                        lengthField.syncValue(value);
                    }
                },
                WindTunnelControllerScreen.this::sendSettings
        ));

        // Airspeed slider (0-128)
        airspeedSlider = addRenderableWidget(new NumericSlider(
                left + 12,
                top + AIRSPEED_ROW_Y,
                SLIDER_WIDTH,
                Component.translatable("block.windtunnel.wind_tunnel_controller.target_airspeed"),
                0,
                WindTunnelControllerBlockEntity.MAX_AIRSPEED,
                targetAirspeed,
                value -> {
                    targetAirspeed = value;
                    if (airspeedField != null) {
                        airspeedField.syncValue(value);
                    }
                },
                WindTunnelControllerScreen.this::sendSettings
        ));

        // Length text field (compact, next to slider)
        lengthField = addRenderableWidget(new NumericEditBox(
                left + 142,
                top + LENGTH_ROW_Y,
                targetLength,
                1,
                WindTunnelControllerBlockEntity.MAX_LENGTH,
                value -> {
                    targetLength = value;
                    if (lengthSlider != null) {
                        lengthSlider.setExternalValue(value);
                    }
                    sendSettings();
                }
        ));

        // Airspeed text field (compact, next to slider)
        airspeedField = addRenderableWidget(new NumericEditBox(
                left + 142,
                top + AIRSPEED_ROW_Y,
                targetAirspeed,
                0,
                WindTunnelControllerBlockEntity.MAX_AIRSPEED,
                value -> {
                    targetAirspeed = value;
                    if (airspeedSlider != null) {
                        airspeedSlider.setExternalValue(value);
                    }
                    sendSettings();
                }
        ));

        // Initialize widget labels
        updateEnabledButton();
        updateFanSpinButton();
        lengthField.syncValue(targetLength);
        airspeedField.syncValue(targetAirspeed);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        if (pendingSyncTicks > 0) {
            pendingSyncTicks--;
        }
        // Poll the block entity while the screen is open so redstone/server-side edits show up.
        loadControllerState();
    }

    @Override
    public void onClose() {
        // Commit any focused text field edits before closing.
        commitFocusedFields();
        super.onClose();
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // Background: warm paper-like palette with section dividers
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFE7D7BF);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFFF7F1E8);
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 1, 0xFF8A6B46);
        guiGraphics.fill(leftPos, topPos + imageHeight - 1, leftPos + imageWidth, topPos + imageHeight, 0xFF8A6B46);
        guiGraphics.fill(leftPos, topPos, leftPos + 1, topPos + imageHeight, 0xFF8A6B46);
        guiGraphics.fill(leftPos + imageWidth - 1, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF8A6B46);
        // Section dividers
        guiGraphics.fill(leftPos + 8, topPos + 24, leftPos + imageWidth - 8, topPos + 25, 0xFFD8C7B0);
        guiGraphics.fill(leftPos + 10, topPos + 76, leftPos + imageWidth - 10, topPos + 103, 0x33A7855A);
        guiGraphics.fill(leftPos + 10, topPos + 110, leftPos + imageWidth - 10, topPos + 137, 0x33A7855A);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, this.title, titleLabelX, titleLabelY, 0x404040, false);
    }

    private void loadControllerState() {
        if (minecraft == null || minecraft.level == null) {
            return;
        }

        if ((lengthSlider != null && lengthSlider.isSliding())
                || (airspeedSlider != null && airspeedSlider.isSliding())) {
            // Do not fight the user's pointer while they are dragging locally.
            return;
        }

        if (!(minecraft.level.getBlockEntity(menu.getControllerPos()) instanceof WindTunnelControllerBlockEntity controller)) {
            return;
        }

        boolean blockEnabled = minecraft.level.getBlockState(menu.getControllerPos()).getValue(WindTunnelControllerBlock.ENABLED);
        int blockLength = controller.getTargetLength();
        int blockAirspeed = controller.getTargetAirspeed();
        boolean blockSpinFanBlades = controller.shouldSpinFanBlades();

        if (waitingForServerState) {
            // After the client sends a change, give the server a few ticks to echo the accepted
            // state back before allowing passive world reads to overwrite the local buffer.
            boolean serverCaughtUp = blockEnabled == lastSentEnabled
                    && blockLength == lastSentLength
                    && blockAirspeed == lastSentAirspeed
                    && blockSpinFanBlades == lastSentSpinFanBlades;
            if (serverCaughtUp) {
                waitingForServerState = false;
                pendingSyncTicks = 0;
            } else if (pendingSyncTicks > 0) {
                return;
            } else {
                waitingForServerState = false;
            }
        }

        if (blockEnabled == enabled
                && blockLength == targetLength
                && blockAirspeed == targetAirspeed
                && blockSpinFanBlades == spinFanBlades) {
            return;
        }

        suppressUpdates = true;
        enabled = blockEnabled;
        targetLength = blockLength;
        targetAirspeed = blockAirspeed;
        spinFanBlades = blockSpinFanBlades;
        if (enabledButton != null) {
            updateEnabledButton();
        }
        if (spinFanButton != null) {
            updateFanSpinButton();
        }
        if (lengthSlider != null) {
            lengthSlider.setExternalValue(targetLength);
        }
        if (airspeedSlider != null) {
            airspeedSlider.setExternalValue(targetAirspeed);
        }
        if (lengthField != null) {
            lengthField.syncValue(targetLength);
        }
        if (airspeedField != null) {
            airspeedField.syncValue(targetAirspeed);
        }
        suppressUpdates = false;
    }

    private void sendSettings() {
        if (suppressUpdates) {
            return;
        }

        // The screen is optimistic locally, but still waits for the server to confirm the edit.
        waitingForServerState = true;
        pendingSyncTicks = CLIENT_EDIT_GRACE_TICKS;
        lastSentLength = targetLength;
        lastSentAirspeed = targetAirspeed;
        lastSentEnabled = enabled;
        lastSentSpinFanBlades = spinFanBlades;

        PacketDistributor.sendToServer(new UpdateWindTunnelControllerPayload(
                menu.getControllerPos(),
                targetLength,
                targetAirspeed,
                spinFanBlades,
                enabled
        ));
    }

    private void commitFocusedFields() {
        if (lengthField != null && lengthField.isFocused()) {
            lengthField.commitValue();
        }
        if (airspeedField != null && airspeedField.isFocused()) {
            airspeedField.commitValue();
        }
    }

    private void updateField(EditBox field, int value) {
        String text = Integer.toString(value);
        if (!text.equals(field.getValue())) {
            field.setValue(text);
        }
    }

    private void updateEnabledButton() {
        if (enabledButton == null) {
            return;
        }

        enabledButton.setMessage(Component.translatable(
                enabled
                        ? "block.windtunnel.wind_tunnel_controller.enabled"
                        : "block.windtunnel.wind_tunnel_controller.disabled"
        ));
    }

    private void updateFanSpinButton() {
        if (spinFanButton == null) {
            return;
        }

        spinFanButton.setMessage(Component.translatable(
                spinFanBlades
                        ? "block.windtunnel.wind_tunnel_controller.fan_spin_enabled"
                        : "block.windtunnel.wind_tunnel_controller.fan_spin_disabled"
        ));
    }

    private class NumericSlider extends AbstractSliderButton {
        private final Component label;
        private final int minValue;
        private final int maxValue;
        private final IntConsumer onPreviewChange;
        private final Runnable onCommit;
        private int currentValue;
        private int interactionStartValue;
        private boolean sliding;
        private boolean commitQueued;

        private NumericSlider(int x, int y, int width, Component label, int minValue, int maxValue, int initialValue,
                              IntConsumer onPreviewChange, Runnable onCommit) {
            super(x, y, width, 20, Component.empty(), normalize(initialValue, minValue, maxValue));
            this.label = label;
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.onPreviewChange = onPreviewChange;
            this.onCommit = onCommit;
            this.currentValue = initialValue;
            this.interactionStartValue = initialValue;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Integer.toString(currentValue)));
        }

        @Override
        protected void applyValue() {
            int newValue = denormalize(this.value, minValue, maxValue);
            if (newValue == currentValue) {
                return;
            }

            currentValue = newValue;
            updateMessage();
            onPreviewChange.accept(newValue);
            if (sliding) {
                // While dragging, preview immediately but batch network traffic until release.
                commitQueued = true;
            } else {
                onCommit.run();
            }
        }

        private void setExternalValue(int value) {
            this.currentValue = value;
            this.value = normalize(value, minValue, maxValue);
            updateMessage();
        }

        private boolean isSliding() {
            return sliding;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            beginInteraction();
            super.onClick(mouseX, mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            if (!sliding) {
                beginInteraction();
            }
            super.onDrag(mouseX, mouseY, dragX, dragY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            finishInteraction();
        }

        private void beginInteraction() {
            sliding = true;
            commitQueued = false;
            interactionStartValue = currentValue;
        }

        private void finishInteraction() {
            if (!sliding) {
                return;
            }

            sliding = false;
            if (commitQueued || currentValue != interactionStartValue) {
                // Only send one final commit for the drag gesture.
                onCommit.run();
            }
            commitQueued = false;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.drawString(font, label, getX(), getY() - 10, 0x404040, false);
        }
    }

    private class NumericEditBox extends EditBox {
        private final int minValue;
        private final int maxValue;
        private final IntConsumer onApply;
        private int committedValue;

        private NumericEditBox(int x, int y, int initialValue, int minValue, int maxValue, IntConsumer onApply) {
            super(font, x, y, FIELD_WIDTH, 20, Component.empty());
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.onApply = onApply;
            this.committedValue = initialValue;
            setMaxLength(3);
            setFilter(value -> value.isEmpty() || value.matches("\\d{0,3}"));
            setValue(Integer.toString(initialValue));
        }

        @Override
        public void setFocused(boolean focused) {
            boolean wasFocused = isFocused();
            super.setFocused(focused);
            if (wasFocused && !focused) {
                commitValue();
            }
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (isFocused()) {
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    commitValue();
                    setFocused(false);
                    return true;
                }

                if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_RIGHT) {
                    stepValue(stepSize());
                    return true;
                }

                if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_LEFT) {
                    stepValue(-stepSize());
                    return true;
                }
            }

            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        private void syncValue(int value) {
            committedValue = value;
            if (!isFocused()) {
                updateField(this, value);
            }
        }

        private void commitValue() {
            if (suppressUpdates) {
                return;
            }

            // Text editing is permissive while focused; clamp only when the user commits.
            if (getValue().isEmpty()) {
                // Empty input is treated as "cancel this edit" rather than zero.
                updateField(this, committedValue);
                return;
            }

            int clampedValue = Mth.clamp(Integer.parseInt(getValue()), minValue, maxValue);
            boolean textChanged = !Integer.toString(clampedValue).equals(getValue());
            boolean valueChanged = clampedValue != committedValue;

            committedValue = clampedValue;
            if (textChanged) {
                updateField(this, clampedValue);
            }
            if (valueChanged) {
                onApply.accept(clampedValue);
            }
        }

        private void stepValue(int delta) {
            int baseValue = getValue().isEmpty() ? committedValue : Integer.parseInt(getValue());
            int steppedValue = Mth.clamp(baseValue + delta, minValue, maxValue);
            committedValue = steppedValue;
            updateField(this, steppedValue);
            onApply.accept(steppedValue);
        }

        private int stepSize() {
            return Screen.hasShiftDown() ? LARGE_STEP : 1;
        }
    }

    private static double normalize(int value, int minValue, int maxValue) {
        return (double) (value - minValue) / (double) (maxValue - minValue);
    }

    private static int denormalize(double value, int minValue, int maxValue) {
        return Mth.clamp((int) Math.round(minValue + value * (maxValue - minValue)), minValue, maxValue);
    }
}
