package io.github.windtunnel.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.windtunnel.content.HologramProjectorBlockEntity;
import io.github.windtunnel.content.HologramProjectorRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Renders hologram projector previews in a world render stage so shader mods see them as world content,
 * not nested block-entity draws.
 */
public final class HologramProjectorWorldRenderer {
    private HologramProjectorWorldRenderer() {
    }

    @SuppressWarnings("null")
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        List<HologramProjectorBlockEntity> projectors = collectLoadedProjectors();
        if (projectors.isEmpty()) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        Matrix4f projection = new Matrix4f(event.getProjectionMatrix());

        for (HologramProjectorBlockEntity projector : projectors) {
            if (!shouldRender(projector, cameraPosition)) {
                continue;
            }

            BlockPos pos = projector.getBlockPos();
            PoseStack projectorPoseStack = new PoseStack();
            projectorPoseStack.translate(
                    pos.getX() - cameraPosition.x,
                    pos.getY() - cameraPosition.y,
                    pos.getZ() - cameraPosition.z
            );
            HologramProjectorRenderer.renderInWorld(projector, partialTick, projectorPoseStack, bufferSource, modelView, projection);
        }
        bufferSource.endBatch();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HologramProjectorWorldState.clear();
    }

    private static List<HologramProjectorBlockEntity> collectLoadedProjectors() {
        Minecraft minecraft = Minecraft.getInstance();
        List<HologramProjectorBlockEntity> projectors = new ArrayList<>();
        for (BlockPos pos : HologramProjectorWorldState.getKnownProjectors()) {
            BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);
            if (blockEntity instanceof HologramProjectorBlockEntity projector && !projector.isRemoved()) {
                projectors.add(projector);
            }
        }
        return projectors;
    }

    private static boolean shouldRender(HologramProjectorBlockEntity projector, Vec3 cameraPosition) {
        if (projector.getRenderedSubLevelIds().isEmpty() && projector.getForceArrows().isEmpty()) {
            return false;
        }

        BlockPos pos = projector.getBlockPos();
        double dx = pos.getX() + 0.5D - cameraPosition.x;
        double dy = pos.getY() + 0.5D - cameraPosition.y;
        double dz = pos.getZ() + 0.5D - cameraPosition.z;
        return dx * dx + dy * dy + dz * dz <= 4096.0D;
    }
}
