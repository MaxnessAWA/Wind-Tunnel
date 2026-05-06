package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * A LDLib2-based slider widget.
 * <p>
 * Supports both {@code double} and {@code int} value modes via separate
 * constructor overloads. Integer mode fires {@link IntConsumer} callbacks
 * and rounds values to whole numbers.
 */
public class WindTunnelSlider extends UIElement {

    private static final float TRACK_HEIGHT = 4.0F;
    private static final float THUMB_RADIUS = 6.0F;
    private static final int TRACK_COLOR = WindTunnelLdlib2Theme.LINE_SHADOW;
    private static final int TRACK_FILL_COLOR = WindTunnelLdlib2Theme.LINE;
    private static final int THUMB_COLOR = WindTunnelLdlib2Theme.BUTTON_DULL;
    private static final int THUMB_HOVER_COLOR = WindTunnelLdlib2Theme.BUTTON;

    private final double minValue;
    private final double maxValue;
    private double currentValue;
    private final DoubleConsumer onValueChanged;
    private final IntConsumer onIntChanged;
    private final boolean intMode;
    private boolean dragging;

    /* ---- registration helpers (used by ldlib2 event system) ---- */

    public static UIElement create() {
        return new WindTunnelSlider(0.0, 100.0, 50.0, (DoubleConsumer) (v -> {}));
    }

    public WindTunnelSlider() {
        this(0.0, 100.0, 50.0, (DoubleConsumer) (v -> {}));
    }

    /* ---- public constructors ---- */

    /** Double-precision slider. */
    public WindTunnelSlider(double minValue, double maxValue, double initialValue,
                            DoubleConsumer onValueChanged) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.currentValue = Mth.clamp(initialValue, minValue, maxValue);
        this.onValueChanged = onValueChanged;
        this.onIntChanged = null;
        this.intMode = false;
        this.layout(l -> l.width(120).height(20));
        wireEvents();
    }

    /** Integer slider — rounds values and fires {@link IntConsumer}. */
    public WindTunnelSlider(int minValue, int maxValue, int initialValue,
                            IntConsumer onIntChanged) {
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.currentValue = Mth.clamp(initialValue, minValue, maxValue);
        this.onValueChanged = null;
        this.onIntChanged = onIntChanged;
        this.intMode = true;
        this.layout(l -> l.width(120).height(20));
        wireEvents();
    }

    private void wireEvents() {
        this.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            if (isMouseOverElement(event.x, event.y)) {
                dragging = true;
                IGuiTexture emptyTex = IGuiTexture.EMPTY;
                startDrag("", Objects.requireNonNull(emptyTex));
                updateFromMouse(event.x);
            }
        });

        this.addEventListener(UIEvents.MOUSE_MOVE, event -> {
            if (dragging) {
                updateFromMouse(event.x);
            }
        });

        this.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, event -> {
            if (dragging) {
                updateFromMouse(event.x);
            }
        }, true);

        this.addEventListener(UIEvents.MOUSE_UP, event -> {
            dragging = false;
        });

        this.addEventListener(UIEvents.DRAG_END, event -> {
            dragging = false;
        }, true);
    }

    /* ---- value access ---- */

    public double getValue() {
        return currentValue;
    }

    /** Silent set (no callback). Used for server-state reconciliation. */
    public void setValue(double value) {
        currentValue = Mth.clamp(value, minValue, maxValue);
    }

    public void setValue(int value) {
        currentValue = Mth.clamp(value, (int) minValue, (int) maxValue);
    }

    public int getIntValue() {
        return (int) Math.round(currentValue);
    }

    public boolean isSliding() {
        return dragging;
    }

    /* ---- mouse → value mapping ---- */

    private void updateFromMouse(float mouseX) {
        float w = getSizeWidth();
        if (w <= 0) return;
        // event.x is screen-space; convert to element-local.
        float localX = mouseX - getPositionX();
        double normalised = Mth.clamp(localX / w, 0.0, 1.0);
        double newValue = minValue + normalised * (maxValue - minValue);
        if (intMode || Screen.hasShiftDown()) {
            newValue = Mth.clamp(Math.round(newValue), minValue, maxValue);
        } else {
            newValue = Math.round(newValue * 100.0) / 100.0;
        }
        if (Math.abs(newValue - currentValue) > 1.0E-6) {
            currentValue = newValue;
            if (intMode && onIntChanged != null) {
                onIntChanged.accept((int) currentValue);
            } else if (!intMode && onValueChanged != null) {
                onValueChanged.accept(currentValue);
            }
        }
    }

    /* ---- rendering (element-local coordinates — GUIContext handles transform) ---- */

    @Override
    public void drawBackgroundAdditional(@NotNull GUIContext ctx) {
        if (dragging || isHover()) {
            WindTunnelResizableUi.setMoveCursor();
        }
        GuiGraphics g = ctx.graphics;
        float x = getPositionX();
        float y = getPositionY();
        float w = getSizeWidth();
        float h = getSizeHeight();
        float cy = y + h * 0.5F;

        // --- track background (centered vertically) ---
        g.fill((int) x, (int) (cy - TRACK_HEIGHT * 0.5F),
                (int) (x + w), (int) (cy + TRACK_HEIGHT * 0.5F), TRACK_COLOR);

        // --- filled portion ---
        double normalised = (currentValue - minValue) / (maxValue - minValue);
        float fillW = (float) (w * normalised);
        if (fillW > 0) {
            g.fill((int) x, (int) (cy - TRACK_HEIGHT * 0.5F),
                    (int) (x + fillW), (int) (cy + TRACK_HEIGHT * 0.5F), TRACK_FILL_COLOR);
        }

        // --- thumb (square at fill end) ---
        float thumbX = x + fillW;
        int thumbColor = isHover() ? THUMB_HOVER_COLOR : THUMB_COLOR;
        g.fill((int) (thumbX - THUMB_RADIUS), (int) (cy - THUMB_RADIUS),
                (int) (thumbX + THUMB_RADIUS), (int) (cy + THUMB_RADIUS), thumbColor);
    }
}
