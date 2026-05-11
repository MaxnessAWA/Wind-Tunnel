package io.github.windtunnel.content;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup.PointForce;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.entities.diagram.DiagramConfig;
import dev.simulated_team.simulated.content.entities.diagram.screen.ForceClusterFinder;
import dev.simulated_team.simulated.util.SimpleSubLevelGroupRenderer;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import foundry.veil.api.client.render.VeilLevelPerspectiveRenderer;
import foundry.veil.api.client.render.framebuffer.AdvancedFbo;
import io.github.windtunnel.network.RequestAirflowInjectorDiagramPayload;
import io.github.windtunnel.network.RequestWindTunnelMountDiagramPayload;
import io.github.windtunnel.WindTunnelMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

/**
 * LDLib2-hosted force diagram.
 */
public class WindTunnelLdlib2DiagramElement extends UIElement implements WindTunnelDiagramReceiver {
    public enum Source {
        MOUNT,
        AIRFLOW_INJECTOR
    }

    private static final int DIAGRAM_REFRESH_INTERVAL = 10;
    private static final int MIN_PANEL_WIDTH = 288;
    private static final int MIN_PANEL_HEIGHT = 340;
    private static final int MIN_DIAGRAM_WIDTH = 256;
    private static final int MIN_DIAGRAM_HEIGHT = 192;
    private static final int DIAGRAM_TO_TOGGLE_GAP = 4;
    private static final int PANEL_SIDE_PADDING = 4;
    private static final int FORCE_TOGGLE_BOTTOM_INSET = 0;
    private static final int PANEL_TOP_CONTENT_Y = 24;
    private static final int FORCE_TOGGLE_WIDTH = 72;
    private static final int FORCE_TOGGLE_HEIGHT = 10;
    private static final int FORCE_TOGGLE_ROW_GAP = 2;
    private static final float FORCE_TOGGLE_LABEL_SCALE = 0.75F;
    private static final int DIAGRAM_CONTROL_BUTTON_RIGHT = 20;
    private static final int DIAGRAM_CONTROL_BUTTON_BOTTOM = 72;
    private static final int DIAGRAM_CONTROL_BUTTON_SPACING = 20;
    private static final int BUTTON_COLOR = WindTunnelLdlib2Theme.BUTTON;
    private static final int DULL_BUTTON_COLOR = WindTunnelLdlib2Theme.BUTTON_DULL;
    private static final int TOOLTIP_LABEL_COLOR = -4025475;
    private static final float FBO_BRIGHTNESS = 2.1F;
    private static final Vector3d CAMERA_POSITION = new Vector3d();
    private static final Map<DiagramViewKey, DiagramViewState> STORED_VIEW_STATES = new HashMap<>();
    private static final ResourceLocation DIAGRAM_CONTROLS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(WindTunnelMod.MOD_ID, "textures/gui/diagram_controls.png");
    private static final int DIAGRAM_CONTROLS_TEX_WIDTH = 32;
    private static final int DIAGRAM_CONTROLS_TEX_HEIGHT = 32;

    private final Source source;
    private final BlockPos pos;
    private final java.util.function.BooleanSupplier activeSupplier;
    private final SharedDiagramViewport viewport = new SharedDiagramViewport();
    private final List<Component> tooltipList = new ArrayList<>();
    private final List<ClientSubLevel> renderedSubLevels = new ArrayList<>();
    private final List<DiagramButtonHitbox> buttons = new ArrayList<>();
    private final DiagramViewportElement diagramViewport = new DiagramViewportElement();
    private final UIElement forceToggleGrid = createForceToggleGrid();

    private WindTunnelMountDiagramData diagramData = WindTunnelMountDiagramData.EMPTY;
    private DiagramConfig diagramConfig;
    private boolean forceGroupSelectionTouched;
    private boolean initialDiagramRequested;
    private int diagramRequestCooldown;
    private AdvancedFbo fbo;
    private int fboWidth;
    private int fboHeight;
    private ClientSubLevel referenceSubLevel;

    public WindTunnelLdlib2DiagramElement(Source source, BlockPos pos, java.util.function.BooleanSupplier activeSupplier) {
        this.source = source;
        this.pos = pos;
        this.activeSupplier = activeSupplier;
        layout(l -> l.minWidth(MIN_PANEL_WIDTH)
                .minHeight(MIN_PANEL_HEIGHT)
                .heightStretch()
                .flexGrow(1)
                .flexShrink(1)
                .flexDirection(FlexDirection.COLUMN));
        style(s -> s.overflowVisible(true));
        addChild(Objects.requireNonNull(diagramViewport));
        addChild(Objects.requireNonNull(forceToggleGrid));
        ensureDiagramConfig();
        addDiagramHitboxes();
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        updateDiagramStackLayout();
    }

    @Override
    public BlockPos windtunnel$getDiagramPos() {
        return pos;
    }

    @Override
    public void windtunnel$updateDiagramData(WindTunnelMountDiagramData data) {
        UUID previousReferenceId = diagramData.referenceSubLevelId();
        List<UUID> previousRenderedIds = diagramData.renderedSubLevelIds();
        diagramData = data == null ? WindTunnelMountDiagramData.EMPTY : data;
        syncEnabledForceGroupsWithData();
        if (!Objects.equals(previousReferenceId, diagramData.referenceSubLevelId())
                || !previousRenderedIds.equals(diagramData.renderedSubLevelIds())) {
            viewport.reset();
            restoreStoredViewState();
        }
        resolveDiagramTargets();
    }

    @Override
    public void screenTick() {
        super.screenTick();
        if (!initialDiagramRequested) {
            initialDiagramRequested = true;
            requestDiagramData();
            return;
        }
        if (diagramRequestCooldown > 0) {
            diagramRequestCooldown--;
        }
        if (diagramRequestCooldown <= 0 && activeSupplier.getAsBoolean()) {
            requestDiagramData();
        }
    }

    @Override
    protected void onRemoved() {
        freeFramebuffers();
        super.onRemoved();
    }

    @Override
    public void drawContents(@NotNull GUIContext ctx) {
        tooltipList.clear();
        super.drawContents(Objects.requireNonNull(ctx));
        if (!tooltipList.isEmpty()) {
            ctx.graphics.renderComponentTooltip(Objects.requireNonNull(Minecraft.getInstance().font), Objects.requireNonNull(tooltipList), ctx.mouseX, ctx.mouseY);
        }
    }

    @Override
    public void drawBackgroundAdditional(@NotNull GUIContext ctx) {
        GuiGraphics graphics = ctx.graphics;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(getPositionX(), getPositionY(), 0.0F);

        drawPanelBackground(graphics);

        pose.popPose();
    }

    private void drawPanelBackground(GuiGraphics graphics) {
        int width = Math.round(getSizeWidth());
        int height = Math.round(getSizeHeight());
        graphics.fill(0, 0, width, height, WindTunnelLdlib2Theme.PANEL_BACKGROUND);
        graphics.fill(0, 0, width, 1, WindTunnelLdlib2Theme.SECTION_LINE);
        graphics.fill(0, height - 1, width, height, WindTunnelLdlib2Theme.SECTION_LINE);
        graphics.drawString(Objects.requireNonNull(Minecraft.getInstance().font),
                Objects.requireNonNull(Component.translatable("block.windtunnel.wind_tunnel_mount.diagram")),
                PANEL_SIDE_PADDING, 8, WindTunnelLdlib2Theme.TEXT, false);
    }

    private void renderSubLevelFooter(GuiGraphics graphics) {
        if (referenceSubLevel == null) {
            return;
        }
        String text = referenceSubLevel.getName();
        if (text == null || text.isEmpty()) {
            return;
        }
        var font = Minecraft.getInstance().font;
        int diagramWidth = diagramWidth();
        int diagramHeight = diagramHeight();
        int footerW = font.width(text);
        graphics.fill(diagramWidth - footerW - 7, diagramHeight - 14,
                diagramWidth - 4, diagramHeight - 3, WindTunnelLdlib2Theme.PANEL_BACKGROUND);
        graphics.drawString(font, text, diagramWidth - footerW - 5, diagramHeight - 12,
                WindTunnelLdlib2Theme.TEXT, false);
    }

    private void updateButtonState() {
        for (DiagramButtonHitbox button : buttons) {
            button.visible = true;
            button.active = true;
            button.updatePosition();
        }
        DiagramButtonHitbox turnUp = buttonById("turn_up");
        DiagramButtonHitbox turnDown = buttonById("turn_down");
        if (turnDown != null) {
            turnDown.visible = turnDown.active = diagramConfig.pitch() < 45.0D;
        }
        if (turnUp != null) {
            turnUp.visible = turnUp.active = diagramConfig.pitch() > -45.0D;
        }
    }

    private void renderButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        double localX = mouseX - getPositionX();
        double localY = mouseY - getPositionY();
        for (DiagramButtonHitbox button : buttons) {
            if (!button.visible) {
                continue;
            }
            boolean hovered = button.contains(localX, localY);
            int color = (hovered || button.iconSwitch.getAsBoolean()) ? BUTTON_COLOR : DULL_BUTTON_COLOR;
            drawDiagramButton(graphics, button, color, hovered);
            if (hovered && button.tooltip != null) {
                tooltipList.add(button.tooltip.get());
            }
        }
        graphics.flush();
    }

    private void populateDiagramFbos(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (referenceSubLevel == null || renderedSubLevels.isEmpty() || fbo == null || VeilLevelPerspectiveRenderer.isRenderingPerspective()) {
            return;
        }
        if (viewport.shouldDeferRender(minecraft.getTimer().getRealtimeDeltaTicks())) {
            return;
        }

        int diagramWidth = diagramWidth();
        int diagramHeight = diagramHeight();
        BoundsSummary bounds = computeBoundsSummary(partialTick);
        viewport.fitToBounds(bounds.center(), bounds.radius(), diagramWidth, diagramHeight);
        Pose3dc referenceRenderPose = referenceSubLevel.renderPose(partialTick);
        referenceRenderPose.transformPosition(CAMERA_POSITION.set(viewport.localCameraPosition()));
        Quaternionf renderOrientation = viewport.buildRenderOrientation(referenceRenderPose);
        fbo.clear();
        drawGroup(renderedSubLevels, referenceSubLevel.getLevel(), partialTick, renderOrientation, viewport.projectionMatrix(), CAMERA_POSITION,
                fbo);
    }

    private BoundsSummary computeBoundsSummary(float partialTick) {
        Pose3dc referenceRenderPose = referenceSubLevel.renderPose(partialTick);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (ClientSubLevel subLevel : renderedSubLevels) {
            BoundingBox3ic box = subLevel.getPlot().getBoundingBox();
            if (box == null) {
                continue;
            }
            int[] xs = {box.minX(), box.maxX() + 1};
            int[] ys = {box.minY(), box.maxY() + 1};
            int[] zs = {box.minZ(), box.maxZ() + 1};
            Pose3dc subRenderPose = subLevel.renderPose(partialTick);
            for (int x : xs) {
                for (int y : ys) {
                    for (int z : zs) {
                        Vector3d world = subRenderPose.transformPosition(new Vector3d(x, y, z), new Vector3d());
                        Vector3d local = referenceRenderPose.transformPositionInverse(world, new Vector3d());
                        minX = Math.min(minX, local.x);
                        minY = Math.min(minY, local.y);
                        minZ = Math.min(minZ, local.z);
                        maxX = Math.max(maxX, local.x);
                        maxY = Math.max(maxY, local.y);
                        maxZ = Math.max(maxZ, local.z);
                    }
                }
            }
        }

        if (!Double.isFinite(minX)) {
            minX = minY = minZ = -1.0D;
            maxX = maxY = maxZ = 1.0D;
        }

        float radius = (float) (Math.max(Math.max(maxX - minX, maxY - minY), maxZ - minZ) * 0.55D);
        radius = Math.max(radius, 2.0F);
        return new BoundsSummary(new Vector3d((minX + maxX) * 0.5D, (minY + maxY) * 0.5D, (minZ + maxZ) * 0.5D), radius);
    }

    private static void drawGroup(List<ClientSubLevel> subLevels, net.minecraft.client.multiplayer.ClientLevel level, float partialTicks,
                                  Quaternionf localOrientation, Matrix4f projectionMat, Vector3d cameraPos, AdvancedFbo fbo) {
        fbo.bind(true);
        SimpleSubLevelGroupRenderer.renderGroup(level, subLevels, fbo, new Matrix4f(), projectionMat, cameraPos,
                new Quaternionf(localOrientation), partialTicks, true);
    }

    private void renderArrows(GuiGraphics graphics, int mouseX, int mouseY) {
        if (diagramData.isEmpty() || referenceSubLevel == null) {
            return;
        }
        int diagramWidth = diagramWidth();
        int diagramHeight = diagramHeight();

        double maxArrowLengthSquared = 0.0D;
        Map<ForceGroup, List<ForceClusterFinder.Cluster>> clusters = new HashMap<>();
        for (ResourceLocation groupId : diagramConfig.enabledForceGroups()) {
            ForceGroup group = ForceGroups.REGISTRY.get(groupId);
            if (group == null) {
                continue;
            }
            List<PointForce> forces = diagramData.forces().get(group);
            if (forces == null || forces.isEmpty()) {
                continue;
            }
            List<ForceClusterFinder.Cluster> cluster = diagramConfig.mergeForces()
                    ? ForceClusterFinder.getMergedClusters(forces)
                    : ForceClusterFinder.passThrough(forces);
            clusters.put(group, cluster);
            for (ForceClusterFinder.Cluster pointForce : cluster) {
                maxArrowLengthSquared = Math.max(maxArrowLengthSquared, pointForce.force().lengthSquared());
            }
        }

        for (ResourceLocation groupId : diagramConfig.enabledForceGroups()) {
            ForceGroup group = ForceGroups.REGISTRY.get(groupId);
            if (group == null) {
                continue;
            }
            List<ForceClusterFinder.Cluster> cluster = clusters.get(group);
            if (cluster == null) {
                continue;
            }
            for (ForceClusterFinder.Cluster pointForce : cluster) {
                renderForceArrow(graphics, group, pointForce, Math.sqrt(maxArrowLengthSquared),
                        (int) (mouseX - diagramViewport.getPositionX()),
                        (int) (mouseY - diagramViewport.getPositionY()),
                        viewport.localOrientation(), viewport.localCameraPosition(), viewport.projectionMatrix(),
                        diagramWidth, diagramHeight);
            }
        }
    }

    private void renderForceArrow(GuiGraphics graphics, ForceGroup forceGroup, ForceClusterFinder.Cluster pointForce, double maxArrowLength,
                                  int mouseX, int mouseY, Quaternionfc orientation, Vector3dc cameraPos, Matrix4fc projMatrix,
                                  int areaWidth, int areaHeight) {
        double forceMagnitude = pointForce.force().length();
        if (forceMagnitude <= 0.01D || viewport.viewportRadius() <= 0.0F || maxArrowLength <= 0.0D) {
            return;
        }

        Vector3d globalDirection = pointForce.force().normalize(new Vector3d());
        Vector3d forceOffset = globalDirection.mul(Math.max(0.25D, forceMagnitude / maxArrowLength) * viewport.viewportRadius() * 0.5D,
                new Vector3d());
        Vector2d originCoords = WindTunnelDiagramProjection.getScreenCoords(new Vector3d(pointForce.pos()), orientation, cameraPos, projMatrix, areaWidth, areaHeight);
        if (!canDrawArrowAt((int) originCoords.x, (int) originCoords.y, areaWidth, areaHeight)) {
            return;
        }

        Vector2d mousePos = new Vector2d(mouseX, mouseY);
        int color = 0xFF000000 | forceGroup.color();
        int shadowColor = -396578;
        double facingDot = orientation.transformInverse(globalDirection, new Vector3d()).dot(0.0D, 0.0D, -1.0D);
        if (Math.abs(facingDot) > 0.85D) {
            PoseStack ps = graphics.pose();
            ps.pushPose();
            ps.translate(0.0D, 0.0D, 1.0D);
            if (mousePos.sub(originCoords, new Vector2d()).lengthSquared() < 64.0D) {
                addForceArrowTooltip(forceGroup, pointForce.groupSize().getValue(), forceMagnitude, color);
            }
            if (facingDot < 0.0D) {
                drawPageArrow(graphics, (int) originCoords.x, (int) originCoords.y, color, shadowColor, true);
            } else {
                drawPageArrow(graphics, (int) originCoords.x, (int) originCoords.y, color, shadowColor, false);
            }
            ps.popPose();
            return;
        }

        Vector2d resultCoords = WindTunnelDiagramProjection.getScreenCoords(pointForce.pos().add(forceOffset, new Vector3d()), orientation, cameraPos, projMatrix, areaWidth, areaHeight);
        Vector2d arrowDir = resultCoords.sub(originCoords, new Vector2d());
        float arrowLength = (float) arrowDir.length();
        if (arrowLength <= 1.0E-4F) {
            return;
        }
        arrowDir.div(arrowLength);
        while (arrowLength > 0.0F && !canDrawArrowAt((int) resultCoords.x, (int) resultCoords.y, areaWidth, areaHeight)) {
            resultCoords.fma(-3.0D, arrowDir);
            arrowLength -= 3.0F;
        }

        int x1 = (int) originCoords.x();
        int y1 = (int) originCoords.y();
        int x2 = (int) resultCoords.x();
        int y2 = (int) resultCoords.y();
        BufferSource bufferSource = graphics.bufferSource();
        VertexConsumer builder = bufferSource.getBuffer(Objects.requireNonNull(RenderType.gui()));
        Matrix4f pose = Objects.requireNonNull(graphics.pose().last().pose());
        Vector2d arrowLeft = new Vector2d(-arrowDir.y(), arrowDir.x()).mul(4.0D);
        Vector2d arrowRight = new Vector2d(arrowDir.y(), -arrowDir.x()).mul(4.0D);
        boolean drawArrow = originCoords.distanceSquared(resultCoords) > 36.0D;
        double distanceAlongLine = mousePos.sub(originCoords, new Vector2d()).dot(arrowDir);
        distanceAlongLine = Mth.clamp(distanceAlongLine, 0.0D, arrowLength);
        boolean displayTooltip = new Vector2d(originCoords).fma(distanceAlongLine, arrowDir).distance(mousePos) < 5.0D;
        if (displayTooltip) {
            addForceArrowTooltip(forceGroup, pointForce.groupSize().getValue(), forceMagnitude, color);
        }

        int inflation = 3;
        builder.addVertex(pose, x1 - inflation, y1 - inflation, 1.0F).setColor(shadowColor);
        builder.addVertex(pose, x1 - inflation, y1 + 1.0F + inflation, 1.0F).setColor(shadowColor);
        builder.addVertex(pose, x1 + 1.0F + inflation, y1 + 1.0F + inflation, 1.0F).setColor(shadowColor);
        builder.addVertex(pose, x1 + 1.0F + inflation, y1 - inflation, 1.0F).setColor(shadowColor);
        if (drawArrow) {
            drawLine(builder, pose, x2, y2, (int) (x2 - arrowDir.x * 6.0D + arrowLeft.x), (int) (y2 - arrowDir.y * 6.0D + arrowLeft.y), shadowColor, 1);
            drawLine(builder, pose, x2, y2, (int) (x2 - arrowDir.x * 6.0D + arrowRight.x), (int) (y2 - arrowDir.y * 6.0D + arrowRight.y), shadowColor, 1);
            drawLine(builder, pose, x1, y1, x2, y2, shadowColor, 1);
            drawLine(builder, pose, x2, y2, (int) (x2 - arrowDir.x * 6.0D + arrowLeft.x), (int) (y2 - arrowDir.y * 6.0D + arrowLeft.y), color, 0);
            drawLine(builder, pose, x2, y2, (int) (x2 - arrowDir.x * 6.0D + arrowRight.x), (int) (y2 - arrowDir.y * 6.0D + arrowRight.y), color, 0);
            drawLine(builder, pose, x1, y1, x2, y2, color, 0);
        }

        int pointInflation = 2;
        builder.addVertex(pose, x1 - pointInflation, y1 - pointInflation, 1.0F).setColor(color);
        builder.addVertex(pose, x1 - pointInflation, y1 + 1.0F + pointInflation, 1.0F).setColor(color);
        builder.addVertex(pose, x1 + 1.0F + pointInflation, y1 + 1.0F + pointInflation, 1.0F).setColor(color);
        builder.addVertex(pose, x1 + 1.0F + pointInflation, y1 - pointInflation, 1.0F).setColor(color);
    }

    private void renderCenterOfMass(GuiGraphics graphics) {
        int diagramWidth = diagramWidth();
        int diagramHeight = diagramHeight();
        Vector2d screenCoords = WindTunnelDiagramProjection.getScreenCoords(new Vector3d(diagramData.centerOfMassLocal()),
                viewport.localOrientation(), viewport.localCameraPosition(), viewport.projectionMatrix(),
                diagramWidth, diagramHeight);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0D, 0.0D, 1.0D);
        drawCenterOfMassMarker(graphics, (int) screenCoords.x, (int) screenCoords.y);
        pose.popPose();
    }

    private void addForceArrowTooltip(ForceGroup forceGroup, int forceCount, double forceMagnitude, int color) {
        Component forceNameText = forceGroup.name().copy().withStyle(style -> style.withColor(color));
        Component forceMagnitudeText = Component.translatable("block.windtunnel.diagram.force_arrow_magnitude",
                String.format(Locale.ROOT, "%,.2f", forceMagnitude)).withStyle(ChatFormatting.WHITE);
        if (forceCount > 1) {
            Component countText = Component.translatable("block.windtunnel.diagram.merging_numeral", Integer.toString(forceCount))
                    .withStyle(ChatFormatting.WHITE);
            tooltipList.add(Component.translatable("block.windtunnel.diagram.merged_force_arrow",
                    countText, forceNameText, forceMagnitudeText).withStyle(style -> style.withColor(TOOLTIP_LABEL_COLOR)));
        } else {
            tooltipList.add(Component.translatable("block.windtunnel.diagram.force_arrow", forceNameText, forceMagnitudeText)
                    .withStyle(style -> style.withColor(TOOLTIP_LABEL_COLOR)));
        }
    }

    private void requestDiagramData() {
        diagramRequestCooldown = DIAGRAM_REFRESH_INTERVAL;
        if (source == Source.MOUNT) {
            PacketDistributor.sendToServer(new RequestWindTunnelMountDiagramPayload(pos));
        } else {
            PacketDistributor.sendToServer(new RequestAirflowInjectorDiagramPayload(pos));
        }
    }

    private void resolveDiagramTargets() {
        renderedSubLevels.clear();
        referenceSubLevel = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || diagramData.isEmpty()) {
            return;
        }
        ClientSubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
        if (container == null) {
            return;
        }
        if (diagramData.referenceSubLevelId() != null && container.getSubLevel(diagramData.referenceSubLevelId()) instanceof ClientSubLevel subLevel) {
            referenceSubLevel = subLevel;
        }
        for (UUID id : diagramData.renderedSubLevelIds()) {
            if (container.getSubLevel(id) instanceof ClientSubLevel subLevel) {
                renderedSubLevels.add(subLevel);
            }
        }
        if (referenceSubLevel != null && renderedSubLevels.stream().noneMatch(sub -> sub.getUniqueId().equals(referenceSubLevel.getUniqueId()))) {
            renderedSubLevels.add(0, referenceSubLevel);
        }
    }

    private void ensureDiagramConfig() {
        if (diagramConfig != null) {
            return;
        }
        List<ResourceLocation> enabledForceGroups = new ArrayList<>();
        for (ResourceLocation groupId : ForceGroups.REGISTRY.keySet()) {
            ForceGroup group = ForceGroups.REGISTRY.get(groupId);
            if (group != null && group.defaultDisplayed()) {
                enabledForceGroups.add(groupId);
            }
        }
        diagramConfig = new DiagramConfig(enabledForceGroups, false, false, 0.0D, 0.0D,
                new DiagramConfig.NoteConfigs(new BoundingBox3d(), 0.0D, 0.0D, false));
        restoreStoredViewState();
        updateViewportOrientation();
    }

    private void syncEnabledForceGroupsWithData() {
        if (diagramConfig == null || forceGroupSelectionTouched || diagramData.forces().isEmpty()) {
            return;
        }

        for (ForceGroup group : diagramData.forces().keySet()) {
            ResourceLocation groupId = ForceGroups.REGISTRY.getKey(Objects.requireNonNull(group));
            if (groupId != null && !diagramConfig.enabledForceGroups().contains(groupId)) {
                diagramConfig.enabledForceGroups().add(groupId);
            }
        }
    }

    private void updateViewportOrientation() {
        viewport.updateOrientation(diagramConfig.yaw(), diagramConfig.pitch(), diagramWidth(), diagramHeight());
    }

    private void rotateDiagram(int yawSteps, int pitchSteps) {
        if (diagramConfig.pitch() > 45.0D) {
            yawSteps = -yawSteps;
        }
        diagramConfig.setYaw(diagramConfig.yaw() + yawSteps * 90.0D);
        diagramConfig.setPitch(Mth.clamp(diagramConfig.pitch() + pitchSteps * 90.0D, -90.0D, 90.0D));
        Minecraft.getInstance().getSoundManager().play(Objects.requireNonNull(SimpleSoundInstance.forUI(
                Objects.requireNonNull(SoundEvents.VILLAGER_WORK_CARTOGRAPHER), 1.0F)));
        updateViewportOrientation();
        storeViewState();
    }

    private DiagramIcon getMergeIcon() {
        return diagramConfig.mergeForces() ? DiagramIcon.FORCES_MERGED : DiagramIcon.FORCES_SEPARATED;
    }

    private int diagramWidth() {
        return Math.max(MIN_DIAGRAM_WIDTH, Math.round(diagramViewport.getSizeWidth()));
    }

    private int diagramHeight() {
        return Math.max(MIN_DIAGRAM_HEIGHT, Math.round(diagramViewport.getSizeHeight()));
    }

    private void ensureFramebufferSize(int width, int height) {
        if (fbo == null || fboWidth != width || fboHeight != height) {
            createFramebuffers(width, height);
            viewport.markDirty();
            updateViewportOrientation();
        }
    }

    private void panDiagram(double mouseX, double mouseY, double previousMouseX, double previousMouseY) {
        viewport.pan(mouseX, mouseY, previousMouseX, previousMouseY,
                (int) diagramViewport.getPositionX(), (int) diagramViewport.getPositionY(),
                diagramWidth(), diagramHeight());
        storeViewState();
    }

    private void zoomDiagram(double mouseX, double mouseY, double scrollY) {
        viewport.zoom(mouseX, mouseY, scrollY,
                (int) diagramViewport.getPositionX(), (int) diagramViewport.getPositionY(),
                diagramWidth(), diagramHeight());
        storeViewState();
    }

    private void resetDiagramPanAndZoom() {
        viewport.resetPanAndZoom();
        storeViewState();
    }

    private void createFramebuffers(int width, int height) {
        freeFramebuffers();
        fboWidth = Math.max(1, width);
        fboHeight = Math.max(1, height);
        fbo = AdvancedFbo.withSize(fboWidth, fboHeight).addColorTextureBuffer().setDepthTextureBuffer().build(true);
    }

    private void freeFramebuffers() {
        if (fbo != null) {
            fbo.free();
            fbo = null;
        }
        fboWidth = 0;
        fboHeight = 0;
    }

    private void addDiagramHitboxes() {
        buttons.clear();
        buttons.add(new DiagramButtonHitbox("merge", getMergeIcon(), this::diagramControlButtonX, this::diagramControlButtonTop,
                () -> {
                    diagramConfig.setMergeForces(!diagramConfig.mergeForces());
                    DiagramButtonHitbox merge = buttonById("merge");
                    if (merge != null) {
                        merge.icon = getMergeIcon();
                    }
                },
                () -> {
                    Component c = Component.translatable("block.windtunnel.diagram.merge_forces").withStyle(style -> style.withColor(TOOLTIP_LABEL_COLOR))
                            .append(Objects.requireNonNull(Component.literal(" ")))
                            .append(Objects.requireNonNull(Component.translatable(diagramConfig.mergeForces()
                                    ? "block.windtunnel.diagram.merged"
                                    : "block.windtunnel.diagram.unmerged").withStyle(ChatFormatting.WHITE)));
                    return Objects.requireNonNull(c);
                },
                () -> false));
        buttons.add(new DiagramButtonHitbox("center_mass", DiagramIcon.CENTER_OF_MASS_TOGGLE,
                this::diagramControlButtonX, () -> diagramControlButtonTop() + DIAGRAM_CONTROL_BUTTON_SPACING,
                () -> diagramConfig.setDisplayCenterOfMass(!diagramConfig.displayCenterOfMass()),
                () -> {
                    Component c = Component.translatable("block.windtunnel.diagram.center_of_mass").withStyle(style -> style.withColor(TOOLTIP_LABEL_COLOR))
                            .append(Objects.requireNonNull(Component.literal(" ")))
                            .append(Objects.requireNonNull(Component.translatable(diagramConfig.displayCenterOfMass()
                                    ? "block.windtunnel.diagram.shown"
                                    : "block.windtunnel.diagram.hidden").withStyle(ChatFormatting.WHITE)));
                    return Objects.requireNonNull(c);
                },
                () -> diagramConfig.displayCenterOfMass()));
        buttons.add(new DiagramButtonHitbox("mass", DiagramIcon.MASS,
                this::diagramControlButtonX, () -> diagramControlButtonTop() + DIAGRAM_CONTROL_BUTTON_SPACING * 2,
                () -> {},
                () -> {
                    Component c = Component.translatable("block.windtunnel.diagram.total_mass").withStyle(style -> style.withColor(TOOLTIP_LABEL_COLOR))
                            .append(Objects.requireNonNull(Component.literal(" ")))
                            .append(Objects.requireNonNull(Component.translatable("block.windtunnel.diagram.mass",
                                    String.format(Locale.ROOT, "%,.2f", diagramData.mass())).withStyle(ChatFormatting.WHITE)));
                    return Objects.requireNonNull(c);
                },
                () -> true).setActive(false));
        buttons.add(new DiagramButtonHitbox("turn_up", DiagramIcon.TURN_UP,
                () -> Math.round(diagramViewport.getLayoutX() + diagramWidth() - 20),
                () -> Math.round(diagramViewport.getLayoutY() + 8),
                () -> rotateDiagram(0, -1), null, () -> false));
        buttons.add(new DiagramButtonHitbox("turn_down", DiagramIcon.TURN_DOWN,
                () -> Math.round(diagramViewport.getLayoutX() + diagramWidth() - 20),
                () -> Math.round(diagramViewport.getLayoutY() + 22),
                () -> rotateDiagram(0, 1), null, () -> false));
        buttons.add(new DiagramButtonHitbox("turn_left", DiagramIcon.TURN_LEFT,
                () -> Math.round(diagramViewport.getLayoutX() + diagramWidth() - 28),
                () -> Math.round(diagramViewport.getLayoutY() + 12),
                () -> rotateDiagram(1, 0), null, () -> false));
        buttons.add(new DiagramButtonHitbox("turn_right", DiagramIcon.TURN_RIGHT,
                () -> Math.round(diagramViewport.getLayoutX() + diagramWidth() - 13),
                () -> Math.round(diagramViewport.getLayoutY() + 12),
                () -> rotateDiagram(-1, 0), null, () -> false));

        rebuildForceToggleGrid();
    }

    private void restoreStoredViewState() {
        DiagramViewState state = STORED_VIEW_STATES.get(viewStateKey());
        if (state == null || diagramConfig == null) {
            return;
        }
        diagramConfig.setYaw(state.yaw());
        diagramConfig.setPitch(state.pitch());
        viewport.restore(state.viewportState());
    }

    private void storeViewState() {
        if (diagramConfig == null) {
            return;
        }
        STORED_VIEW_STATES.put(viewStateKey(), new DiagramViewState(diagramConfig.yaw(), diagramConfig.pitch(), viewport.snapshot()));
    }

    private DiagramViewKey viewStateKey() {
        return new DiagramViewKey(source, pos);
    }

    private int diagramControlButtonX() {
        return Math.round(diagramViewport.getLayoutX() + diagramWidth() - DIAGRAM_CONTROL_BUTTON_RIGHT);
    }

    private int diagramControlButtonTop() {
        return Math.round(diagramViewport.getLayoutY() + diagramHeight() - DIAGRAM_CONTROL_BUTTON_BOTTOM);
    }

    private void updateDiagramStackLayout() {
        int panelHeight = Math.round(getSizeHeight());
        if (panelHeight <= 0) {
            return;
        }
        int toggleHeight = forceToggleAreaHeight();
        int toggleTop = panelHeight - FORCE_TOGGLE_BOTTOM_INSET - toggleHeight;
        int viewportHeight = Math.max(MIN_DIAGRAM_HEIGHT, toggleTop - PANEL_TOP_CONTENT_Y - DIAGRAM_TO_TOGGLE_GAP);
        diagramViewport.layout(layout -> layout.height(viewportHeight).maxHeightAuto());
        forceToggleGrid.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
                .left(PANEL_SIDE_PADDING)
                .right(PANEL_SIDE_PADDING)
                .bottom(FORCE_TOGGLE_BOTTOM_INSET)
                .height(toggleHeight));
    }

    private int forceToggleAreaHeight() {
        int panelWidth = Math.round(getSizeWidth());
        if (panelWidth <= 0) {
            panelWidth = MIN_PANEL_WIDTH;
        }
        int availableWidth = Math.max(FORCE_TOGGLE_WIDTH, panelWidth - PANEL_SIDE_PADDING * 2);
        int columns = Math.max(1, availableWidth / FORCE_TOGGLE_WIDTH);
        int groupCount = Math.max(1, ForceGroups.count());
        int rows = (groupCount + columns - 1) / columns;
        return rows * FORCE_TOGGLE_HEIGHT + Math.max(0, rows - 1) * FORCE_TOGGLE_ROW_GAP;
    }

    private UIElement createForceToggleGrid() {
        return new UIElement()
                .layout(layout -> layout.widthStretch()
                        .height(forceToggleAreaHeight())
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .justifyContent(AlignContent.SPACE_BETWEEN)
                        .gapRow(FORCE_TOGGLE_ROW_GAP)
                        .flexShrink(0));
    }

    private void rebuildForceToggleGrid() {
        forceToggleGrid.clearAllChildren();
        for (ResourceLocation groupId : ForceGroups.REGISTRY.keySet()) {
            ForceGroup forceGroup = ForceGroups.REGISTRY.get(groupId);
            if (forceGroup == null) {
                continue;
            }
            forceToggleGrid.addChild(new ForceToggleElement(groupId, forceGroup));
        }
    }

    private DiagramButtonHitbox buttonById(String id) {
        for (DiagramButtonHitbox button : buttons) {
            if (button.id.equals(id)) {
                return button;
            }
        }
        return null;
    }

    private DiagramButtonHitbox findButton(double localX, double localY) {
        for (DiagramButtonHitbox button : buttons) {
            if (button.visible && button.active && button.contains(localX, localY)) {
                return button;
            }
        }
        return null;
    }

    private boolean isForceGroupEnabled(ResourceLocation groupId) {
        return diagramConfig.enabledForceGroups().contains(groupId);
    }

    private void toggleActive(ResourceLocation groupId) {
        if (isForceGroupEnabled(groupId)) {
            diagramConfig.enabledForceGroups().remove(groupId);
        } else {
            diagramConfig.enabledForceGroups().add(groupId);
        }
    }

    private int countForces(ForceGroup group) {
        List<PointForce> forces = diagramData.forces().get(group);
        return forces == null ? 0 : forces.size();
    }

    private static void drawDiagramFrame(GuiGraphics graphics, int width, int height) {
        graphics.fill(-1, -1, width + 1, height + 1, 0xFFC7B797);
        graphics.fill(0, 0, width, height, 0xFFEFE6D1);
        graphics.fill(3, 3, width - 3, height - 3, 0xFFE7DDC6);
        graphics.fill(4, 4, width - 4, height - 4, 0x00000000);
    }

    @SuppressWarnings("null")
    private static void renderFbo(GuiGraphics graphics, AdvancedFbo fbo, int width, int height) {
        int id = fbo.getColorTextureAttachment(0).getId();
        RenderSystem.setShaderTexture(0, id);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(FBO_BRIGHTNESS, FBO_BRIGHTNESS, FBO_BRIGHTNESS, 1.0F);
        RenderSystem.enableBlend();
        Matrix4f matrix = Objects.requireNonNull(graphics.pose().last().pose());
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        buffer.addVertex(matrix, 0.0F, 0.0F, 0.1F).setUv(0.0F, 1.0F).setColor(0xFFFFFFFF);
        buffer.addVertex(matrix, 0.0F, height, 0.1F).setUv(0.0F, 0.0F).setColor(0xFFFFFFFF);
        buffer.addVertex(matrix, width, height, 0.1F).setUv(1.0F, 0.0F).setColor(0xFFFFFFFF);
        buffer.addVertex(matrix, width, 0.0F, 0.1F).setUv(1.0F, 1.0F).setColor(0xFFFFFFFF);
        BufferUploader.drawWithShader(Objects.requireNonNull(buffer.buildOrThrow()));
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawDiagramButton(GuiGraphics graphics, DiagramButtonHitbox button, int color, boolean hovered) {
        if (button.icon.isRotationControl()) {
            drawIcon(graphics, button.icon, button.x, button.y, color, hovered);
            return;
        }
        int x = button.x - 1;
        int y = button.y - 1;
        graphics.fill(x, y, x + button.width(), y + button.height(), hovered ? 0xFFFFF7E8 : 0xFFEFE6D1);
        graphics.fill(x, y, x + button.width(), y + 1, 0xFFFFF7E8);
        graphics.fill(x, y, x + 1, y + button.height(), 0xFFFFF7E8);
        graphics.fill(x + button.width() - 1, y, x + button.width(), y + button.height(), 0xFFC7B797);
        graphics.fill(x, y + button.height() - 1, x + button.width(), y + button.height(), 0xFFC7B797);
        drawIcon(graphics, button.icon, button.x, button.y, color, hovered);
    }

    private static void drawIcon(GuiGraphics graphics, DiagramIcon icon, int x, int y, int color, boolean hovered) {
        switch (icon) {
            case FORCES_SEPARATED -> {
                Matrix4f iconPose1 = Objects.requireNonNull(graphics.pose().last().pose());
                drawLine(graphics.bufferSource().getBuffer(Objects.requireNonNull(RenderType.gui())), iconPose1, x + 3, y + 11, x + 13, y + 3, color, 0);
                drawLine(graphics.bufferSource().getBuffer(Objects.requireNonNull(RenderType.gui())), iconPose1, x + 3, y + 5, x + 13, y + 13, color, 0);
                drawSmallArrowHead(graphics, x + 13, y + 3, 1, -1, color);
                drawSmallArrowHead(graphics, x + 13, y + 13, 1, 1, color);
            }
            case FORCES_MERGED -> {
                Matrix4f iconPose2 = Objects.requireNonNull(graphics.pose().last().pose());
                drawLine(graphics.bufferSource().getBuffer(Objects.requireNonNull(RenderType.gui())), iconPose2, x + 3, y + 11, x + 13, y + 8, color, 0);
                drawLine(graphics.bufferSource().getBuffer(Objects.requireNonNull(RenderType.gui())), iconPose2, x + 3, y + 5, x + 13, y + 8, color, 0);
                drawSmallArrowHead(graphics, x + 13, y + 8, 1, 0, color);
            }
            case CENTER_OF_MASS_TOGGLE -> drawCenterOfMassMarker(graphics, x + 8, y + 8);
            case MASS -> {
                graphics.fill(x + 4, y + 9, x + 12, y + 12, color);
                graphics.fill(x + 5, y + 6, x + 11, y + 9, color);
                graphics.fill(x + 6, y + 4, x + 10, y + 6, color);
            }
            case TURN_UP, TURN_DOWN, TURN_LEFT, TURN_RIGHT -> drawTexturedTurnIcon(graphics, icon, x, y, color, hovered);
        }
    }

    private static void drawTexturedTurnIcon(GuiGraphics graphics, DiagramIcon icon, int x, int y, int color, boolean hovered) {
        RenderSystem.enableBlend();
        graphics.blit(Objects.requireNonNull(DIAGRAM_CONTROLS_TEXTURE), x, y, icon.u(), icon.v(hovered), icon.width, icon.height,
                DIAGRAM_CONTROLS_TEX_WIDTH, DIAGRAM_CONTROLS_TEX_HEIGHT);
        graphics.fill(x, y, x + icon.width, y + icon.height, color & 0x00FFFFFF);
        RenderSystem.disableBlend();
    }

    private static void drawSmallArrowHead(GuiGraphics graphics, int x, int y, int xSign, int ySign, int color) {
        VertexConsumer builder = graphics.bufferSource().getBuffer(Objects.requireNonNull(RenderType.gui()));
        Matrix4f pose = Objects.requireNonNull(graphics.pose().last().pose());
        drawLine(builder, pose, x, y, x - 3 * xSign, y, color, 0);
        drawLine(builder, pose, x, y, x, y - 3 * ySign, color, 0);
    }

    private static void drawForceTab(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y + 1, x + 10, y + 9, color);
        graphics.fill(x + 1, y, x + 9, y + 10, color);
        graphics.fill(x + 3, y + 3, x + 8, y + 7, 0x33FFFFFF);
    }

    private static void drawPageArrow(GuiGraphics graphics, int centerX, int centerY, int color, int shadowColor, boolean intoPage) {
        int sx = centerX + 1;
        int sy = centerY + 1;
        drawPageArrowGlyph(graphics, sx, sy, shadowColor, intoPage);
        drawPageArrowGlyph(graphics, centerX, centerY, color, intoPage);
    }

    private static void drawPageArrowGlyph(GuiGraphics graphics, int centerX, int centerY, int color, boolean intoPage) {
        graphics.fill(centerX - 5, centerY - 5, centerX + 6, centerY + 6, 0x22FFFFFF);
        graphics.fill(centerX - 4, centerY - 1, centerX + 5, centerY + 2, color);
        graphics.fill(centerX - 1, centerY - 4, centerX + 2, centerY + 5, color);
        if (intoPage) {
            graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, WindTunnelLdlib2Theme.PANEL_BACKGROUND);
            graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, color);
        } else {
            drawTriangle(graphics, centerX - 4, centerY - 5, centerX + 5, centerY - 5, centerX, centerY - 10, color);
        }
    }

    private static void drawCenterOfMassMarker(GuiGraphics graphics, int centerX, int centerY) {
        int color = WindTunnelLdlib2Theme.LINE;
        graphics.fill(centerX - 1, centerY - 6, centerX + 1, centerY + 7, color);
        graphics.fill(centerX - 6, centerY - 1, centerX + 7, centerY + 1, color);
        graphics.fill(centerX - 4, centerY - 4, centerX + 5, centerY - 3, color);
        graphics.fill(centerX - 4, centerY + 3, centerX + 5, centerY + 4, color);
        graphics.fill(centerX - 4, centerY - 4, centerX - 3, centerY + 5, color);
        graphics.fill(centerX + 3, centerY - 4, centerX + 4, centerY + 5, color);
    }

    private static void drawTriangle(GuiGraphics graphics, int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));
        for (int y = minY; y <= maxY; y++) {
            int left = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int intersections = 0;
            int[] xs = new int[3];
            intersections = addTriangleIntersection(y, x1, y1, x2, y2, xs, intersections);
            intersections = addTriangleIntersection(y, x2, y2, x3, y3, xs, intersections);
            intersections = addTriangleIntersection(y, x3, y3, x1, y1, xs, intersections);
            for (int i = 0; i < intersections; i++) {
                left = Math.min(left, xs[i]);
                right = Math.max(right, xs[i]);
            }
            if (intersections >= 2) {
                graphics.fill(left, y, right + 1, y + 1, color);
            }
        }
    }

    private static int addTriangleIntersection(int y, int x1, int y1, int x2, int y2, int[] xs, int count) {
        if (y1 == y2 || count >= xs.length) {
            return count;
        }
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        if (y < minY || y > maxY) {
            return count;
        }
        double t = (double) (y - y1) / (double) (y2 - y1);
        xs[count] = (int) Math.round(x1 + (x2 - x1) * t);
        return count + 1;
    }

    private static boolean canDrawArrowAt(int x, int y, int width, int height) {
        return x >= 8 && x < width - 8 && y >= 8 && y < height - 8;
    }

    private static void drawLine(VertexConsumer builder, @NotNull Matrix4f pose, int x1, int y1, int x2, int y2, int color, int inflation) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;

        while (true) {
            builder.addVertex(pose, x1 - inflation, y1 - inflation, 1.0F).setColor(color);
            builder.addVertex(pose, x1 - inflation, y1 + 1.0F + inflation, 1.0F).setColor(color);
            builder.addVertex(pose, x1 + 1.0F + inflation, y1 + 1.0F + inflation, 1.0F).setColor(color);
            builder.addVertex(pose, x1 + 1.0F + inflation, y1 - inflation, 1.0F).setColor(color);
            if (x1 == x2 && y1 == y2) {
                return;
            }

            int e2 = err * 2;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }
        }
    }

    private static void playClickSound() {
        Minecraft.getInstance().getSoundManager().play(Objects.requireNonNull(SimpleSoundInstance.forUI(
                Objects.requireNonNull(SoundEvents.UI_BUTTON_CLICK.value()), 1.0F)));
    }

    private record BoundsSummary(Vector3d center, float radius) {
    }

    private record DiagramViewKey(Source source, BlockPos pos) {
        private DiagramViewKey {
            pos = pos.immutable();
        }
    }

    private record DiagramViewState(double yaw, double pitch, SharedDiagramViewport.ViewState viewportState) {
    }

    private enum DiagramIcon {
        FORCES_SEPARATED(16, 16),
        FORCES_MERGED(16, 16),
        CENTER_OF_MASS_TOGGLE(16, 16),
        MASS(16, 16),
        TURN_UP(6, 7, 9, 4),
        TURN_DOWN(6, 7, 17, 4),
        TURN_LEFT(8, 13, 1, 1),
        TURN_RIGHT(8, 13, 24, 1);

        private final int width;
        private final int height;
        private final int u;
        private final int v;

        DiagramIcon(int width, int height) {
            this(width, height, 0, 0);
        }

        DiagramIcon(int width, int height, int u, int v) {
            this.width = width;
            this.height = height;
            this.u = u;
            this.v = v;
        }

        private int u() {
            return u;
        }

        private int v(boolean hovered) {
            return hovered ? v + 16 : v;
        }

        private boolean isRotationControl() {
            return this == TURN_UP || this == TURN_DOWN || this == TURN_LEFT || this == TURN_RIGHT;
        }
    }

    private static final class DiagramButtonHitbox {
        private final String id;
        private DiagramIcon icon;
        private final java.util.function.IntSupplier xSupplier;
        private final java.util.function.IntSupplier ySupplier;
        private int x;
        private int y;
        private final Runnable onClick;
        private final java.util.function.Supplier<Component> tooltip;
        private final java.util.function.BooleanSupplier iconSwitch;
        private boolean active = true;
        private boolean visible = true;

        private DiagramButtonHitbox(String id, DiagramIcon icon, java.util.function.IntSupplier xSupplier,
                                    java.util.function.IntSupplier ySupplier, Runnable onClick,
                                    java.util.function.Supplier<Component> tooltip,
                                    java.util.function.BooleanSupplier iconSwitch) {
            this.id = id;
            this.icon = icon;
            this.xSupplier = xSupplier;
            this.ySupplier = ySupplier;
            this.onClick = onClick;
            this.tooltip = tooltip;
            this.iconSwitch = iconSwitch;
            updatePosition();
        }

        private DiagramButtonHitbox setActive(boolean active) {
            this.active = active;
            return this;
        }

        private void updatePosition() {
            this.x = xSupplier.getAsInt();
            this.y = ySupplier.getAsInt();
        }

        private boolean contains(double localX, double localY) {
            return active
                    && localX >= x
                    && localX < x + width()
                    && localY >= y
                    && localY < y + height();
        }

        private int width() {
            return icon.width + 2;
        }

        private int height() {
            return icon.height + 2;
        }
    }

    private final class DiagramViewportElement extends UIElement {
        private boolean dragging;
        private double lastMouseX;
        private double lastMouseY;

        private DiagramViewportElement() {
            layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
                    .left(PANEL_SIDE_PADDING)
                    .right(PANEL_SIDE_PADDING)
                    .top(PANEL_TOP_CONTENT_Y)
                    .height(MIN_DIAGRAM_HEIGHT)
                    .minWidth(MIN_DIAGRAM_WIDTH)
                    .minHeight(MIN_DIAGRAM_HEIGHT)
                    .flexGrow(0)
                    .flexShrink(1));
            addEventListener(UIEvents.MOUSE_DOWN, this::handleMouseDown);
            addEventListener(UIEvents.MOUSE_MOVE, this::handleMouseMove);
            addEventListener(UIEvents.DRAG_SOURCE_UPDATE, this::handleMouseMove, true);
            addEventListener(UIEvents.MOUSE_UP, this::handleMouseUp);
            addEventListener(UIEvents.DRAG_END, this::handleMouseUp, true);
            addEventListener(UIEvents.MOUSE_WHEEL, this::handleMouseWheel);
        }

        @Override
        public void drawBackgroundAdditional(@NotNull GUIContext guiContext) {
            int diagramWidth = diagramWidth();
            int diagramHeight = diagramHeight();
            ensureFramebufferSize(diagramWidth, diagramHeight);
            updateButtonState();
            populateDiagramFbos(guiContext.partialTick);

            GuiGraphics graphics = guiContext.graphics;
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(getPositionX(), getPositionY(), 0.0F);
            drawDiagramFrame(graphics, diagramWidth, diagramHeight);
            if (fbo != null) {
                renderFbo(graphics, fbo, diagramWidth, diagramHeight);
            }
            renderSubLevelFooter(graphics);
            renderArrows(graphics, guiContext.mouseX, guiContext.mouseY);
            if (diagramConfig.displayCenterOfMass()) {
                renderCenterOfMass(graphics);
            }
            graphics.flush();
            pose.popPose();

            pose.pushPose();
            pose.translate(WindTunnelLdlib2DiagramElement.this.getPositionX(),
                    WindTunnelLdlib2DiagramElement.this.getPositionY(), 0.0F);
            renderButtons(graphics, guiContext.mouseX, guiContext.mouseY);
            pose.popPose();
        }

        private void handleMouseDown(UIEvent event) {
            if (event.button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                resetDiagramPanAndZoom();
                event.stopPropagation();
                return;
            }
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return;
            }
            DiagramButtonHitbox button = findButton(
                    event.x - WindTunnelLdlib2DiagramElement.this.getPositionX(),
                    event.y - WindTunnelLdlib2DiagramElement.this.getPositionY());
            if (button != null) {
                button.onClick.run();
                event.stopPropagation();
                return;
            }
            dragging = true;
            lastMouseX = event.x;
            lastMouseY = event.y;
            IGuiTexture emptyTex = IGuiTexture.EMPTY;
            startDrag("", Objects.requireNonNull(emptyTex));
            event.stopPropagation();
        }

        private void handleMouseMove(UIEvent event) {
            if (!dragging) {
                return;
            }
            panDiagram(event.x, event.y, lastMouseX, lastMouseY);
            lastMouseX = event.x;
            lastMouseY = event.y;
            event.stopPropagation();
        }

        private void handleMouseUp(UIEvent event) {
            if (dragging) {
                dragging = false;
                event.stopPropagation();
            }
        }

        private void handleMouseWheel(UIEvent event) {
            if (Math.abs(event.deltaY) > 1.0E-4D) {
                zoomDiagram(event.x, event.y, event.deltaY);
                event.stopPropagation();
            }
        }
    }

    private final class ForceToggleElement extends UIElement {
        private final ResourceLocation groupId;
        private final ForceGroup group;

        private ForceToggleElement(ResourceLocation groupId, ForceGroup group) {
            this.groupId = groupId;
            this.group = group;
            layout(layout -> layout.width(FORCE_TOGGLE_WIDTH).height(FORCE_TOGGLE_HEIGHT));
            addEventListener(UIEvents.MOUSE_DOWN, this::handleMouseDown);
        }

        @Override
        public void drawBackgroundAdditional(@NotNull GUIContext guiContext) {
            GuiGraphics graphics = guiContext.graphics;
            int x = Math.round(getPositionX());
            int y = Math.round(getPositionY());
            int width = Math.round(getSizeWidth());
            int forceCount = countForces(group);
            boolean enabled = isForceGroupEnabled(groupId);
            int groupColor = 0xFF000000 | group.color();
            int labelColor = enabled ? groupColor : -1431655766;

            drawForceTab(graphics, x - 1, y - 1, enabled ? groupColor : -5592406);

            Font font = Objects.requireNonNull(Minecraft.getInstance().font);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(x + 18.0F, y + 1.0F, 0.0F);
            pose.scale(FORCE_TOGGLE_LABEL_SCALE, FORCE_TOGGLE_LABEL_SCALE, 0.0F);
            if (enabled) {
                graphics.drawString(font, group.name(), 1, 1, WindTunnelLdlib2Theme.PANEL_BACKGROUND, false);
                graphics.drawString(font, group.name(), 0, 0, labelColor, false);
            } else {
                graphics.drawString(font, Objects.requireNonNull(group.name().copy().withStyle(ChatFormatting.STRIKETHROUGH)),
                        0, 0, labelColor, false);
            }
            if (forceCount > 0) {
                String forceCountText = String.valueOf(forceCount);
                int countX = Math.round((width - 18) / FORCE_TOGGLE_LABEL_SCALE) - font.width(Objects.requireNonNull(forceCountText));
                if (enabled) {
                    graphics.drawString(font, forceCountText, countX + 1, 1, WindTunnelLdlib2Theme.PANEL_BACKGROUND, false);
                    graphics.drawString(font, forceCountText, countX, 0, labelColor, false);
                } else {
                    graphics.drawString(font, forceCountText, countX, 0, labelColor, false);
                }
            }
            pose.popPose();
        }

        private void handleMouseDown(UIEvent event) {
            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                return;
            }
            forceGroupSelectionTouched = true;
            toggleActive(groupId);
            playClickSound();
            event.stopPropagation();
        }
    }
}
