package io.github.windtunnel.content;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import io.github.windtunnel.ClientHooks;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Renders the spinning fan core for the wind tunnel block.
 * The block model provides the static housing; this renderer rotates the separately-authored
 * moving model using the same speed conversion logic the previous implementation used.
 */
@SuppressWarnings("null")
public class WindTunnelFanRenderer extends SafeBlockEntityRenderer<WindTunnelBlockEntity> {
    public WindTunnelFanRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(WindTunnelBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
                              MultiBufferSource bufferSource, int light, int overlay) {
        BakedModel movingModel = getMovingModel();
        if (movingModel == null || blockEntity.getLevel() == null) {
            return;
        }

        Direction direction = blockEntity.getFacing();
        BlockState state = blockEntity.getBlockState();
        int renderedLight = LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos().relative(direction));

        float time = AnimationTickHolder.getRenderTime(blockEntity.getLevel());
        float speed = blockEntity.getRenderedFanSpeed();
        if (speed > 0.0F) {
            speed = Mth.clamp(speed, 80.0F, 64.0F * 20.0F);
        }
        if (speed < 0.0F) {
            speed = Mth.clamp(speed, -64.0F * 20.0F, -80.0F);
        }
        float angleDegrees = (time * speed * 3.0F / 10.0F) % 360.0F;

        poseStack.pushPose();
        poseStack.translate(0.5D, 0.5D, 0.5D);
        applyFacingRotation(poseStack, direction);
        poseStack.mulPose(Axis.ZP.rotationDegrees(angleDegrees));
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
                    renderedLight,
                    overlay,
                    modelData,
                    renderType
            );
        }
        poseStack.popPose();
    }

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

    private static BakedModel getMovingModel() {
        ModelManager modelManager = Minecraft.getInstance().getModelManager();
        BakedModel model = modelManager.getModel(ClientHooks.WIND_TUNNEL_MOVING_MODEL);
        return model == modelManager.getMissingModel() ? null : model;
    }
}
