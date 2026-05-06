package io.github.windtunnel.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import io.github.windtunnel.ClientHooks;
import io.github.windtunnel.WindTunnelMod;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;

/**
 * Renders only the moving center cube of the aircraft airflow injector.
 * The outer shell is now a normal baked block model; this renderer just replays the authored
 * keyframes from the Gecko-style animation resource onto a side-loaded baked model.
 */
public class AirflowInjectorRenderer extends SafeBlockEntityRenderer<AirflowInjectorBlockEntity> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float CORE_BASE_Y_OFFSET_PIXELS = 0.5F;
    private static final ResourceLocation ANIMATION_LOCATION =
            ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "animations/airflow_injector.animation.json");
    private static final String BONE_NAME = "bone3";
    private static final String DEFAULT_ANIMATION_NAME = "animation";
    private static final Vector3 ZERO = new Vector3(0.0F, 0.0F, 0.0F);
    @Nullable
    private static ResourceManager cachedResourceManager;
    @Nullable
    private static AnimationClip cachedAnimation;
    private static boolean cachedAnimationLoadFailed;

    public AirflowInjectorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    @SuppressWarnings("null")
    protected void renderSafe(AirflowInjectorBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
                              MultiBufferSource bufferSource, int light, int overlay) {
        BakedModel movingModel = getMovingModel();
        if (movingModel == null) {
            return;
        }

        BlockState state = blockEntity.getBlockState();
        AnimationClip animation = getAnimation();
        BonePose pose = sampleBonePose(
                animation,
                blockEntity.isEnabled() && blockEntity.getLevel() != null
                        ? AnimationTickHolder.getRenderTime(blockEntity.getLevel()) / 20.0D
                        : 0.0D
        );

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.5D, 0.5D);
        applyFacingRotation(poseStack, blockEntity.getMountFacing());
        poseStack.translate(0.0D, (CORE_BASE_Y_OFFSET_PIXELS + pose.position.y) / 16.0D, 0.0D);
        applyRotationAroundCenter(poseStack, pose.rotation);
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        ModelData modelData = ModelData.EMPTY;
        RandomSource random = RandomSource.create(42L);
        for (net.minecraft.client.renderer.RenderType renderType : movingModel.getRenderTypes(state, random, modelData)) {
            blockRenderer.getModelRenderer().renderModel(
                    poseStack.last(),
                    bufferSource.getBuffer(net.neoforged.neoforge.client.RenderTypeHelper.getEntityRenderType(renderType, false)),
                    state,
                    movingModel,
                    1.0F,
                    1.0F,
                    1.0F,
                    light,
                    overlay,
                    modelData,
                    renderType
            );
        }
        poseStack.popPose();
    }

    @Nullable
    @SuppressWarnings("null")
    private static BakedModel getMovingModel() {
        ModelManager modelManager = Minecraft.getInstance().getModelManager();
        BakedModel model = modelManager.getModel(ClientHooks.AIRFLOW_INJECTOR_MOVING_MODEL);
        return model == modelManager.getMissingModel() ? null : model;
    }

    @Nullable
    private static synchronized AnimationClip getAnimation() {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager resourceManager = minecraft.getResourceManager();
        if (cachedResourceManager == resourceManager) {
            return cachedAnimation;
        }

        cachedResourceManager = resourceManager;
        cachedAnimation = null;
        cachedAnimationLoadFailed = false;

        try {
            cachedAnimation = loadAnimation(resourceManager);
        } catch (Exception exception) {
            if (!cachedAnimationLoadFailed) {
                LOGGER.error("Failed to load airflow injector animation resource", exception);
                cachedAnimationLoadFailed = true;
            }
        }
        return cachedAnimation;
    }

    @Nullable
    private static AnimationClip loadAnimation(ResourceManager resourceManager) throws IOException {
        JsonObject root = readJsonObject(resourceManager, ANIMATION_LOCATION);
        JsonObject animations = root.getAsJsonObject("animations");
        if (animations == null || animations.entrySet().isEmpty()) {
            return null;
        }

        JsonObject animationObject = animations.has(DEFAULT_ANIMATION_NAME)
                ? animations.getAsJsonObject(DEFAULT_ANIMATION_NAME)
                : animations.entrySet().iterator().next().getValue().getAsJsonObject();
        JsonObject bones = animationObject.getAsJsonObject("bones");
        if (bones == null || !bones.has(BONE_NAME)) {
            return null;
        }

        JsonObject boneObject = bones.getAsJsonObject(BONE_NAME);
        return new AnimationClip(
                readLoopFlag(animationObject.get("loop")),
                readDouble(animationObject, "animation_length", 0.0D),
                parseTrack(boneObject.get("position")),
                parseTrack(boneObject.get("rotation"))
        );
    }

    @SuppressWarnings("null")
    private static JsonObject readJsonObject(ResourceManager resourceManager, ResourceLocation location) throws IOException {
        try (InputStream inputStream = resourceManager.open(location);
             InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static List<Keyframe> parseTrack(@Nullable JsonElement trackElement) {
        if (trackElement == null || !trackElement.isJsonObject()) {
            return List.of();
        }

        List<Keyframe> keyframes = new ArrayList<>();
        for (var entry : trackElement.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject frameObject = entry.getValue().getAsJsonObject();
            if (!frameObject.has("vector")) {
                continue;
            }
            keyframes.add(new Keyframe(parseDouble(entry.getKey(), 0.0D), readVector3(frameObject.getAsJsonArray("vector"))));
        }
        keyframes.sort(Comparator.comparingDouble(Keyframe::time));
        return keyframes;
    }

    private static BonePose sampleBonePose(@Nullable AnimationClip animation, double animationSeconds) {
        if (animation == null) {
            return BonePose.IDENTITY;
        }

        double time = animation.wrapTime(animationSeconds);
        return new BonePose(
                sampleTrack(animation.positionTrack, time),
                sampleTrack(animation.rotationTrack, time)
        );
    }

    private static Vector3 sampleTrack(List<Keyframe> keyframes, double time) {
        if (keyframes.isEmpty()) {
            return ZERO;
        }
        if (keyframes.size() == 1 || time <= keyframes.get(0).time) {
            return keyframes.get(0).value;
        }

        Keyframe previous = keyframes.get(0);
        for (int index = 1; index < keyframes.size(); index++) {
            Keyframe next = keyframes.get(index);
            if (time <= next.time) {
                float progress = (float) ((time - previous.time) / Math.max(1.0E-6D, next.time - previous.time));
                return previous.value.lerp(next.value, progress);
            }
            previous = next;
        }

        return keyframes.get(keyframes.size() - 1).value;
    }

    @SuppressWarnings("null")
    private static void applyRotationAroundCenter(PoseStack poseStack, Vector3 rotation) {
        if (rotation.equals(ZERO)) {
            return;
        }

        if (rotation.x != 0.0F) {
            poseStack.mulPose(Axis.XP.rotationDegrees(rotation.x));
        }
        if (rotation.y != 0.0F) {
            poseStack.mulPose(Axis.YP.rotationDegrees(rotation.y));
        }
        if (rotation.z != 0.0F) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.z));
        }
    }

    @SuppressWarnings("null")
    private static void applyFacingRotation(PoseStack poseStack, Direction facing) {
        switch (facing) {
            case SOUTH -> poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            case WEST -> poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            case EAST -> poseStack.mulPose(Axis.YN.rotationDegrees(90.0F));
            case UP -> poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            case DOWN -> poseStack.mulPose(Axis.XN.rotationDegrees(90.0F));
            default -> {
            }
        }
    }

    private static boolean readLoopFlag(@Nullable JsonElement loopElement) {
        if (loopElement == null) {
            return false;
        }
        if (loopElement.isJsonPrimitive() && loopElement.getAsJsonPrimitive().isBoolean()) {
            return loopElement.getAsBoolean();
        }
        if (loopElement.isJsonPrimitive() && loopElement.getAsJsonPrimitive().isString()) {
            String value = loopElement.getAsString();
            return "loop".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value);
        }
        return false;
    }

    private static Vector3 readVector3(com.google.gson.JsonArray array) {
        return new Vector3(
                array.size() > 0 ? array.get(0).getAsFloat() : 0.0F,
                array.size() > 1 ? array.get(1).getAsFloat() : 0.0F,
                array.size() > 2 ? array.get(2).getAsFloat() : 0.0F
        );
    }

    private static double readDouble(@Nullable JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        return object.get(key).getAsDouble();
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private record AnimationClip(boolean loop, double lengthSeconds, List<Keyframe> positionTrack, List<Keyframe> rotationTrack) {
        private double wrapTime(double time) {
            if (!loop || lengthSeconds <= 1.0E-6D) {
                return Mth.clamp((float) time, 0.0F, (float) Math.max(0.0D, lengthSeconds));
            }
            double wrapped = time % lengthSeconds;
            return wrapped < 0.0D ? wrapped + lengthSeconds : wrapped;
        }
    }

    private record Keyframe(double time, Vector3 value) {
    }

    private record BonePose(Vector3 position, Vector3 rotation) {
        private static final BonePose IDENTITY = new BonePose(ZERO, ZERO);
    }

    private record Vector3(float x, float y, float z) {
        private Vector3 lerp(Vector3 other, float delta) {
            return new Vector3(
                    Mth.lerp(delta, x, other.x),
                    Mth.lerp(delta, y, other.y),
                    Mth.lerp(delta, z, other.z)
            );
        }
    }
}
