package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.math.Size;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared resize support for LDLib2 menu roots.
 */
final class WindTunnelResizableUi {
    private static final int SCREEN_MARGIN = 8;
    private static final int EDGE_SIZE = 5;
    private static final int CORNER_SIZE = 14;
    private static final int MOVE_BAR_HEIGHT = 18;
    /** Top-left strip width reserved for the menu bar so it does not conflict with window dragging. */
    private static final int MENU_BAR_ZONE_WIDTH = 150;
    private static final Map<String, StoredBounds> STORED_BOUNDS = new HashMap<>();

    private WindTunnelResizableUi() {
    }

    static SizeState sizeState(int minWidth, int minHeight, float defaultWidthRatio, float defaultHeightRatio) {
        return sizeState("", minWidth, minHeight, defaultWidthRatio, defaultHeightRatio);
    }

    static SizeState sizeState(String storageKey, int minWidth, int minHeight, float defaultWidthRatio, float defaultHeightRatio) {
        return new SizeState(storageKey, minWidth, minHeight, defaultWidthRatio, defaultHeightRatio);
    }

    static UIElement windowChrome(SizeState sizeState) {
        return new WindowChromeElement(sizeState)
                .layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
                        .left(0)
                        .top(0)
                        .widthPercent(100)
                        .heightPercent(100))
                .style(style -> style.zIndex(1000));
    }

    static boolean mouseClicked(SizeState sizeState, ModularUI modularUI, double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        Interaction interaction = sizeState.interactionAt(modularUI, mouseX, mouseY);
        if (interaction == Interaction.NONE) {
            return false;
        }
        sizeState.beginDrag((float) mouseX, (float) mouseY, modularUI, interaction);
        return true;
    }

    static boolean mouseDragged(SizeState sizeState, ModularUI modularUI, double mouseX, double mouseY) {
        if (!sizeState.isDragging()) {
            return false;
        }
        sizeState.updateDrag((float) mouseX, (float) mouseY, modularUI);
        return true;
    }

    static boolean mouseReleased(SizeState sizeState) {
        if (!sizeState.isDragging()) {
            return false;
        }
        sizeState.endDrag();
        return true;
    }

    static void syncCursor(SizeState sizeState, ModularUI modularUI, double mouseX, double mouseY) {
        Interaction interaction = sizeState.isDragging()
                ? sizeState.activeInteraction()
                : sizeState.interactionAt(modularUI, mouseX, mouseY);
        CursorManager.set(cursorFor(interaction));
    }

    static void resetCursor() {
        CursorManager.set(CursorType.DEFAULT);
    }

    static void setMoveCursor() {
        CursorManager.set(CursorType.MOVE);
    }

    private static CursorType cursorFor(Interaction interaction) {
        return switch (interaction) {
            case LEFT, RIGHT -> CursorType.RESIZE_HORIZONTAL;
            case TOP, BOTTOM -> CursorType.RESIZE_VERTICAL;
            case TOP_LEFT, BOTTOM_RIGHT -> CursorType.RESIZE_NWSE;
            case TOP_RIGHT, BOTTOM_LEFT -> CursorType.RESIZE_NESW;
            case MOVE -> CursorType.MOVE;
            case NONE -> CursorType.DEFAULT;
        };
    }

    static final class SizeState implements UI.DynamicSizeProvider {
        private final int minWidth;
        private final int minHeight;
        private final float defaultWidthRatio;
        private final float defaultHeightRatio;
        private final String storageKey;
        private UIElement root;
        private boolean manualSize;
        private int requestedWidthPhysical = -1;
        private int requestedHeightPhysical = -1;
        private int appliedLeft;
        private int appliedTop;
        private int appliedWidth;
        private int appliedHeight;
        private int appliedWidthPhysical;
        private int appliedHeightPhysical;
        private int anchorLeftPhysical;
        private int anchorTopPhysical;
        private int dragStartMouseXPhysical;
        private int dragStartMouseYPhysical;
        private int dragStartLeftPhysical;
        private int dragStartTopPhysical;
        private int dragStartWidthPhysical;
        private int dragStartHeightPhysical;
        private boolean dragging;
        private Interaction activeInteraction = Interaction.NONE;

        private SizeState(String storageKey, int minWidth, int minHeight, float defaultWidthRatio, float defaultHeightRatio) {
            this.storageKey = storageKey;
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.defaultWidthRatio = defaultWidthRatio;
            this.defaultHeightRatio = defaultHeightRatio;
        }

        SizeState attach(UIElement root) {
            this.root = root;
            return this;
        }

        @Override
        public Size apply(Size screenSize) {
            WindowMetrics metrics = WindowMetrics.capture(screenSize);
            restoreStoredBounds();
            if (manualSize) {
                clampAnchor(metrics);
            }
            applyRootPosition(TaffyPosition.RELATIVE, 0, 0);

            int minWidthPhysical = metrics.logicalToPhysicalWidth(minWidth);
            int minHeightPhysical = metrics.logicalToPhysicalHeight(minHeight);
            int targetWidthPhysical = manualSize && requestedWidthPhysical > 0
                    ? requestedWidthPhysical
                    : Math.max(minWidthPhysical, Math.round(metrics.screenWidthPhysical * defaultWidthRatio));
            int targetHeightPhysical = manualSize && requestedHeightPhysical > 0
                    ? requestedHeightPhysical
                    : Math.max(minHeightPhysical, Math.round(metrics.screenHeightPhysical * defaultHeightRatio));
            appliedWidthPhysical = clampWidth(targetWidthPhysical, metrics);
            appliedHeightPhysical = clampHeight(targetHeightPhysical, metrics);
            appliedWidth = Math.max(minWidth, metrics.physicalToLogicalWidth(appliedWidthPhysical));
            appliedHeight = Math.max(minHeight, metrics.physicalToLogicalHeight(appliedHeightPhysical));
            appliedLeft = manualSize ? metrics.physicalToLogicalX(anchorLeftPhysical) : 0;
            appliedTop = manualSize ? metrics.physicalToLogicalY(anchorTopPhysical) : 0;
            return Size.of(appliedWidth, appliedHeight);
        }

        void beginDrag(float mouseX, float mouseY, ModularUI modularUI, Interaction interaction) {
            WindowMetrics metrics = WindowMetrics.capture(modularUI.getScreenWidth(), modularUI.getScreenHeight());
            manualSize = true;
            dragging = true;
            activeInteraction = interaction;
            anchorLeftPhysical = metrics.logicalToPhysicalX(Math.round(modularUI.getLeftPos()));
            anchorTopPhysical = metrics.logicalToPhysicalY(Math.round(modularUI.getTopPos()));
            dragStartMouseXPhysical = metrics.logicalToPhysicalX(Math.round(mouseX));
            dragStartMouseYPhysical = metrics.logicalToPhysicalY(Math.round(mouseY));
            dragStartLeftPhysical = anchorLeftPhysical;
            dragStartTopPhysical = anchorTopPhysical;
            dragStartWidthPhysical = metrics.logicalToPhysicalWidth(currentWidth(modularUI));
            dragStartHeightPhysical = metrics.logicalToPhysicalHeight(currentHeight(modularUI));
            requestedWidthPhysical = dragStartWidthPhysical;
            requestedHeightPhysical = dragStartHeightPhysical;
            appliedLeft = metrics.physicalToLogicalX(anchorLeftPhysical);
            appliedTop = metrics.physicalToLogicalY(anchorTopPhysical);
            reinitialize(modularUI);
        }

        void updateDrag(float mouseX, float mouseY, ModularUI modularUI) {
            WindowMetrics metrics = WindowMetrics.capture(modularUI.getScreenWidth(), modularUI.getScreenHeight());
            int mouseXPhysical = metrics.logicalToPhysicalX(Math.round(mouseX));
            int mouseYPhysical = metrics.logicalToPhysicalY(Math.round(mouseY));
            int dx = mouseXPhysical - dragStartMouseXPhysical;
            int dy = mouseYPhysical - dragStartMouseYPhysical;
            if (activeInteraction == Interaction.MOVE) {
                moveWindow(dx, dy, metrics);
            } else {
                resizeWindow(dx, dy, metrics, activeInteraction);
            }
            appliedLeft = metrics.physicalToLogicalX(anchorLeftPhysical);
            appliedTop = metrics.physicalToLogicalY(anchorTopPhysical);
            reinitialize(modularUI);
            storeBounds();
        }

        void endDrag() {
            dragging = false;
            activeInteraction = Interaction.NONE;
        }

        boolean isDragging() {
            return dragging;
        }

        boolean hasManualBounds() {
            return manualSize;
        }

        int anchorLeft() {
            return appliedLeft;
        }

        int anchorTop() {
            return appliedTop;
        }

        Interaction activeInteraction() {
            return activeInteraction;
        }

        Interaction interactionAt(ModularUI modularUI, double mouseX, double mouseY) {
            return interactionAt(modularUI.getLeftPos(), modularUI.getTopPos(), currentWidth(modularUI), currentHeight(modularUI), mouseX, mouseY);
        }

        Interaction interactionAt(double leftPos, double topPos, double width, double height, double mouseX, double mouseY) {
            double localX = mouseX - leftPos;
            double localY = mouseY - topPos;
            if (localX < 0.0D || localY < 0.0D || localX >= width || localY >= height) {
                return Interaction.NONE;
            }

            boolean left = localX < EDGE_SIZE;
            boolean right = localX >= width - EDGE_SIZE;
            boolean top = localY < EDGE_SIZE;
            boolean bottom = localY >= height - EDGE_SIZE;
            boolean cornerLeft = localX < CORNER_SIZE;
            boolean cornerRight = localX >= width - CORNER_SIZE;
            boolean cornerTop = localY < CORNER_SIZE;
            boolean cornerBottom = localY >= height - CORNER_SIZE;

            if (cornerLeft && cornerTop) {
                return Interaction.TOP_LEFT;
            }
            if (cornerRight && cornerTop) {
                return Interaction.TOP_RIGHT;
            }
            if (cornerLeft && cornerBottom) {
                return Interaction.BOTTOM_LEFT;
            }
            if (cornerRight && cornerBottom) {
                return Interaction.BOTTOM_RIGHT;
            }
            if (left) {
                return Interaction.LEFT;
            }
            if (right) {
                return Interaction.RIGHT;
            }
            if (top) {
                return Interaction.TOP;
            }
            if (bottom) {
                return Interaction.BOTTOM;
            }
            if (localY < MOVE_BAR_HEIGHT) {
                // The top-left strip belongs to the menu bar; the rest of the bar still moves.
                return localX < MENU_BAR_ZONE_WIDTH ? Interaction.NONE : Interaction.MOVE;
            }
            return Interaction.NONE;
        }

        private void moveWindow(int dx, int dy, WindowMetrics metrics) {
            int marginX = metrics.logicalToPhysicalWidth(SCREEN_MARGIN);
            int marginY = metrics.logicalToPhysicalHeight(SCREEN_MARGIN);
            requestedWidthPhysical = dragStartWidthPhysical;
            requestedHeightPhysical = dragStartHeightPhysical;
            int maxLeft = Math.max(marginX, metrics.screenWidthPhysical - dragStartWidthPhysical - marginX);
            int maxTop = Math.max(marginY, metrics.screenHeightPhysical - dragStartHeightPhysical - marginY);
            anchorLeftPhysical = Mth.clamp(dragStartLeftPhysical + dx, marginX, maxLeft);
            anchorTopPhysical = Mth.clamp(dragStartTopPhysical + dy, marginY, maxTop);
        }

        private void resizeWindow(int dx, int dy, WindowMetrics metrics, Interaction interaction) {
            int marginX = metrics.logicalToPhysicalWidth(SCREEN_MARGIN);
            int marginY = metrics.logicalToPhysicalHeight(SCREEN_MARGIN);
            int minWidthPhysical = metrics.logicalToPhysicalWidth(minWidth);
            int minHeightPhysical = metrics.logicalToPhysicalHeight(minHeight);
            int left = dragStartLeftPhysical;
            int top = dragStartTopPhysical;
            int right = dragStartLeftPhysical + dragStartWidthPhysical;
            int bottom = dragStartTopPhysical + dragStartHeightPhysical;
            int minLeft = marginX;
            int minTop = marginY;
            int maxRight = metrics.screenWidthPhysical - marginX;
            int maxBottom = metrics.screenHeightPhysical - marginY;

            if (interaction.left) {
                left = Mth.clamp(dragStartLeftPhysical + dx, minLeft, right - minWidthPhysical);
            }
            if (interaction.right) {
                right = Mth.clamp(right + dx, left + minWidthPhysical, maxRight);
            }
            if (interaction.top) {
                top = Mth.clamp(dragStartTopPhysical + dy, minTop, bottom - minHeightPhysical);
            }
            if (interaction.bottom) {
                bottom = Mth.clamp(bottom + dy, top + minHeightPhysical, maxBottom);
            }

            anchorLeftPhysical = left;
            anchorTopPhysical = top;
            requestedWidthPhysical = right - left;
            requestedHeightPhysical = bottom - top;
        }

        private int currentWidth(ModularUI modularUI) {
            if (appliedWidth > 0) {
                return appliedWidth;
            }
            return Math.max(minWidth, Math.round(modularUI.getWidth()));
        }

        private int currentHeight(ModularUI modularUI) {
            if (appliedHeight > 0) {
                return appliedHeight;
            }
            return Math.max(minHeight, Math.round(modularUI.getHeight()));
        }

        private void reinitialize(ModularUI modularUI) {
            if (modularUI.getScreen() instanceof WindTunnelLdlib2MenuScreen<?> screen) {
                screen.relayoutModularUi(appliedLeft, appliedTop);
            } else {
                modularUI.init(modularUI.getScreenWidth(), modularUI.getScreenHeight());
            }
        }

        private void clampAnchor(WindowMetrics metrics) {
            int marginX = metrics.logicalToPhysicalWidth(SCREEN_MARGIN);
            int marginY = metrics.logicalToPhysicalHeight(SCREEN_MARGIN);
            int minWidthPhysical = metrics.logicalToPhysicalWidth(minWidth);
            int minHeightPhysical = metrics.logicalToPhysicalHeight(minHeight);
            int maxLeft = Math.max(marginX, metrics.screenWidthPhysical - minWidthPhysical - marginX);
            int maxTop = Math.max(marginY, metrics.screenHeightPhysical - minHeightPhysical - marginY);
            anchorLeftPhysical = Mth.clamp(anchorLeftPhysical, marginX, maxLeft);
            anchorTopPhysical = Mth.clamp(anchorTopPhysical, marginY, maxTop);
        }

        private int clampWidth(int width, WindowMetrics metrics) {
            int marginX = metrics.logicalToPhysicalWidth(SCREEN_MARGIN);
            int minWidthPhysical = metrics.logicalToPhysicalWidth(minWidth);
            int maxWidth = manualSize
                    ? Math.max(minWidthPhysical, metrics.screenWidthPhysical - anchorLeftPhysical - marginX)
                    : Math.max(minWidthPhysical, metrics.screenWidthPhysical - marginX * 2);
            return Mth.clamp(width, minWidthPhysical, maxWidth);
        }

        private int clampHeight(int height, WindowMetrics metrics) {
            int marginY = metrics.logicalToPhysicalHeight(SCREEN_MARGIN);
            int minHeightPhysical = metrics.logicalToPhysicalHeight(minHeight);
            int maxHeight = manualSize
                    ? Math.max(minHeightPhysical, metrics.screenHeightPhysical - anchorTopPhysical - marginY)
                    : Math.max(minHeightPhysical, metrics.screenHeightPhysical - marginY * 2);
            return Mth.clamp(height, minHeightPhysical, maxHeight);
        }

        private void applyRootPosition(TaffyPosition position, int left, int top) {
            if (root != null) {
                root.layout(layout -> layout.positionType(position).left(left).top(top));
            }
        }

        private void restoreStoredBounds() {
            if (manualSize || storageKey.isEmpty()) {
                return;
            }
            StoredBounds storedBounds = STORED_BOUNDS.get(storageKey);
            if (storedBounds == null) {
                return;
            }
            manualSize = true;
            anchorLeftPhysical = storedBounds.left;
            anchorTopPhysical = storedBounds.top;
            requestedWidthPhysical = storedBounds.width;
            requestedHeightPhysical = storedBounds.height;
        }

        private void storeBounds() {
            if (storageKey.isEmpty() || !manualSize) {
                return;
            }
            STORED_BOUNDS.put(storageKey, new StoredBounds(anchorLeftPhysical, anchorTopPhysical, appliedWidthPhysical, appliedHeightPhysical));
        }
    }

    private record StoredBounds(int left, int top, int width, int height) {
    }

    private record WindowMetrics(int screenWidthLogical, int screenHeightLogical, int screenWidthPhysical,
                                 int screenHeightPhysical, double scaleX, double scaleY) {
        private static WindowMetrics capture(Size screenSize) {
            return capture(Math.round(screenSize.getWidth()), Math.round(screenSize.getHeight()));
        }

        private static WindowMetrics capture(int screenWidthLogical, int screenHeightLogical) {
            Minecraft minecraft = Minecraft.getInstance();
            int screenWidthPhysical = screenWidthLogical;
            int screenHeightPhysical = screenHeightLogical;
            if (minecraft != null) {
                var window = minecraft.getWindow();
                screenWidthPhysical = Math.max(1, window.getWidth());
                screenHeightPhysical = Math.max(1, window.getHeight());
            }
            double scaleX = screenWidthLogical <= 0 ? 1.0D : screenWidthPhysical / (double) screenWidthLogical;
            double scaleY = screenHeightLogical <= 0 ? 1.0D : screenHeightPhysical / (double) screenHeightLogical;
            return new WindowMetrics(
                    Math.max(1, screenWidthLogical),
                    Math.max(1, screenHeightLogical),
                    screenWidthPhysical,
                    screenHeightPhysical,
                    scaleX,
                    scaleY);
        }

        private int logicalToPhysicalX(int logicalX) {
            return logicalToPhysicalWidth(logicalX);
        }

        private int logicalToPhysicalY(int logicalY) {
            return logicalToPhysicalHeight(logicalY);
        }

        private int logicalToPhysicalWidth(int logicalWidth) {
            return Math.max(1, (int) Math.round(logicalWidth * scaleX));
        }

        private int logicalToPhysicalHeight(int logicalHeight) {
            return Math.max(1, (int) Math.round(logicalHeight * scaleY));
        }

        private int physicalToLogicalX(int physicalX) {
            return physicalToLogicalWidth(physicalX);
        }

        private int physicalToLogicalY(int physicalY) {
            return physicalToLogicalHeight(physicalY);
        }

        private int physicalToLogicalWidth(int physicalWidth) {
            return Math.max(1, (int) Math.round(physicalWidth / scaleX));
        }

        private int physicalToLogicalHeight(int physicalHeight) {
            return Math.max(1, (int) Math.round(physicalHeight / scaleY));
        }
    }

    private enum Interaction {
        NONE(false, false, false, false),
        MOVE(false, false, false, false),
        LEFT(true, false, false, false),
        RIGHT(false, true, false, false),
        TOP(false, false, true, false),
        BOTTOM(false, false, false, true),
        TOP_LEFT(true, false, true, false),
        TOP_RIGHT(false, true, true, false),
        BOTTOM_LEFT(true, false, false, true),
        BOTTOM_RIGHT(false, true, false, true);

        private final boolean left;
        private final boolean right;
        private final boolean top;
        private final boolean bottom;

        Interaction(boolean left, boolean right, boolean top, boolean bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }
    }

    private static final class WindowChromeElement extends UIElement {
        private final SizeState sizeState;

        private WindowChromeElement(SizeState sizeState) {
            this.sizeState = sizeState;
            addEventListener(UIEvents.MOUSE_DOWN, this::handleMouseDown);
            addEventListener(UIEvents.MOUSE_MOVE, this::handleMouseMove);
            addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::handleMouseMove, true);
            addEventListener(UIEvents.MOUSE_UP, this::handleMouseUp);
            addEventListener(UIEvents.DRAG_END, this::handleMouseUp, true);
        }

        @Override
        public boolean isIntersectWithPoint(double localX, double localY) {
            return sizeState.interactionAt(getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), localX, localY) != Interaction.NONE;
        }

        @Override
        public void drawBackgroundAdditional(@NotNull GUIContext guiContext) {
            Interaction interaction = sizeState.isDragging()
                    ? sizeState.activeInteraction()
                    : sizeState.interactionAt(getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), guiContext.mouseX, guiContext.mouseY);
            if (interaction == Interaction.NONE) {
                return;
            }
            GuiGraphics graphics = guiContext.graphics;
            int x = Math.round(getPositionX());
            int y = Math.round(getPositionY());
            int w = Math.round(getSizeWidth());
            int h = Math.round(getSizeHeight());
            int base = isHover() || sizeState.isDragging() ? WindTunnelLdlib2Theme.BUTTON : WindTunnelLdlib2Theme.BUTTON_DULL;
            if (interaction == Interaction.MOVE) {
                graphics.fill(x + 1, y + 1, x + w - 1, y + 2, base);
                return;
            }
            if (interaction.left) {
                graphics.fill(x, y, x + 2, y + h, base);
            }
            if (interaction.right) {
                graphics.fill(x + w - 2, y, x + w, y + h, base);
            }
            if (interaction.top) {
                graphics.fill(x, y, x + w, y + 2, base);
            }
            if (interaction.bottom) {
                graphics.fill(x, y + h - 2, x + w, y + h, base);
            }
        }

        private void handleMouseDown(UIEvent event) {
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return;
            }
            ModularUI modularUI = getModularUI();
            if (modularUI == null) {
                return;
            }
            Interaction interaction = sizeState.interactionAt(getPositionX(), getPositionY(), getSizeWidth(), getSizeHeight(), event.x, event.y);
            if (interaction == Interaction.NONE) {
                return;
            }
            sizeState.beginDrag(event.x, event.y, modularUI, interaction);
            IGuiTexture emptyTex = IGuiTexture.EMPTY;
            startDrag("", Objects.requireNonNull(emptyTex));
            event.stopPropagation();
        }

        private void handleMouseMove(UIEvent event) {
            if (!sizeState.isDragging()) {
                return;
            }
            ModularUI modularUI = getModularUI();
            if (modularUI != null) {
                sizeState.updateDrag(event.x, event.y, modularUI);
            }
            event.stopPropagation();
        }

        private void handleMouseUp(UIEvent event) {
            if (sizeState.isDragging()) {
                sizeState.endDrag();
                event.stopPropagation();
            }
        }
    }

    private enum CursorType {
        DEFAULT(-1),
        MOVE(GLFW.GLFW_RESIZE_ALL_CURSOR),
        RESIZE_HORIZONTAL(GLFW.GLFW_HRESIZE_CURSOR),
        RESIZE_VERTICAL(GLFW.GLFW_VRESIZE_CURSOR),
        RESIZE_NWSE(GLFW.GLFW_RESIZE_NWSE_CURSOR),
        RESIZE_NESW(GLFW.GLFW_RESIZE_NESW_CURSOR);

        private final int glfwShape;

        CursorType(int glfwShape) {
            this.glfwShape = glfwShape;
        }
    }

    private static final class CursorManager {
        private static final Map<CursorType, Long> HANDLES = new EnumMap<>(CursorType.class);
        private static CursorType activeCursor = CursorType.DEFAULT;

        private CursorManager() {
        }

        private static void set(CursorType cursorType) {
            if (activeCursor == cursorType) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            long window = minecraft.getWindow().getWindow();
            if (window == 0L) {
                return;
            }
            long handle = cursorType == CursorType.DEFAULT ? 0L : HANDLES.computeIfAbsent(cursorType, CursorManager::createHandle);
            GLFW.glfwSetCursor(window, handle);
            activeCursor = cursorType;
        }

        private static long createHandle(CursorType cursorType) {
            long handle = GLFW.glfwCreateStandardCursor(cursorType.glfwShape);
            return handle == 0L ? 0L : handle;
        }
    }
}
