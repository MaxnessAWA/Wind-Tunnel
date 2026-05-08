package io.github.windtunnel.data;

import io.github.windtunnel.WindTunnelMod;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public final class WindTunnelDataGenerators {
    private WindTunnelDataGenerators() {
    }

    public static void gatherData(GatherDataEvent event) {
        if (!event.getMods().contains(WindTunnelMod.MOD_ID)) {
            return;
        }
        if (event.includeClient()) {
            event.createProvider(WindTunnelAirfoilAssetProvider::new);
        }
        if (event.includeServer()) {
            event.createProvider(WindTunnelAirfoilDataProvider::new);
        }
    }
}
