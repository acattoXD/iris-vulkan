package net.irisshaders.iris.vulkan;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/** CPU-only regression; compile with IrisVulkanProjection and JOML. */
public final class IrisVulkanProjectionTest {
    public static void main(String[] args) {
        float near = 0.05f, far = 1024.0f;
        Matrix4f nativeProjection = new Matrix4f().setPerspective((float) Math.toRadians(77.0), 16.0f / 9.0f, far, near, true);
        Matrix4f legacy = IrisVulkanProjection.toShaderpack(nativeProjection);
        expectDepth(legacy, near, -1.0f);
        expectDepth(legacy, far, 1.0f);
        roundTrip(nativeProjection, legacy);

        // Off-center projection, jitter, and hand/hurt transforms must survive intact.
        Matrix4f asymmetric = new Matrix4f(nativeProjection).m20(0.017f).m21(-0.025f)
                .rotateXYZ(0.02f, -0.03f, 0.01f).translate(0.02f, -0.01f, 0.0f);
        roundTrip(asymmetric, IrisVulkanProjection.toShaderpack(asymmetric));
        Matrix4f ortho = new Matrix4f().setOrtho(-4.0f, 7.0f, -3.0f, 5.0f, far, near, true);
        Matrix4f orthoLegacy = IrisVulkanProjection.toShaderpack(ortho);
        expectDepth(orthoLegacy, near, -1.0f);
        expectDepth(orthoLegacy, far, 1.0f);
        roundTrip(ortho, orthoLegacy);
        cameraEffects(nativeProjection);
        System.out.println("IRIS_VULKAN_PROJECTION_PASS: depth conversion, camera-effect clip equivalence, sparse pack reconstruction");
    }

    private static void cameraEffects(Matrix4f projection) {
        Matrix4f view = new Matrix4f().rotateX(-0.2f).rotateY(0.6f);
        Vector4f point = new Vector4f(2.0f, 1.0f, -16.0f, 1.0f);
        Matrix4f packProjection = IrisVulkanProjection.toShaderpack(projection);
        Matrix4f packInverse = new Matrix4f(packProjection).invert();
        float worstOldError = 0.0f;
        for (int frame = 0; frame < 48; frame++) {
            float phase = frame * 0.23f;
            // Walking translation/rotation, then hurt and nausea, in engine order.
            Matrix4f effects = new Matrix4f().translate(0.03f * (float) Math.sin(phase),
                    -0.04f * Math.abs((float) Math.cos(phase)), 0.0f)
                    .rotateZ(0.015f * (float) Math.sin(phase)).rotateX(0.02f)
                    .rotateY(-0.03f).rotate(0.3f, 0.0f, 0.70710677f, 0.70710677f)
                    .scale(1.02f, 1.0f, 1.0f).rotate(-0.3f, 0.0f, 0.70710677f, 0.70710677f);
            Matrix4f modelView = new Matrix4f(view).mulLocal(effects);
            Vector4f nativeClip = new Matrix4f(projection).mul(effects).mul(view).transform(new Vector4f(point));
            Vector4f packClip = new Matrix4f(packProjection).mul(modelView).transform(new Vector4f(point));
            requireClose(packClip.x, nativeClip.x, "camera clip x");
            requireClose(packClip.y, nativeClip.y, "camera clip y");
            requireClose(0.5f * (packClip.w - packClip.z), nativeClip.z, "camera clip depth");
            requireClose(packClip.w, nativeClip.w, "camera clip w");

            Vector4f ndc = new Vector4f(packClip).div(packClip.w);
            Vector4f restored = new Matrix4f(modelView).invert().transform(sparseUnproject(packInverse, ndc));
            requireClose(restored.x, point.x, "reconstructed camera-relative x");
            requireClose(restored.y, point.y, "reconstructed camera-relative y");
            requireClose(restored.z, point.z, "reconstructed camera-relative z");

            // The old Vulkan split rasterizes identically but violates Sildur's
            // diagonal inverse shortcut used by sky, shadows and reflection passes.
            Matrix4f oldInverse = IrisVulkanProjection.toShaderpack(new Matrix4f(projection).mul(effects)).invert();
            Vector4f oldRestored = new Matrix4f(view).invert().transform(sparseUnproject(oldInverse, ndc));
            worstOldError = Math.max(worstOldError, oldRestored.distance(point));
        }
        if (worstOldError < 0.05f) throw new AssertionError("Camera regression did not distinguish the old projection split");
        System.out.println("Old camera split reconstruction error: " + worstOldError + " blocks");
    }

    /** Sildur's iProjDiag * p3.xyzz + gbufferProjectionInverse[3]. */
    private static Vector4f sparseUnproject(Matrix4f inverse, Vector4f ndc) {
        Vector4f result = new Vector4f(inverse.m00() * ndc.x + inverse.m30(),
                inverse.m11() * ndc.y + inverse.m31(), inverse.m22() * ndc.z + inverse.m32(),
                inverse.m23() * ndc.z + inverse.m33());
        return result.div(result.w);
    }

    private static void expectDepth(Matrix4f projection, float distance, float expected) {
        Vector4f clip = projection.transform(new Vector4f(0.0f, 0.0f, -distance, 1.0f));
        requireClose(clip.z / clip.w, expected, "depth at distance " + distance);
    }

    private static void roundTrip(Matrix4f nativeProjection, Matrix4f legacy) {
        Vector4f[] points = {new Vector4f(0.0f, 0.0f, -0.05f, 1.0f), new Vector4f(3.0f, -2.0f, -16.0f, 1.0f),
                new Vector4f(-7.0f, 9.0f, -1000.0f, 1.0f)};
        for (Vector4f point : points) {
            Vector4f expected = nativeProjection.transform(new Vector4f(point));
            Vector4f actual = legacy.transform(new Vector4f(point));
            actual.z = 0.5f * (actual.w - actual.z);
            requireClose(actual.x, expected.x, "clip x");
            requireClose(actual.y, expected.y, "clip y");
            requireClose(actual.z, expected.z, "clip z");
            requireClose(actual.w, expected.w, "clip w");
        }
    }

    private static void requireClose(float actual, float expected, String name) {
        if (Math.abs(actual - expected) > 0.0001f * Math.max(1.0f, Math.abs(expected))) {
            throw new AssertionError(name + ": " + actual + " != " + expected);
        }
    }
}
