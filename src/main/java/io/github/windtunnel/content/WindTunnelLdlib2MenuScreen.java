package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import org.jetbrains.annotations.NotNull;

/**
 * LDLib2 screen wrapper that still participates in Minecraft's menu lifecycle.
 */
public class WindTunnelLdlib2MenuScreen<M extends AbstractContainerMenu> extends ModularUIScreen implements MenuAccess<M> {
    private static final Field MODULAR_UI_LEFT_POS = modularUiField("leftPos");
    private static final Field MODULAR_UI_TOP_POS = modularUiField("topPos");

    private final M menu;
    private final Runnable tickHandler;
    private final WindTunnelDiagramReceiver diagramReceiver;
    private final WindTunnelResizableUi.SizeState sizeState;

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
        super.init();
        if (sizeState != null && sizeState.hasManualBounds()) {
            setModularUiPosition(sizeState.anchorLeft(), sizeState.anchorTop());
            this.leftPos = sizeState.anchorLeft();
            this.topPos = sizeState.anchorTop();
            modularUI.ui.rootElement.clearLayoutCache();
        }
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
