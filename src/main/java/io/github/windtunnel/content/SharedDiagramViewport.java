package io.github.windtunnel.content;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Shared viewport state for the large force-diagram screens.
 * This keeps the camera orientation, orthographic projection and mouse-driven
 * pan/zoom math in one place so each screen only handles its own data source
 * and widget layout.
 */
public final class SharedDiagramViewport {
    private static final float MIN_VIEWPORT_ZOOM = 0.15F;
    private static final float MAX_VIEWPORT_ZOOM = 4.0F;
    private static final float VIEWPORT_ZOOM_STEP = 0.85F;
    private static final float MIN_VIEWPORT_RADIUS = 0.35F;
    private static final float RENDER_INTERVAL_TICKS = 1.6666666F;

    private final Vector3d localCameraPosition = new Vector3d();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Quaternionf localOrientation = new Quaternionf();
    private final Vector3d viewportCenterLocal = new Vector3d();

    private float renderTime = 12.0F;
    private float viewportRadius = 2.0F;
    private float viewportFitRadius = 2.0F;
    private float viewportZoom = 1.0F;
    private boolean viewportInitialized;

    public record ViewState(Vector3d centerLocal, float zoom, boolean initialized) {
    }

    public boolean shouldDeferRender(float deltaTicks) {
        if (renderTime < RENDER_INTERVAL_TICKS) {
            renderTime += deltaTicks;
            return true;
        }
        renderTime = 0.0F;
        return false;
    }

    public void fitToBounds(Vector3dc center, float radius, int viewportWidth, int viewportHeight) {
        viewportFitRadius = radius;
        if (!viewportInitialized) {
            viewportCenterLocal.set(center);
            viewportZoom = 1.0F;
            viewportInitialized = true;
        }
        applyMatrices(viewportWidth, viewportHeight);
    }

    public void updateOrientation(double yawDegrees, double pitchDegrees, int viewportWidth, int viewportHeight) {
        markDirty();
        localOrientation.identity()
                .rotateY((float) Math.toRadians(yawDegrees))
                .rotateX((float) Math.toRadians(pitchDegrees));
        if (viewportInitialized) {
            applyMatrices(viewportWidth, viewportHeight);
        }
    }

    public void pan(double mouseX, double mouseY, double previousMouseX, double previousMouseY,
                    int diagramLeft, int diagramTop, int viewportWidth, int viewportHeight) {
        if (!viewportInitialized) {
            return;
        }

        Vector2d previousDiagramPoint = new Vector2d(previousMouseX - diagramLeft, previousMouseY - diagramTop);
        Vector2d currentDiagramPoint = new Vector2d(mouseX - diagramLeft, mouseY - diagramTop);
        if (previousDiagramPoint.distanceSquared(currentDiagramPoint) <= 1.0E-6D) {
            return;
        }

        Vector3d focusBefore = WindTunnelDiagramProjection.getPlotCoords(previousDiagramPoint, localOrientation, localCameraPosition, projectionMatrix,
                viewportWidth, viewportHeight);
        Vector3d focusAfter = WindTunnelDiagramProjection.getPlotCoords(currentDiagramPoint, localOrientation, localCameraPosition, projectionMatrix,
                viewportWidth, viewportHeight);
        viewportCenterLocal.add(focusBefore.sub(focusAfter, new Vector3d()));
        applyMatrices(viewportWidth, viewportHeight);
        markDirty();
    }

    public void zoom(double mouseX, double mouseY, double scrollY,
                     int diagramLeft, int diagramTop, int viewportWidth, int viewportHeight) {
        if (!viewportInitialized) {
            return;
        }

        float oldZoom = viewportZoom;
        float targetZoom = Mth.clamp((float) (oldZoom * Math.pow(VIEWPORT_ZOOM_STEP, scrollY)), MIN_VIEWPORT_ZOOM, MAX_VIEWPORT_ZOOM);
        if (Math.abs(targetZoom - oldZoom) <= 1.0E-4F) {
            return;
        }

        Vector2d diagramSpacePoint = new Vector2d(mouseX - diagramLeft, mouseY - diagramTop);
        float oldRadius = getViewportRadius(oldZoom);
        float aspect = (float) viewportWidth / (float) viewportHeight;
        Matrix4f oldProjection = new Matrix4f().ortho(
                -oldRadius * aspect, oldRadius * aspect,
                -oldRadius, oldRadius,
                0.1F, oldRadius * 2.0F);
        Vector3d oldLocalCamera = new Vector3d(viewportCenterLocal)
                .add(localOrientation.transform(new Vector3d(0.0D, 0.0D, oldRadius), new Vector3d()));
        Vector3d focusBefore = WindTunnelDiagramProjection.getPlotCoords(diagramSpacePoint, localOrientation, oldLocalCamera, oldProjection,
                viewportWidth, viewportHeight);

        viewportZoom = targetZoom;
        applyMatrices(viewportWidth, viewportHeight);

        Vector3d focusAfter = WindTunnelDiagramProjection.getPlotCoords(diagramSpacePoint, localOrientation, localCameraPosition, projectionMatrix,
                viewportWidth, viewportHeight);
        viewportCenterLocal.add(focusBefore.sub(focusAfter, new Vector3d()));
        applyMatrices(viewportWidth, viewportHeight);
        markDirty();
    }

    public Quaternionf buildRenderOrientation(Pose3dc referenceRenderPose) {
        Quaternionf renderOrientation = new Quaternionf(referenceRenderPose.orientation()).conjugate();
        return renderOrientation.premul(localOrientation.conjugate(new Quaternionf()));
    }

    public void reset() {
        viewportInitialized = false;
        viewportZoom = 1.0F;
        viewportFitRadius = 2.0F;
        viewportRadius = 2.0F;
        viewportCenterLocal.zero();
        localCameraPosition.zero();
        projectionMatrix.identity();
        markDirty();
    }

    public void resetPanAndZoom() {
        viewportInitialized = false;
        viewportZoom = 1.0F;
        viewportCenterLocal.zero();
        localCameraPosition.zero();
        projectionMatrix.identity();
        markDirty();
    }

    public ViewState snapshot() {
        return new ViewState(new Vector3d(viewportCenterLocal), viewportZoom, viewportInitialized);
    }

    public void restore(ViewState state) {
        if (state == null) {
            reset();
            return;
        }
        viewportInitialized = state.initialized();
        viewportZoom = state.zoom();
        viewportCenterLocal.set(state.centerLocal());
        localCameraPosition.zero();
        projectionMatrix.identity();
        markDirty();
    }

    public void markDirty() {
        renderTime = Float.MAX_VALUE;
    }

    public Vector3d localCameraPosition() {
        return localCameraPosition;
    }

    public Matrix4f projectionMatrix() {
        return projectionMatrix;
    }

    public Quaternionf localOrientation() {
        return localOrientation;
    }

    public float viewportRadius() {
        return viewportRadius;
    }

    private void applyMatrices(int viewportWidth, int viewportHeight) {
        viewportRadius = getViewportRadius(viewportZoom);
        float aspect = (float) viewportWidth / (float) viewportHeight;
        projectionMatrix.identity().ortho(
                -viewportRadius * aspect, viewportRadius * aspect,
                -viewportRadius, viewportRadius,
                0.1F, viewportRadius * 2.0F);
        localCameraPosition.set(viewportCenterLocal)
                .add(localOrientation.transform(new Vector3d(0.0D, 0.0D, viewportRadius), new Vector3d()));
    }

    private float getViewportRadius(float zoom) {
        return Math.max(MIN_VIEWPORT_RADIUS, viewportFitRadius * zoom);
    }
}
