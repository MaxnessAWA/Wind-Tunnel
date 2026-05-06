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
        private int requestedWidth = -1;
        private int requestedHeight = -1;
        private int appliedWidth;
        private int appliedHeight;
        private int anchorLeft;
        private int anchorTop;
        private int dragStartMouseX;
        private int dragStartMouseY;
        private int dragStartLeft;
        private int dragStartTop;
        private int dragStartWidth;
        private int dragStartHeight;
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
            int screenWidth = Math.round(screenSize.getWidth());
            int screenHeight = Math.round(screenSize.getHeight());
            restoreStoredBounds();
            if (manualSize) {
                clampAnchor(screenWidth, screenHeight);
            }
            applyRootPosition(TaffyPosition.RELATIVE, 0, 0);

            int targetWidth = manualSize && requestedWidth > 0
                    ? requestedWidth
                    : Math.max(minWidth, Math.round(screenWidth * defaultWidthRatio));
            int targetHeight = manualSize && requestedHeight > 0
                    ? requestedHeight
                    : Math.max(minHeight, Math.round(screenHeight * defaultHeightRatio));
            appliedWidth = clampWidth(targetWidth, screenWidth);
            appliedHeight = clampHeight(targetHeight, screenHeight);
            return Size.of(appliedWidth, appliedHeight);
        }

        void beginDrag(float mouseX, float mouseY, ModularUI modularUI, Interaction interaction) {
            manualSize = true;
            dragging = true;
            activeInteraction = interaction;
            anchorLeft = Math.round(modularUI.getLeftPos());
            anchorTop = Math.round(modularUI.getTopPos());
            dragStartMouseX = Math.round(mouseX);
            dragStartMouseY = Math.round(mouseY);
            dragStartLeft = anchorLeft;
            dragStartTop = anchorTop;
            dragStartWidth = currentWidth(modularUI);
            dragStartHeight = currentHeight(modularUI);
            requestedWidth = dragStartWidth;
            requestedHeight = dragStartHeight;
            reinitialize(modularUI);
        }

        void updateDrag(float mouseX, float mouseY, ModularUI modularUI) {
            int dx = Math.round(mouseX) - dragStartMouseX;
            int dy = Math.round(mouseY) - dragStartMouseY;
            if (activeInteraction == Interaction.MOVE) {
                moveWindow(dx, dy, modularUI.getScreenWidth(), modularUI.getScreenHeight());
            } else {
                resizeWindow(dx, dy, modularUI.getScreenWidth(), modularUI.getScreenHeight(), activeInteraction);
            }
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
            return anchorLeft;
        }

        int anchorTop() {
            return anchorTop;
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
            return localY < MOVE_BAR_HEIGHT ? Interaction.MOVE : Interaction.NONE;
        }

        private void moveWindow(int dx, int dy, int screenWidth, int screenHeight) {
            requestedWidth = dragStartWidth;
            requestedHeight = dragStartHeight;
            int maxLeft = Math.max(SCREEN_MARGIN, screenWidth - dragStartWidth - SCREEN_MARGIN);
            int maxTop = Math.max(SCREEN_MARGIN, screenHeight - dragStartHeight - SCREEN_MARGIN);
            anchorLeft = Mth.clamp(dragStartLeft + dx, SCREEN_MARGIN, maxLeft);
            anchorTop = Mth.clamp(dragStartTop + dy, SCREEN_MARGIN, maxTop);
        }

        private void resizeWindow(int dx, int dy, int screenWidth, int screenHeight, Interaction interaction) {
            int left = dragStartLeft;
            int top = dragStartTop;
            int right = dragStartLeft + dragStartWidth;
            int bottom = dragStartTop + dragStartHeight;
            int minLeft = SCREEN_MARGIN;
            int minTop = SCREEN_MARGIN;
            int maxRight = screenWidth - SCREEN_MARGIN;
            int maxBottom = screenHeight - SCREEN_MARGIN;

            if (interaction.left) {
                left = Mth.clamp(dragStartLeft + dx, minLeft, right - minWidth);
            }
            if (interaction.right) {
                right = Mth.clamp(right + dx, left + minWidth, maxRight);
            }
            if (interaction.top) {
                top = Mth.clamp(dragStartTop + dy, minTop, bottom - minHeight);
            }
            if (interaction.bottom) {
                bottom = Mth.clamp(bottom + dy, top + minHeight, maxBottom);
            }

            anchorLeft = left;
            anchorTop = top;
            requestedWidth = right - left;
            requestedHeight = bottom - top;
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
                screen.relayoutModularUi(anchorLeft, anchorTop);
            } else {
                modularUI.init(modularUI.getScreenWidth(), modularUI.getScreenHeight());
            }
        }

        private void clampAnchor(int screenWidth, int screenHeight) {
            int maxLeft = Math.max(SCREEN_MARGIN, screenWidth - minWidth - SCREEN_MARGIN);
            int maxTop = Math.max(SCREEN_MARGIN, screenHeight - minHeight - SCREEN_MARGIN);
            anchorLeft = Mth.clamp(anchorLeft, SCREEN_MARGIN, maxLeft);
            anchorTop = Mth.clamp(anchorTop, SCREEN_MARGIN, maxTop);
        }

        private int clampWidth(int width, int screenWidth) {
            int maxWidth = manualSize
                    ? Math.max(minWidth, screenWidth - anchorLeft - SCREEN_MARGIN)
                    : Math.max(minWidth, screenWidth - SCREEN_MARGIN * 2);
            return Mth.clamp(width, minWidth, maxWidth);
        }

        private int clampHeight(int height, int screenHeight) {
            int maxHeight = manualSize
                    ? Math.max(minHeight, screenHeight - anchorTop - SCREEN_MARGIN)
                    : Math.max(minHeight, screenHeight - SCREEN_MARGIN * 2);
            return Mth.clamp(height, minHeight, maxHeight);
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
            anchorLeft = storedBounds.left;
            anchorTop = storedBounds.top;
            requestedWidth = storedBounds.width;
            requestedHeight = storedBounds.height;
        }

        private void storeBounds() {
            if (storageKey.isEmpty() || !manualSize) {
                return;
            }
            STORED_BOUNDS.put(storageKey, new StoredBounds(anchorLeft, anchorTop, appliedWidth, appliedHeight));
        }
    }

    private record StoredBounds(int left, int top, int width, int height) {
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
