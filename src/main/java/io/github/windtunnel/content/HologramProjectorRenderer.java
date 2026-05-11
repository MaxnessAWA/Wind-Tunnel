package io.github.windtunnel.content;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.BlockEntityRenderDispatcherExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import io.github.windtunnel.ClientHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/**
 * Renders a miniature blue hologram of the captured Sable structure above the projector block.
 */
public class HologramProjectorRenderer implements BlockEntityRenderer<HologramProjectorBlockEntity> {
    private static final double DISPLAY_CENTER_X = 0.5D;
    private static final double DISPLAY_CENTER_Y = 1.05D;
    private static final double DISPLAY_CENTER_Z = 0.5D;
    private static final double DISPLAY_MAX_SIZE = 1.0D;
    private static final double MIN_ARROW_LENGTH = 0.08D;
    private static final double MAX_ARROW_LENGTH = 0.34D;
    private static final double ARROW_HEAD_FRACTION = 0.28D;
    private static final double ARROW_HEAD_WIDTH_FRACTION = 0.45D;
    private static final double MIN_FORCE_LENGTH_SQUARED = 1.0E-8D;
    private static final float HOLOGRAM_RED = 0.32F;
    private static final float HOLOGRAM_GREEN = 0.78F;
    private static final float HOLOGRAM_BLUE = 1.0F;
    private static final float HOLOGRAM_ALPHA = 0.45F;
    private static final int HOLOGRAM_ENTITY_RED = Math.round(HOLOGRAM_RED * 255.0F);
    private static final int HOLOGRAM_ENTITY_GREEN = Math.round(HOLOGRAM_GREEN * 255.0F);
    private static final int HOLOGRAM_ENTITY_BLUE = Math.round(HOLOGRAM_BLUE * 255.0F);
    private static final int HOLOGRAM_ENTITY_ALPHA = Math.round(HOLOGRAM_ALPHA * 255.0F);
    private static final int HOLOGRAM_ENTITY_COLOR = HOLOGRAM_ENTITY_ALPHA << 24
            | HOLOGRAM_ENTITY_RED << 16
            | HOLOGRAM_ENTITY_GREEN << 8
            | HOLOGRAM_ENTITY_BLUE;
    private static final int DEFAULT_ARROW_COLOR = 0x6DE8FF;
    private static final Matrix4f SUB_LEVEL_MODEL = new Matrix4f();
    private static final Matrix4f IDENTITY_MODEL_VIEW = new Matrix4f();

    public HologramProjectorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(@NotNull HologramProjectorBlockEntity blockEntity, float partialTick,@NotNull PoseStack poseStack,
                       @NotNull MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
    }

    public static void renderInWorld(@NotNull HologramProjectorBlockEntity blockEntity, float partialTick,
                                     @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource,
                                     @NotNull Matrix4f baseModelView, @NotNull Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        List<ClientSubLevel> subLevels = resolveSubLevels(blockEntity, minecraft);
        if (subLevels.isEmpty()) {
            return;
        }

        HologramTransform transform = HologramTransform.create(subLevels);
        if (transform == null) {
            return;
        }

        renderMiniatureStructure(subLevels, transform, partialTick, poseStack, bufferSource, baseModelView, projection);
        renderForceArrows(blockEntity.getForceArrows(), transform, new Matrix4f(baseModelView).mul(poseStack.last().pose()), bufferSource);
    }

    @Override
    public boolean shouldRenderOffScreen(@NotNull HologramProjectorBlockEntity blockEntity) {
        return true;
    }

    private static List<ClientSubLevel> resolveSubLevels(HologramProjectorBlockEntity blockEntity, Minecraft minecraft) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return List.of();
        }

        List<ClientSubLevel> subLevels = new ArrayList<>();
        for (UUID id : blockEntity.getRenderedSubLevelIds()) {
            if (container.getSubLevel(id) instanceof ClientSubLevel subLevel
                    && subLevel.getRenderData() != null
                    && !subLevels.contains(subLevel)) {
                subLevels.add(subLevel);
            }
        }
        return subLevels;
    }

    @SuppressWarnings("null")
    private static void renderMiniatureStructure(List<ClientSubLevel> subLevels, HologramTransform transform,
                                                 float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
                                                 Matrix4f baseModelView, Matrix4f projection) {
        Minecraft minecraft = Minecraft.getInstance();
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        dispatcher.preRenderChunks(camera);

        Matrix4f modelView = new Matrix4f(baseModelView)
                .mul(poseStack.last().pose())
                .translate((float) DISPLAY_CENTER_X, (float) DISPLAY_CENTER_Y, (float) DISPLAY_CENTER_Z)
                .scale((float) transform.scale());

        float[] previousColor = RenderSystem.getShaderColor().clone();
        boolean previousDepthMask = true;
        RenderSystem.setShaderColor(HOLOGRAM_RED, HOLOGRAM_GREEN, HOLOGRAM_BLUE, HOLOGRAM_ALPHA);
        RenderSystem.depthMask(false);
        try {
            for (RenderType renderType : RenderType.chunkBufferLayers()) {
                renderType.setupRenderState();
                applyHologramRenderState();
                ShaderInstance shader = Objects.requireNonNullElseGet(ClientHooks.getHologramBlockShader(),
                        () -> Objects.requireNonNull(RenderSystem.getShader()));
                shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
                applyHologramShader(shader, renderType);

                renderMiniatureBlockLayer(subLevels, renderType, shader, modelView, transform);

                shader.clear();
                renderType.clearRenderState();
            }
            applyHologramRenderState();
            renderMiniatureBlockEntities(subLevels, transform, partialTick, poseStack);
        } finally {
            RenderSystem.depthMask(previousDepthMask);
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }

    @SuppressWarnings("null")
    private static void renderMiniatureBlockLayer(List<ClientSubLevel> subLevels, RenderType renderType,
                                                  ShaderInstance shader, Matrix4f modelView,
                                                  HologramTransform transform) {
        FogShape previousFogShape = RenderSystem.getShaderFogShape();
        if (shader.FOG_SHAPE != null && previousFogShape != FogShape.SPHERE) {
            shader.FOG_SHAPE.set(FogShape.SPHERE.getIndex());
            shader.FOG_SHAPE.upload();
        }

        VanillaSubLevelRenderDispatcher.setupDynamicEffects(shader, true, true);
        try {
            renderChunkedBlockLayer(subLevels, renderType, shader, modelView, transform);
            renderSingleBlockLayer(subLevels, renderType, shader, modelView, transform);
        } finally {
            if (shader.FOG_SHAPE != null && previousFogShape != FogShape.SPHERE) {
                shader.FOG_SHAPE.set(previousFogShape.getIndex());
            }
            VanillaSubLevelRenderDispatcher.setupDynamicEffects(shader, false, false);
        }
    }

    private static void renderChunkedBlockLayer(List<ClientSubLevel> subLevels, RenderType renderType,
                                                ShaderInstance shader, Matrix4f modelView,
                                                HologramTransform transform) {
        for (ClientSubLevel subLevel : subLevels) {
            if (!(subLevel.getRenderData() instanceof VanillaChunkedSubLevelRenderData renderData)) {
                continue;
            }
            renderChunkedSubLevel(renderData, renderType, shader, modelView, transform);
        }
    }

    @SuppressWarnings("null")
    private static void renderChunkedSubLevel(VanillaChunkedSubLevelRenderData renderData, RenderType renderType,
                                              ShaderInstance shader, Matrix4f modelView,
                                              HologramTransform transform) {
        ClientSubLevel subLevel = renderData.getSubLevel();
        if (renderData.allRenderSections().isEmpty()) {
            return;
        }

        Pose3dc renderPose = subLevel.renderPose();
        Vector3dc renderPos = renderPose.position();
        Quaterniondc renderRot = renderPose.orientation();
        Vector3dc renderScale = renderPose.scale();
        Vector3dc rotationPoint = renderPose.rotationPoint();

        Uniform skyLightScale = shader.getUniform("SableSkyLightScale");
        if (skyLightScale != null) {
            skyLightScale.set(subLevel.getLatestSkyLightScale() / 15.0F);
        }

        if (shader.MODEL_VIEW_MATRIX != null) {
            SUB_LEVEL_MODEL.identity()
                    .translate((float) (renderPos.x() - transform.center().x()),
                            (float) (renderPos.y() - transform.center().y()),
                            (float) (renderPos.z() - transform.center().z()))
                    .rotate(new Quaternionf(renderRot))
                    .scale((float) renderScale.x(), (float) renderScale.y(), (float) renderScale.z());
            shader.MODEL_VIEW_MATRIX.set(modelView.mul(SUB_LEVEL_MODEL, new Matrix4f()));
        }

        Uniform chunkOffsetUniform = shader.CHUNK_OFFSET;
        for (SectionRenderDispatcher.RenderSection renderSection : renderData.allRenderSections()) {
            if (renderSection.getCompiled().isEmpty(renderType)) {
                continue;
            }

            if (chunkOffsetUniform != null) {
                BlockPos pos = renderSection.getOrigin();
                chunkOffsetUniform.set(
                        (float) (pos.getX() - rotationPoint.x()),
                        (float) (pos.getY() - rotationPoint.y()),
                        (float) (pos.getZ() - rotationPoint.z())
                );
            }

            applyHologramShader(shader, renderType);
            VertexBuffer buffer = renderSection.getBuffer(renderType);
            buffer.bind();
            buffer.draw();
        }

        if (chunkOffsetUniform != null) {
            chunkOffsetUniform.set(0.0F, 0.0F, 0.0F);
        }
    }

    @SuppressWarnings("null")
    private static void renderSingleBlockLayer(List<ClientSubLevel> subLevels, RenderType renderType,
                                               ShaderInstance shader, Matrix4f modelView,
                                               HologramTransform transform) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        Matrix4f singleBlockModelView = new Matrix4f();
        for (ClientSubLevel subLevel : subLevels) {
            if (subLevel.getRenderData() instanceof VanillaSingleSubLevelRenderData renderData) {
                renderData.renderSingleBlock(renderType, builder, singleBlockModelView(subLevel, transform, singleBlockModelView),
                        transform.center().x(), transform.center().y(), transform.center().z());
            }
        }

        MeshData meshData = builder.build();
        if (meshData != null) {
            Uniform skyLightScale = shader.getUniform("SableSkyLightScale");
            if (skyLightScale != null) {
                skyLightScale.set(1.0F);
            }
            if (shader.MODEL_VIEW_MATRIX != null) {
                shader.MODEL_VIEW_MATRIX.set(modelView);
            }
            if (shader.CHUNK_OFFSET != null) {
                shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);
            }
            applyHologramShader(shader, renderType);
            BufferUploader.draw(meshData);
        }
    }

    private static Matrix4f singleBlockModelView(ClientSubLevel subLevel, HologramTransform transform, Matrix4f dest) {
        Pose3dc renderPose = subLevel.renderPose();
        Vector3dc renderScale = renderPose.scale();
        if (renderScale.equals(1.0D, 1.0D, 1.0D)) {
            return IDENTITY_MODEL_VIEW;
        }

        Vector3dc renderPos = renderPose.position();
        Quaternionf renderRot = new Quaternionf(renderPose.orientation());
        Quaternionf renderRotInverse = new Quaternionf(renderRot).conjugate();
        float x = (float) (renderPos.x() - transform.center().x());
        float y = (float) (renderPos.y() - transform.center().y());
        float z = (float) (renderPos.z() - transform.center().z());

        return dest.identity()
                .translate(x, y, z)
                .rotate(renderRot)
                .scale((float) renderScale.x(), (float) renderScale.y(), (float) renderScale.z())
                .rotate(renderRotInverse)
                .translate(-x, -y, -z);
    }

    private static void renderMiniatureBlockEntities(List<ClientSubLevel> subLevels, HologramTransform transform,
                                                     float partialTick, PoseStack poseStack) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockEntityRenderDispatcher blockEntityRenderDispatcher = minecraft.getBlockEntityRenderDispatcher();
        BlockEntityRenderDispatcherExtension dispatcherExtension =
                (BlockEntityRenderDispatcherExtension) blockEntityRenderDispatcher;
        Matrix4f subLevelTransform = new Matrix4f();
        Vector3d chunkOffset = new Vector3d();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource hologramBufferSource = minecraft.renderBuffers().bufferSource();
        MultiBufferSource tintedBufferSource = hologramBlockEntityBufferSource(hologramBufferSource);

        poseStack.pushPose();
        poseStack.translate(DISPLAY_CENTER_X, DISPLAY_CENTER_Y, DISPLAY_CENTER_Z);
        poseStack.scale((float) transform.scale(), (float) transform.scale(), (float) transform.scale());
        poseStack.translate(-transform.center().x(), -transform.center().y(), -transform.center().z());
        try {
            for (ClientSubLevel subLevel : subLevels) {
                SubLevelRenderData data = subLevel.getRenderData();
                if (data == null) {
                    continue;
                }

                subLevel.renderPose().rotationPoint().negate(chunkOffset.zero());
                fillSubLevelWorldTransform(subLevel, subLevelTransform);
                dispatcherExtension.sable$setCameraPosition(new Vec3(
                        camera.x - chunkOffset.x(),
                        camera.y - chunkOffset.y(),
                        camera.z - chunkOffset.z()
                ));

                poseStack.pushPose();
                try {
                    poseStack.mulPose(subLevelTransform);
                    if (data instanceof VanillaChunkedSubLevelRenderData chunkedRenderData) {
                        for (SectionRenderDispatcher.RenderSection renderSection : chunkedRenderData.allRenderSections()) {
                            List<BlockEntity> blockEntities = renderSection.getCompiled().getRenderableBlockEntities();
                            if (!blockEntities.isEmpty()) {
                                renderBlockEntities(blockEntities, poseStack, tintedBufferSource, partialTick,
                                        -chunkOffset.x(), -chunkOffset.y(), -chunkOffset.z());
                            }
                        }
                    } else if (data instanceof VanillaSingleSubLevelRenderData singleRenderData) {
                        BlockEntity blockEntity = singleRenderData.getRenderBlockEntity();
                        if (blockEntity != null) {
                            renderSingleBlockEntity(blockEntity, poseStack, tintedBufferSource, partialTick,
                                    -chunkOffset.x(), -chunkOffset.y(), -chunkOffset.z());
                        }
                    }
                } finally {
                    poseStack.popPose();
                }
            }
        } finally {
            dispatcherExtension.sable$setCameraPosition(null);
            hologramBufferSource.endBatch();
            poseStack.popPose();
        }
    }

    private static void fillSubLevelWorldTransform(ClientSubLevel subLevel, Matrix4f dest) {
        Pose3dc renderPose = subLevel.renderPose();
        Vector3dc renderPos = renderPose.position();
        Quaterniondc renderRot = renderPose.orientation();
        Vector3dc renderScale = renderPose.scale();

        dest.identity()
                .translate((float) renderPos.x(), (float) renderPos.y(), (float) renderPos.z())
                .rotate(new Quaternionf(renderRot))
                .scale((float) renderScale.x(), (float) renderScale.y(), (float) renderScale.z());
    }

    private static MultiBufferSource hologramBlockEntityBufferSource(MultiBufferSource.BufferSource delegate) {
        return renderType -> new HologramBlockEntityVertexConsumer(delegate.getBuffer(renderType));
    }

    private static void renderBlockEntities(List<BlockEntity> blockEntities, PoseStack poseStack, MultiBufferSource bufferSource,
                                            float partialTick, double cameraX, double cameraY, double cameraZ) {
        for (BlockEntity blockEntity : blockEntities) {
            renderSingleBlockEntity(blockEntity, poseStack, bufferSource, partialTick, cameraX, cameraY, cameraZ);
        }
    }

    @SuppressWarnings("null")
    private static void renderSingleBlockEntity(BlockEntity blockEntity, PoseStack poseStack, MultiBufferSource bufferSource,
                                                float partialTick, double cameraX, double cameraY, double cameraZ) {
        BlockEntityRenderDispatcher blockEntityRenderDispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        BlockPos pos = blockEntity.getBlockPos();
        poseStack.pushPose();
        try {
            poseStack.translate((double) pos.getX() - cameraX, (double) pos.getY() - cameraY, (double) pos.getZ() - cameraZ);
            blockEntityRenderDispatcher.render(blockEntity, partialTick, poseStack, bufferSource);
        } finally {
            poseStack.popPose();
        }
    }

    private static void applyHologramRenderState() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
    }

    private static void applyHologramShader(ShaderInstance shader, RenderType renderType) {
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(HOLOGRAM_RED, HOLOGRAM_GREEN, HOLOGRAM_BLUE, HOLOGRAM_ALPHA);
        }
        Uniform alphaCutoff = shader.getUniform("AlphaCutoff");
        if (alphaCutoff != null) {
            alphaCutoff.set(alphaCutoff(renderType));
        }
        shader.apply();
    }

    private static float alphaCutoff(RenderType renderType) {
        if (renderType == RenderType.cutoutMipped()) {
            return 0.5F;
        }
        if (renderType == RenderType.cutout() || renderType == RenderType.tripwire()) {
            return 0.1F;
        }
        return 0.0F;
    }

    private static void renderForceArrows(List<HologramForceArrow> arrows, HologramTransform transform,
                                          Matrix4f pose, MultiBufferSource bufferSource) {
        if (arrows.isEmpty()) {
            return;
        }

        double maxForce = 0.0D;
        for (HologramForceArrow arrow : arrows) {
            maxForce = Math.max(maxForce, arrow.force().length());
        }
        if (maxForce <= 1.0E-6D) {
            return;
        }

        @SuppressWarnings("null")
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        for (HologramForceArrow arrow : arrows) {
            double forceLengthSquared = arrow.force().lengthSquared();
            if (forceLengthSquared <= MIN_FORCE_LENGTH_SQUARED) {
                continue;
            }

            double forceLength = Math.sqrt(forceLengthSquared);
            double normalizedForce = Mth.clamp(forceLength / maxForce, 0.0D, 1.0D);
            double arrowLength = Mth.lerp(Math.sqrt(normalizedForce), MIN_ARROW_LENGTH, MAX_ARROW_LENGTH);

            Vector3d origin = transform.worldToMiniature(arrow.point(), new Vector3d());
            Vector3d direction = new Vector3d(arrow.force()).normalize();
            Vector3d end = new Vector3d(origin).fma(arrowLength, direction);
            int color = arrowColor(arrow.groupId());

            emitLine(pose, consumer, origin, end, color, 235);
            emitArrowHead(pose, consumer, end, direction, arrowLength, color);
        }
    }

    private static void emitArrowHead(Matrix4f pose, VertexConsumer consumer, Vector3dc end,
                                      Vector3dc direction, double arrowLength, int color) {
        double headLength = Math.max(0.03D, arrowLength * ARROW_HEAD_FRACTION);
        double headWidth = headLength * ARROW_HEAD_WIDTH_FRACTION;
        Vector3d forward = new Vector3d(direction).normalize();
        Vector3d side = perpendicular(forward);
        Vector3d up = new Vector3d(forward).cross(side).normalize();
        Vector3d base = new Vector3d(end).fma(-headLength, forward);

        emitLine(pose, consumer, end, new Vector3d(base).fma(headWidth, side), color, 235);
        emitLine(pose, consumer, end, new Vector3d(base).fma(-headWidth, side), color, 235);
        emitLine(pose, consumer, end, new Vector3d(base).fma(headWidth, up), color, 235);
        emitLine(pose, consumer, end, new Vector3d(base).fma(-headWidth, up), color, 235);
    }

    private static Vector3d perpendicular(Vector3dc direction) {
        Vector3d axis = Math.abs(direction.y()) > 0.85D ? new Vector3d(1.0D, 0.0D, 0.0D) : new Vector3d(0.0D, 1.0D, 0.0D);
        return new Vector3d(direction).cross(axis).normalize();
    }

    @SuppressWarnings("null")
    private static void emitLine(Matrix4f pose, VertexConsumer consumer, Vector3dc from,
                                 Vector3dc to, int color, int alpha) {
        Vector3d normal = new Vector3d(to).sub(from);
        if (normal.lengthSquared() <= 1.0E-8D) {
            normal.set(0.0D, 1.0D, 0.0D);
        } else {
            normal.normalize();
        }

        consumer.addVertex(pose, (float) from.x(), (float) from.y(), (float) from.z())
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal((float) normal.x(), (float) normal.y(), (float) normal.z());
        consumer.addVertex(pose, (float) to.x(), (float) to.y(), (float) to.z())
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal((float) normal.x(), (float) normal.y(), (float) normal.z());
    }

    private static int arrowColor(ResourceLocation groupId) {
        ForceGroup group = ForceGroups.REGISTRY.get(groupId);
        return group == null ? DEFAULT_ARROW_COLOR : group.color();
    }

    private static int red(int color) {
        return color >> 16 & 0xFF;
    }

    private static int green(int color) {
        return color >> 8 & 0xFF;
    }

    private static int blue(int color) {
        return color & 0xFF;
    }

    private static final class HologramBlockEntityVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;

        private HologramBlockEntityVertexConsumer(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(HOLOGRAM_ENTITY_RED, HOLOGRAM_ENTITY_GREEN, HOLOGRAM_ENTITY_BLUE, HOLOGRAM_ENTITY_ALPHA);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(HOLOGRAM_ENTITY_COLOR);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(normalX, normalY, normalZ);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float normalX, float normalY, float normalZ) {
            delegate.addVertex(x, y, z, HOLOGRAM_ENTITY_COLOR, u, v, overlay, light, normalX, normalY, normalZ);
        }
    }

    private record HologramTransform(Vector3d center, double scale) {
        private static HologramTransform create(List<ClientSubLevel> subLevels) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (ClientSubLevel subLevel : subLevels) {
                ensureCompiled(subLevel);
                BoundingBox3ic box = subLevel.getPlot().getBoundingBox();
                if (box == null || box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) {
                    continue;
                }

                int[] xs = {box.minX(), box.maxX() + 1};
                int[] ys = {box.minY(), box.maxY() + 1};
                int[] zs = {box.minZ(), box.maxZ() + 1};
                Pose3dc pose = subLevel.renderPose();
                for (int x : xs) {
                    for (int y : ys) {
                        for (int z : zs) {
                            Vector3d world = pose.transformPosition(new Vector3d(x, y, z), new Vector3d());
                            minX = Math.min(minX, world.x());
                            minY = Math.min(minY, world.y());
                            minZ = Math.min(minZ, world.z());
                            maxX = Math.max(maxX, world.x());
                            maxY = Math.max(maxY, world.y());
                            maxZ = Math.max(maxZ, world.z());
                        }
                    }
                }
            }

            if (!Double.isFinite(minX)) {
                return null;
            }

            double width = maxX - minX;
            double height = maxY - minY;
            double length = maxZ - minZ;
            double largest = Math.max(Math.max(width, height), length);
            if (!Double.isFinite(largest) || largest <= 1.0E-6D) {
                return null;
            }

            return new HologramTransform(
                    new Vector3d((minX + maxX) * 0.5D, (minY + maxY) * 0.5D, (minZ + maxZ) * 0.5D),
                    DISPLAY_MAX_SIZE / largest
            );
        }

        private Vector3d worldToMiniature(Vector3dc world, Vector3d dest) {
            return dest.set(world).sub(center).mul(scale).add(DISPLAY_CENTER_X, DISPLAY_CENTER_Y, DISPLAY_CENTER_Z);
        }
    }

    private static void ensureCompiled(ClientSubLevel subLevel) {
        SubLevelRenderData renderData = subLevel.getRenderData();
        BoundingBox3ic box = subLevel.getPlot().getBoundingBox();
        if (renderData == null || box == null || box.minX() > box.maxX() || box.minY() > box.maxY() || box.minZ() > box.maxZ()) {
            return;
        }

        int minSectionX = box.minX() >> 4;
        int minSectionY = box.minY() >> 4;
        int minSectionZ = box.minZ() >> 4;
        int maxSectionX = box.maxX() >> 4;
        int maxSectionY = box.maxY() >> 4;
        int maxSectionZ = box.maxZ() >> 4;
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    if (!renderData.isSectionCompiled(sectionX, sectionY, sectionZ)) {
                        compileSections(renderData);
                        return;
                    }
                }
            }
        }
    }

    private static void compileSections(SubLevelRenderData renderData) {
        renderData.compileSections(
                Minecraft.getInstance().options.prioritizeChunkUpdates().get(),
                new net.minecraft.client.renderer.chunk.RenderRegionCache(),
                Minecraft.getInstance().gameRenderer.getMainCamera()
        );
    }
}
