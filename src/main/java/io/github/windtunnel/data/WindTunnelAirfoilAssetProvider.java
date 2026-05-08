package io.github.windtunnel.data;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

final class WindTunnelAirfoilAssetProvider implements DataProvider {
    private static final String BLOCK_MODEL_PREFIX = "windtunnel:block/airfoil/";
    private static final String TEXTURE_PREFIX = "windtunnel:block/airfoil/";

    private final PackOutput.PathProvider blockstatePathProvider;
    private final PackOutput.PathProvider blockModelPathProvider;
    private final PackOutput.PathProvider itemModelPathProvider;

    WindTunnelAirfoilAssetProvider(PackOutput output) {
        this.blockstatePathProvider = output.createPathProvider(PackOutput.Target.RESOURCE_PACK, "blockstates");
        this.blockModelPathProvider = output.createPathProvider(PackOutput.Target.RESOURCE_PACK, "models/block/airfoil");
        this.itemModelPathProvider = output.createPathProvider(PackOutput.Target.RESOURCE_PACK, "models/item");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (DyeColor color : WindTunnelAirfoilData.COLORS) {
            for (WindTunnelAirfoilData.AirfoilKind kind : WindTunnelAirfoilData.AirfoilKind.values()) {
                String id = WindTunnelAirfoilData.airfoilId(color, kind);
                futures.add(save(output, blockstatePathProvider.json(windtunnel(id)), blockstate(id)));
                futures.add(save(output, blockModelPathProvider.json(windtunnel(id)), blockModel(color, kind)));
                futures.add(save(output, itemModelPathProvider.json(windtunnel(id)), itemModel(id)));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Wind Tunnel Airfoil Assets";
    }

    private static JsonObject blockstate(String id) {
        JsonObject variants = new JsonObject();
        variants.add("facing=east", variant(id, 90));
        variants.add("facing=north", variant(id, null));
        variants.add("facing=south", variant(id, 180));
        variants.add("facing=west", variant(id, 270));

        JsonObject root = new JsonObject();
        root.add("variants", variants);
        return root;
    }

    private static JsonObject variant(String id, Integer yRotation) {
        JsonObject variant = new JsonObject();
        variant.addProperty("model", BLOCK_MODEL_PREFIX + id);
        if (yRotation != null) {
            variant.addProperty("y", yRotation);
        }
        return variant;
    }

    private static JsonObject blockModel(DyeColor color, WindTunnelAirfoilData.AirfoilKind kind) {
        String texture = TEXTURE_PREFIX + "symmetric_airfoil_" + WindTunnelAirfoilData.textureColorName(color);

        JsonObject textures = new JsonObject();
        textures.addProperty("0", texture);
        textures.addProperty("particle", texture);

        JsonObject root = new JsonObject();
        root.addProperty("parent", kind.blockModelParent);
        root.add("textures", textures);
        return root;
    }

    private static JsonObject itemModel(String id) {
        JsonObject root = new JsonObject();
        root.addProperty("parent", BLOCK_MODEL_PREFIX + id);
        return root;
    }

    private static ResourceLocation windtunnel(String path) {
        return ResourceLocation.fromNamespaceAndPath("windtunnel", path);
    }

    private static CompletableFuture<?> save(CachedOutput output, Path path, JsonObject json) {
        return DataProvider.saveStable(output, json, path);
    }
}
