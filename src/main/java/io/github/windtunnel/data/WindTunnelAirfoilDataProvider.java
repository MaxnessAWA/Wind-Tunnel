package io.github.windtunnel.data;

import com.google.gson.JsonArray;
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

final class WindTunnelAirfoilDataProvider implements DataProvider {
    private final PackOutput.PathProvider lootTablePathProvider;
    private final PackOutput.PathProvider physicsPathProvider;
    private final PackOutput.PathProvider recipePathProvider;

    WindTunnelAirfoilDataProvider(PackOutput output) {
        this.lootTablePathProvider = output.createPathProvider(PackOutput.Target.DATA_PACK, "loot_table/blocks");
        this.physicsPathProvider = output.createPathProvider(PackOutput.Target.DATA_PACK, "physics_block_properties");
        this.recipePathProvider = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipe");
    }

    @SuppressWarnings("null")
    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (WindTunnelAirfoilData.AirfoilKind kind : WindTunnelAirfoilData.AirfoilKind.values()) {
            futures.add(save(output, recipePathProvider.json(windtunnel("white_" + kind.idSuffix)), baseRecipe(kind)));
            for (DyeColor color : WindTunnelAirfoilData.COLORS) {
                String id = WindTunnelAirfoilData.airfoilId(color, kind);
                futures.add(save(output, lootTablePathProvider.json(windtunnel(id)), lootTable(id)));
                futures.add(save(output, physicsPathProvider.json(windtunnel(id)), physicsProperties(id)));
                futures.add(save(output, recipePathProvider.json(windtunnel(id + "_from_dyeing")), dyeRecipe(color, kind)));
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Wind Tunnel Airfoil Data";
    }

    private static JsonObject baseRecipe(WindTunnelAirfoilData.AirfoilKind kind) {
        JsonObject key = new JsonObject();
        JsonObject brass = new JsonObject();
        brass.addProperty("item", "create:brass_sheet");
        key.add("B", brass);
        JsonObject iron = new JsonObject();
        iron.addProperty("item", "minecraft:iron_ingot");
        key.add("I", iron);

        JsonObject result = new JsonObject();
        result.addProperty("id", "windtunnel:white_" + kind.idSuffix);
        result.addProperty("count", 2);

        JsonArray pattern = new JsonArray();
        if (kind == WindTunnelAirfoilData.AirfoilKind.SYMMETRIC) {
            pattern.add("BBB");
            pattern.add(" I ");
            pattern.add(" I ");
        } else {
            pattern.add(" B ");
            pattern.add("BIB");
            pattern.add(" I ");
        }

        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:crafting_shaped");
        root.addProperty("category", "building");
        root.add("pattern", pattern);
        root.add("key", key);
        root.add("result", result);
        return root;
    }

    private static JsonObject dyeRecipe(DyeColor targetColor, WindTunnelAirfoilData.AirfoilKind kind) {
        JsonArray sourceItems = new JsonArray();
        for (DyeColor sourceColor : WindTunnelAirfoilData.COLORS) {
            if (sourceColor == targetColor) {
                continue;
            }
            JsonObject source = new JsonObject();
            source.addProperty("item", "windtunnel:" + WindTunnelAirfoilData.airfoilId(sourceColor, kind));
            sourceItems.add(source);
        }

        JsonObject dye = new JsonObject();
        dye.addProperty("item", "minecraft:" + WindTunnelAirfoilData.colorName(targetColor) + "_dye");

        JsonArray ingredients = new JsonArray();
        ingredients.add(dye);
        ingredients.add(sourceItems);

        JsonObject result = new JsonObject();
        result.addProperty("id", "windtunnel:" + WindTunnelAirfoilData.airfoilId(targetColor, kind));
        result.addProperty("count", 1);

        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:crafting_shapeless");
        root.addProperty("category", "building");
        root.addProperty("group", kind.recipeGroup);
        root.add("ingredients", ingredients);
        root.add("result", result);
        return root;
    }

    private static JsonObject lootTable(String id) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", "minecraft:item");
        entry.addProperty("name", "windtunnel:" + id);

        JsonArray entries = new JsonArray();
        entries.add(entry);

        JsonObject condition = new JsonObject();
        condition.addProperty("condition", "minecraft:survives_explosion");

        JsonArray conditions = new JsonArray();
        conditions.add(condition);

        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        pool.add("entries", entries);
        pool.add("conditions", conditions);

        JsonArray pools = new JsonArray();
        pools.add(pool);

        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:block");
        root.add("pools", pools);
        return root;
    }

    private static JsonObject physicsProperties(String id) {
        JsonObject properties = new JsonObject();
        properties.addProperty("sable:mass", 0.5D);

        JsonObject root = new JsonObject();
        root.addProperty("priority", 2100);
        root.addProperty("selector", "windtunnel:" + id);
        root.add("properties", properties);
        return root;
    }

    @SuppressWarnings("null")
    private static ResourceLocation windtunnel(String path) {
        return ResourceLocation.fromNamespaceAndPath("windtunnel", path);
    }

    @SuppressWarnings("null")
    private static CompletableFuture<?> save(CachedOutput output, Path path, JsonObject json) {
        return DataProvider.saveStable(output, json, path);
    }
}
