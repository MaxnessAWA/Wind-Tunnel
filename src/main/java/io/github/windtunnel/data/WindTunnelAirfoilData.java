package io.github.windtunnel.data;

import java.util.List;
import net.minecraft.world.item.DyeColor;

final class WindTunnelAirfoilData {
    static final List<DyeColor> COLORS = List.of(
            DyeColor.WHITE,
            DyeColor.ORANGE,
            DyeColor.MAGENTA,
            DyeColor.LIGHT_BLUE,
            DyeColor.YELLOW,
            DyeColor.LIME,
            DyeColor.PINK,
            DyeColor.GRAY,
            DyeColor.LIGHT_GRAY,
            DyeColor.CYAN,
            DyeColor.PURPLE,
            DyeColor.BLUE,
            DyeColor.BROWN,
            DyeColor.GREEN,
            DyeColor.RED,
            DyeColor.BLACK
    );

    private WindTunnelAirfoilData() {
    }

    static String colorName(DyeColor color) {
        return color.getName();
    }

    static String textureColorName(DyeColor color) {
        return colorName(color);
    }

    static String airfoilId(DyeColor color, AirfoilKind kind) {
        return colorName(color) + "_" + kind.idSuffix;
    }

    enum AirfoilKind {
        SYMMETRIC("symmetric_airfoil", "windtunnel:block/airfoil/symmetric_airfoil_base", "symmetric_airfoil"),
        VERTICAL_SYMMETRIC("vertical_symmetric_airfoil", "windtunnel:block/airfoil/vertical_symmetric_airfoil_base", "vertical_symmetric_airfoil");

        final String idSuffix;
        final String blockModelParent;
        final String recipeGroup;

        AirfoilKind(String idSuffix, String blockModelParent, String recipeGroup) {
            this.idSuffix = idSuffix;
            this.blockModelParent = blockModelParent;
            this.recipeGroup = recipeGroup;
        }
    }
}
