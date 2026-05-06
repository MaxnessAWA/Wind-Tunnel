package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.network.chat.Component;

import java.util.Objects;

final class WindTunnelLdlib2Theme {

    static final int PANEL_BACKGROUND = 0xFFF7F0DD;
    static final int SECTION_LINE = 0xFFD8C7B0;
    static final int TEXT = 0xFF4F5257;
    static final int LINE = 0xFF2E3032;
    static final int LINE_SHADOW = 0xFF696965;
    static final int BUTTON = 0xFF6D7177;
    static final int BUTTON_DULL = 0xFFB5B1A8;
    static final int BUTTON_PRESSED = 0xFF5D6066;
    static final int BUTTON_TEXT = TEXT;
    static final int BUTTON_SELECTED_TEXT = 0xFFFFF7E8;
    static final int DISABLED_TEXT = 0xFF8B8378;
    static final int DISABLED = 0xFFE1D6C2;

    static final IGuiTexture PANEL = GuiTextureGroup.of(
            new ColorRectTexture(PANEL_BACKGROUND),
            new ColorBorderTexture(-1, SECTION_LINE));
    static final IGuiTexture FIELD = GuiTextureGroup.of(
            new ColorRectTexture(0xFFF2E8D2),
            new ColorBorderTexture(-1, SECTION_LINE));
    static final IGuiTexture FIELD_FOCUS = new ColorBorderTexture(-1, BUTTON);

    static final IGuiTexture BUTTON_BASE = buttonTexture(0xFFEDE1CA, 0xFFCAB89D);
    static final IGuiTexture BUTTON_HOVER = buttonTexture(0xFFE7D6B8, 0xFFB99E78);
    static final IGuiTexture BUTTON_DOWN = buttonTexture(0xFFD7C19C, 0xFFA8875D);
    static final IGuiTexture BUTTON_SELECTED = buttonTexture(BUTTON, 0xFF4F5257);
    static final IGuiTexture BUTTON_SELECTED_HOVER = buttonTexture(0xFF777B82, 0xFF4F5257);
    static final IGuiTexture BUTTON_SELECTED_DOWN = buttonTexture(BUTTON_PRESSED, 0xFF4A4D52);
    static final IGuiTexture BUTTON_DISABLED = buttonTexture(DISABLED, 0xFFCBBFA9);

    private WindTunnelLdlib2Theme() {
    }

    static Button styleButton(Button button) {
        button.textStyle(style -> style.textColor(BUTTON_TEXT).textShadow(false));
        button.buttonStyle(style -> style.baseTexture(Objects.requireNonNull(BUTTON_BASE))
                .hoverTexture(Objects.requireNonNull(BUTTON_HOVER))
                .pressedTexture(Objects.requireNonNull(BUTTON_DOWN)));
        return button;
    }

    static Label label(Component text) {
        return styleLabel(new Label().setValue(text));
    }

    static Label styleLabel(Label label) {
        label.textStyle(style -> style.textColor(TEXT).textShadow(false));
        return label;
    }

    static TextField styleTextField(TextField field) {
        field.style(style -> style.background(Objects.requireNonNull(FIELD)));
        field.textFieldStyle(style -> style.textColor(TEXT)
                .cursorColor(LINE)
                .textShadow(false)
                .focusOverlay(Objects.requireNonNull(FIELD_FOCUS)));
        return field;
    }

    private static IGuiTexture buttonTexture(int fill, int border) {
        return GuiTextureGroup.of(new ColorRectTexture(fill), new ColorBorderTexture(-1, border));
    }
}
