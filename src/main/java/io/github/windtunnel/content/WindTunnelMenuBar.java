package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Toolbar row shown above the Wind Tunnel screen content, mirroring LDLib2's editor menu bar.
 * The interface-size selector re-pins the temporary GUI scale while the screen is open; the
 * player's original setting is restored when the screen closes (see
 * {@link WindTunnelLdlib2MenuScreen}).
 */
final class WindTunnelMenuBar {
    private static final int TOOLBAR_HEIGHT = 22;
    private static final int SELECTOR_WIDTH = 50;
    /** Border-less panel fill so the toolbar and selector blend with the window panel tone. */
    private static final IGuiTexture FLAT_PANEL =
            GuiTextureGroup.of(new ColorRectTexture(WindTunnelLdlib2Theme.PANEL_BACKGROUND));

    private WindTunnelMenuBar() {
    }

    /**
     * Builds the toolbar row (in-flow, top of the window) for the given screen.
     */
    static UIElement create(WindTunnelLdlib2MenuScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        int maxScale = minecraft != null
                ? minecraft.getWindow().calculateScale(0, minecraft.isEnforceUnicode())
                : 4;

        List<Integer> candidates = new ArrayList<>();
        for (int scale = 0; scale <= maxScale; scale++) {
            candidates.add(scale);
        }

        Selector<Integer> selector = new Selector<Integer>()
                .setCandidates(candidates)
                .setCandidateUIProvider(scale -> WindTunnelLdlib2Theme.label(scaleLabel(scale)))
                .setValue(Math.min(Math.max(screen.getTemporaryGuiScale(), 0), maxScale), false)
                .setOnValueChanged(scale -> screen.applyTemporaryGuiScale(scale == null ? 0 : scale))
                .selectorStyle(style -> style.closeAfterSelect(true).maxItemCount(6).showOverlay(true));

        selector.buttonIcon.setDisplay(false);
        // Collapsed state shows the menu title instead of the current scale number; the
        // candidate list still shows the actual scale options when expanded.
        selector.preview.setDisplay(false);
        Label title = WindTunnelLdlib2Theme.label(Objects.requireNonNull(
                Component.translatable("block.windtunnel.menu.gui_scale")));
        title.layout(l -> l.widthStretch().heightStretch().paddingAll(2));
        title.textStyle(style -> style
                .textAlignHorizontal(Horizontal.CENTER)
                .textAlignVertical(Vertical.CENTER));
        selector.display.addChild(title);

        // The Selector defaults to LDLib2's light sprite; restyle it to the project panel tone.
        selector.display.style(s -> s.background(FLAT_PANEL));
        selector.dialog.style(s -> s.background(WindTunnelLdlib2Theme.PANEL));

        return new UIElement()
                .layout(l -> l.widthStretch()
                        .height(TOOLBAR_HEIGHT)
                        .flexShrink(0)
                        .paddingAll(2)
                        .gapAll(2)
                        .flexDirection(FlexDirection.ROW))
                .style(s -> s.background(FLAT_PANEL))
                .addChild(selector.layout(l -> l.width(SELECTOR_WIDTH).heightStretch()));
    }

    private static Component scaleLabel(Integer scale) {
        if (scale == null) {
            return Objects.requireNonNull(Component.translatable("block.windtunnel.menu.gui_scale.auto"));
        }
        return scale == 0
                ? Objects.requireNonNull(Component.translatable("block.windtunnel.menu.gui_scale.auto"))
                : Component.literal(Integer.toString(scale));
    }
}



