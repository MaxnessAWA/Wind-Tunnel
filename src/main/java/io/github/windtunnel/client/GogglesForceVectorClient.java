package io.github.windtunnel.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import io.github.windtunnel.content.HologramForceArrow;
import io.github.windtunnel.content.AeronauticsCompat;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Client cache and world-space renderer for force vectors visible through Aeronautics aviator's goggles.
 */
public final class GogglesForceVectorClient {
    private static final int EXPIRE_AFTER_TICKS = 10;
    private static final int DEFAULT_ARROW_COLOR = 0x6DE8FF;
    private static final double MIN_ARROW_LENGTH = 0.35D;
    private static final double MAX_ARROW_LENGTH = 4.65D;
    private static final double ARROW_HEAD_FRACTION = 0.5D;
    private static final double ARROW_HEAD_WIDTH_FRACTION = 0.2D;
    private static final double MIN_FORCE_LENGTH_SQUARED = 1.0E-8D;
    private static final double FORCE_VECTOR_LINE_WIDTH = 7.0D;
    private static List<HologramForceArrow> arrows = List.of();
    private static long lastUpdateGameTime = Long.MIN_VALUE;

    private GogglesForceVectorClient() {
    }

    public static void clear() {
        arrows = List.of();
        lastUpdateGameTime = Long.MIN_VALUE;
    }

    @SuppressWarnings("null")
    public static void handlePayload(List<HologramForceArrow> updatedArrows) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null
                || minecraft.player == null
                || !AeronauticsCompat.isWearingAviatorsGoggles(minecraft.player)) {
            clear();
            return;
        }
        arrows = List.copyOf(updatedArrows);
        lastUpdateGameTime = minecraft.level.getGameTime();
    }

    @SuppressWarnings("null")
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || arrows.isEmpty()) {
            return;
        }
        if (!AeronauticsCompat.isWearingAviatorsGoggles(minecraft.player)) {
            clear();
            return;
        }
        if (minecraft.level.getGameTime() - lastUpdateGameTime > EXPIRE_AFTER_TICKS) {
            clear();
            return;
        }

        double maxForce = 0.0D;
        for (HologramForceArrow arrow : arrows) {
            maxForce = Math.max(maxForce, arrow.force().length());
        }
        if (maxForce <= 1.0E-6D) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        RenderType renderType = ForceVectorRenderTypes.forceVectorLines();
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();

        poseStack.pushPose();
        try {
            for (HologramForceArrow arrow : arrows) {
                renderArrow(poseStack, consumer, arrow, maxForce, cameraPosition);
            }
        } finally {
            poseStack.popPose();
            bufferSource.endBatch(renderType);
        }
    }

    private static void renderArrow(PoseStack poseStack, VertexConsumer consumer, HologramForceArrow arrow,
                                    double maxForce, Vec3 cameraPosition) {
        double forceLengthSquared = arrow.force().lengthSquared();
        if (forceLengthSquared <= MIN_FORCE_LENGTH_SQUARED) {
            return;
        }

        double forceLength = Math.sqrt(forceLengthSquared);
        double normalizedForce = Mth.clamp(forceLength / maxForce, 0.0D, 1.0D);
        double arrowLength = Mth.lerp(Math.sqrt(normalizedForce), MIN_ARROW_LENGTH, MAX_ARROW_LENGTH);

        Vector3d origin = new Vector3d(
                arrow.point().x() - cameraPosition.x,
                arrow.point().y() - cameraPosition.y,
                arrow.point().z() - cameraPosition.z
        );
        Vector3d direction = new Vector3d(arrow.force()).normalize();
        Vector3d end = new Vector3d(origin).fma(arrowLength, direction);
        int color = arrowColor(arrow.groupId());

        emitLine(poseStack, consumer, origin, end, color, 235);
        emitArrowHead(poseStack, consumer, end, direction, arrowLength, color);
    }

    private static void emitArrowHead(PoseStack poseStack, VertexConsumer consumer, Vector3dc end,
                                      Vector3dc direction, double arrowLength, int color) {
        double headLength = Math.max(0.08D, arrowLength * ARROW_HEAD_FRACTION);
        double headWidth = headLength * ARROW_HEAD_WIDTH_FRACTION;
        Vector3d forward = new Vector3d(direction).normalize();
        Vector3d side = perpendicular(forward);
        Vector3d up = new Vector3d(forward).cross(side).normalize();
        Vector3d base = new Vector3d(end).fma(-headLength, forward);

        emitLine(poseStack, consumer, end, new Vector3d(base).fma(headWidth, side), color, 235);
        emitLine(poseStack, consumer, end, new Vector3d(base).fma(-headWidth, side), color, 235);
        emitLine(poseStack, consumer, end, new Vector3d(base).fma(headWidth, up), color, 235);
        emitLine(poseStack, consumer, end, new Vector3d(base).fma(-headWidth, up), color, 235);
    }

    private static Vector3d perpendicular(Vector3dc direction) {
        Vector3d axis = Math.abs(direction.y()) > 0.85D ? new Vector3d(1.0D, 0.0D, 0.0D) : new Vector3d(0.0D, 1.0D, 0.0D);
        return new Vector3d(direction).cross(axis).normalize();
    }

    @SuppressWarnings("null")
    private static void emitLine(PoseStack poseStack, VertexConsumer consumer, Vector3dc from,
                                 Vector3dc to, int color, int alpha) {
        Vector3d normal = new Vector3d(to).sub(from);
        if (normal.lengthSquared() <= 1.0E-8D) {
            normal.set(0.0D, 1.0D, 0.0D);
        } else {
            normal.normalize();
        }

        Matrix4f pose = poseStack.last().pose();
        consumer.addVertex(pose, (float) from.x(), (float) from.y(), (float) from.z())
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal(poseStack.last(), (float) normal.x(), (float) normal.y(), (float) normal.z());
        consumer.addVertex(pose, (float) to.x(), (float) to.y(), (float) to.z())
                .setColor(red(color), green(color), blue(color), alpha)
                .setNormal(poseStack.last(), (float) normal.x(), (float) normal.y(), (float) normal.z());
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

    private static final class ForceVectorRenderTypes extends RenderType {
        @SuppressWarnings("null")
        private static final RenderType FORCE_VECTOR_LINES = RenderType.create(
                "windtunnel:goggles_force_vectors",
                DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(OptionalDouble.of(FORCE_VECTOR_LINE_WIDTH)))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false)
        );

        private ForceVectorRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                       boolean affectsCrumbling, boolean sortOnUpload,
                                       Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        private static RenderType forceVectorLines() {
            return FORCE_VECTOR_LINES;
        }
    }
}
