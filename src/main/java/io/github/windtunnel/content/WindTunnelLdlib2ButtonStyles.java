package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;

import java.util.Objects;

final class WindTunnelLdlib2ButtonStyles {

    private WindTunnelLdlib2ButtonStyles() {
    }

    static void setDirectionButtonState(Button button, boolean selected, boolean enabled) {
        button.setActive(enabled && !selected);
        button.buttonStyle(style -> {
            if (selected) {
                style.baseTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_SELECTED))
                        .hoverTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_SELECTED_HOVER))
                        .pressedTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_SELECTED_DOWN));
                button.textStyle(text -> text.textColor(WindTunnelLdlib2Theme.BUTTON_SELECTED_TEXT).textShadow(false));
            } else if (enabled) {
                style.baseTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_BASE))
                        .hoverTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_HOVER))
                        .pressedTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_DOWN));
                button.textStyle(text -> text.textColor(WindTunnelLdlib2Theme.BUTTON_TEXT).textShadow(false));
            } else {
                style.baseTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_DISABLED))
                        .hoverTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_DISABLED))
                        .pressedTexture(Objects.requireNonNull(WindTunnelLdlib2Theme.BUTTON_DISABLED));
                button.textStyle(text -> text.textColor(WindTunnelLdlib2Theme.DISABLED_TEXT).textShadow(false));
            }
        });
    }
}
