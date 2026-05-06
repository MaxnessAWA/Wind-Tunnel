package io.github.windtunnel.content;

import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector2d;
import org.joml.Vector2dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector4f;

final class WindTunnelDiagramProjection {

    private WindTunnelDiagramProjection() {
    }

    static Vector2d getScreenCoords(Vector3d plotSpacePoint, Quaternionfc orientation, Vector3dc localPosition,
                                    Matrix4fc projectionMatrix, int width, int height) {
        plotSpacePoint.sub(localPosition);
        orientation.transformInverse(plotSpacePoint);

        Vector4f clipSpace = new Vector4f((float) plotSpacePoint.x, (float) plotSpacePoint.y, (float) plotSpacePoint.z, 1.0F);
        clipSpace.mul(projectionMatrix);
        clipSpace.div(clipSpace.w);

        double projectedX = (clipSpace.x() * 0.5F + 0.5F) * width;
        double projectedY = (-clipSpace.y() * 0.5F + 0.5F) * height;
        return new Vector2d(projectedX, projectedY);
    }

    static Vector3d getPlotCoords(Vector2dc diagramSpacePoint, Quaternionfc orientation, Vector3dc localPosition,
                                  Matrix4fc projectionMatrix, int width, int height) {
        Vector3d clipSpace = new Vector3d(2.0D * diagramSpacePoint.x() / width - 1.0D,
                1.0D - 2.0D * diagramSpacePoint.y() / height, 0.0D);
        Vector3d point = clipSpace.sub(projectionMatrix.getTranslation(new Vector3f()))
                .div(projectionMatrix.m00(), projectionMatrix.m11(), projectionMatrix.m22());

        orientation.transform(point);
        point.add(localPosition);
        return point;
    }
}
