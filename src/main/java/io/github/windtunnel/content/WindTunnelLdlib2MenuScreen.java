package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * LDLib2 screen wrapper that still participates in Minecraft's menu lifecycle.
 */
public class WindTunnelLdlib2MenuScreen<M extends AbstractContainerMenu> extends ModularUIScreen implements MenuAccess<M> {
    private static final Field MODULAR_UI_LEFT_POS = modularUiField("leftPos");
    private static final Field MODULAR_UI_TOP_POS = modularUiField("topPos");
    /** Default temporary GUI scale pinned while a Wind Tunnel screen is open. */
    private static final int DEFAULT_TEMP_GUI_SCALE = 2;

    private final M menu;
    private final Runnable tickHandler;
    private final WindTunnelDiagramReceiver diagramReceiver;
    private final WindTunnelResizableUi.SizeState sizeState;
    /** GUI scale pinned while this screen is open; adjustable from the menu bar. */
    private int tempGuiScale = DEFAULT_TEMP_GUI_SCALE;
    /** Original GUI scale captured before this screen pinned it (null if never pinned). */
    private Integer originalGuiScale;
    private boolean guiScalePinned;
    /** Set while closing so the resize triggered by restoring the scale does not re-pin it. */
    private boolean closing;
    /** Menu bar is only attached once per screen instance. */
    private boolean menuBarAdded;

    public WindTunnelLdlib2MenuScreen(M menu, ModularUI modularUI, Component title, Runnable tickHandler) {
        this(menu, modularUI, title, tickHandler, null);
    }

    public WindTunnelLdlib2MenuScreen(
            M menu,
            ModularUI modularUI,
            Component title,
            Runnable tickHandler,
            WindTunnelDiagramReceiver diagramReceiver) {
        this(menu, modularUI, title, tickHandler, diagramReceiver, null);
    }

    public WindTunnelLdlib2MenuScreen(
            M menu,
            ModularUI modularUI,
            Component title,
            Runnable tickHandler,
            WindTunnelDiagramReceiver diagramReceiver,
            WindTunnelResizableUi.SizeState sizeState) {
        super(modularUI, title);
        this.menu = menu;
        this.tickHandler = tickHandler;
        this.diagramReceiver = diagramReceiver;
        this.sizeState = sizeState;
    }

    @Override
    public M getMenu() {
        return menu;
    }

    public WindTunnelDiagramReceiver getDiagramReceiver() {
        return diagramReceiver;
    }

    @Override
    public void init() {
        if (closing) {
            return;
        }
        if (pinTemporaryGuiScale()) {
            // resizeDisplay() re-runs init() with the new screen dimensions; skip this pass.
            return;
        }
        super.init();
        if (sizeState != null && sizeState.hasManualBounds()) {
            setModularUiPosition(sizeState.anchorLeft(), sizeState.anchorTop());
            this.leftPos = sizeState.anchorLeft();
            this.topPos = sizeState.anchorTop();
            modularUI.ui.rootElement.clearLayoutCache();
        }
        if (!menuBarAdded) {
            menuBarAdded = true;
            insertTopToolbar();
            modularUI.ui.rootElement.clearLayoutCache();
        }
    }

    /**
     * Wraps the existing window content in a content row and inserts a dedicated toolbar row
     * above it, so the menu bar is part of the layout instead of an overlay.
     */
    private void insertTopToolbar() {
        UIElement rootElement = modularUI.ui.rootElement;
        List<UIElement> existing = new ArrayList<>(rootElement.getChildren());
        rootElement.clearAllChildren();
        UIElement toolbar = Objects.requireNonNull(WindTunnelMenuBar.create(this));
        UIElement content = new UIElement()
                .layout(l -> l.widthStretch()
                        .heightStretch()
                        .flexDirection(FlexDirection.ROW)
                        .gapAll(4))
                .addChildren(existing.toArray(new UIElement[0]));
        rootElement.addChildren(toolbar, content);
        // No vertical gap so the tab header sits flush against the toolbar.
        rootElement.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(0));
    }

    @Override
    public void tick() {
        super.tick();
        modularUI.tick();
        if (tickHandler != null) {
            tickHandler.run();
        }
    }

    @Override
    public void render(@NotNull net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (sizeState != null) {
            WindTunnelResizableUi.syncCursor(sizeState, modularUI, mouseX, mouseY);
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    public void relayoutModularUi() {
        modularUI.init(width, height);
        this.leftPos = (int) modularUI.getLeftPos();
        this.topPos = (int) modularUI.getTopPos();
    }

    public void relayoutModularUi(int left, int top) {
        modularUI.init(width, height);
        setModularUiPosition(left, top);
        this.leftPos = left;
        this.topPos = top;
        modularUI.ui.rootElement.clearLayoutCache();
    }

    private static Field modularUiField(String name) {
        try {
            Field field = ModularUI.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to access ModularUI." + name, exception);
        }
    }

    private void setModularUiPosition(int left, int top) {
        try {
            MODULAR_UI_LEFT_POS.setFloat(modularUI, left);
            MODULAR_UI_TOP_POS.setFloat(modularUI, top);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to update ModularUI position", exception);
        }
    }

    /**
     * Temporarily pins Minecraft's GUI scale while this screen is open (restored on close).
     * Returns true when the scale changed and the display was resized; the caller should then
     * return immediately because the resize re-enters {@code init()} with fresh dimensions.
     */
    private boolean pinTemporaryGuiScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return false;
        }
        var guiScale = minecraft.options.guiScale();
        int current = guiScale.get();
        if (current == tempGuiScale) {
            return false;
        }
        if (originalGuiScale == null) {
            originalGuiScale = current;
        }
        guiScale.set(tempGuiScale);
        guiScalePinned = true;
        minecraft.resizeDisplay();
        return true;
    }

    /**
     * Re-pins the temporary GUI scale while this screen is open, e.g. from the menu bar.
     * The player's original setting is still restored when the screen closes.
     */
    public void applyTemporaryGuiScale(int scale) {
        int clamped = Mth.clamp(scale, 0, maxAvailableGuiScale());
        if (clamped == tempGuiScale) {
            return;
        }
        tempGuiScale = clamped;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        var guiScale = minecraft.options.guiScale();
        if (guiScale.get() != clamped) {
            guiScale.set(clamped);
            minecraft.resizeDisplay();
        }
    }

    /** Current temporary GUI scale pinned while this screen is open. */
    public int getTemporaryGuiScale() {
        return tempGuiScale;
    }

    private static int maxAvailableGuiScale() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null
                ? minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode())
                : 4;
    }

    /** Restores the GUI scale captured on open. Safe to call from {@link #removed()}. */
    private void restoreOriginalGuiScale() {
        if (!guiScalePinned || originalGuiScale == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            var guiScale = minecraft.options.guiScale();
            if (guiScale.get() != originalGuiScale) {
                guiScale.set(originalGuiScale);
                minecraft.resizeDisplay();
            }
        }
        guiScalePinned = false;
        originalGuiScale = null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (sizeState != null && WindTunnelResizableUi.mouseClicked(sizeState, modularUI, mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (sizeState != null && WindTunnelResizableUi.mouseDragged(sizeState, modularUI, mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (sizeState != null && WindTunnelResizableUi.mouseReleased(sizeState)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        closing = true;
        restoreOriginalGuiScale();
        closing = false;
        WindTunnelResizableUi.resetCursor();
        modularUI.onRemoved();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            menu.removed(minecraft.player);
        }
    }

    @Override
    public void onClose() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.closeContainer();
        }
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }

        return false;
    }
}
