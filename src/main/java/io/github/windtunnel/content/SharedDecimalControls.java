package io.github.windtunnel.content;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * Shared decimal widgets used by the large configuration screens.
 * Both screens need identical preview-vs-commit slider behavior and the same
 * fixed precision text field clamping rules, so the logic lives here instead
 * of being duplicated in each screen class.
 */
public final class SharedDecimalControls {
    private SharedDecimalControls() {
    }

    public static void syncField(EditBox field, double value) {
        String text = formatValue(value);
        if (!text.equals(field.getValue())) {
            field.setValue(text);
        }
    }

    public static String formatValue(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static double normalize(double value, double min, double max) {
        return (value - min) / (max - min);
    }

    private static double denormalize(double value, double min, double max) {
        double scaled = min + value * (max - min);
        return Math.round(scaled * 100.0D) / 100.0D;
    }

    public static final class DecimalSlider extends AbstractSliderButton {
        private final Font font;
        private final int labelColor;
        private final Component label;
        private final double minValue;
        private final double maxValue;
        private final DoubleConsumer onPreviewChange;
        private final Runnable onCommit;
        private double currentValue;
        private double interactionStartValue;
        private boolean sliding;
        private boolean commitQueued;

        public DecimalSlider(Font font, int labelColor, int x, int y, int width, Component label,
                             double minValue, double maxValue, double initialValue,
                             DoubleConsumer onPreviewChange, Runnable onCommit) {
            super(x, y, width, 20, Component.empty(), normalize(initialValue, minValue, maxValue));
            this.font = font;
            this.labelColor = labelColor;
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
            setMessage(Objects.requireNonNull(Component.literal(
                    Objects.requireNonNull(formatValue(currentValue)))));
        }

        @Override
        protected void applyValue() {
            double newValue = denormalize(this.value, minValue, maxValue);
            if (Math.abs(newValue - currentValue) <= 1.0E-4D) {
                return;
            }

            currentValue = newValue;
            updateMessage();
            onPreviewChange.accept(newValue);
            if (sliding) {
                commitQueued = true;
            } else {
                onCommit.run();
            }
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            sliding = true;
            commitQueued = false;
            interactionStartValue = currentValue;
            super.onClick(mouseX, mouseY);
        }

        @Override
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            if (!sliding) {
                sliding = true;
                interactionStartValue = currentValue;
            }
            super.onDrag(mouseX, mouseY, dragX, dragY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            if (sliding && (commitQueued || Math.abs(currentValue - interactionStartValue) > 1.0E-4D)) {
                onCommit.run();
            }
            sliding = false;
            commitQueued = false;
        }

        @Override
        public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.drawString(Objects.requireNonNull(font), Objects.requireNonNull(label), getX(), getY() - 10, labelColor, false);
        }

        public void setExternalValue(double value) {
            currentValue = value;
            this.value = normalize(value, minValue, maxValue);
            updateMessage();
        }

        public boolean isSliding() {
            return sliding;
        }
    }

    public static final class DecimalEditBox extends EditBox {
        private final double minValue;
        private final double maxValue;
        private final DoubleConsumer onApply;
        private final BooleanSupplier suppressUpdates;

        public DecimalEditBox(Font font, int x, int y, int width, int height, double initialValue,
                              double minValue, double maxValue, DoubleConsumer onApply,
                              BooleanSupplier suppressUpdates) {
            super(font, x, y, width, height, Component.empty());
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.onApply = onApply;
            this.suppressUpdates = suppressUpdates;
            setMaxLength(8);
            setFilter(value -> value.isEmpty() || value.matches("-?\\d{0,3}(\\.\\d{0,2})?"));
            setValue(Objects.requireNonNull(formatValue(initialValue)));
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
            if (isFocused() && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
                commitValue();
                setFocused(false);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        public void commitValue() {
            if (suppressUpdates.getAsBoolean() || getValue().isEmpty() || "-".equals(getValue())) {
                return;
            }
            double parsed = Mth.clamp(Double.parseDouble(getValue()), minValue, maxValue);
            String clamped = formatValue(parsed);
            if (!clamped.equals(getValue())) {
                setValue(clamped);
            }
            onApply.accept(parsed);
        }
    }
}
