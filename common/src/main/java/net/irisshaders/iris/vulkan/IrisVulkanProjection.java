package net.irisshaders.iris.vulkan;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Exact clip-depth conversion, preserving the camera's complete projection. */
public final class IrisVulkanProjection {
    private IrisVulkanProjection() { }

    /** Minecraft Vulkan uses reverse [0,1]; shader packs expect forward [-1,1]. */
    public static Matrix4f toShaderpack(Matrix4fc nativeProjection) {
        return new Matrix4f(nativeProjection)
                .m02(nativeProjection.m03() - 2.0f * nativeProjection.m02())
                .m12(nativeProjection.m13() - 2.0f * nativeProjection.m12())
                .m22(nativeProjection.m23() - 2.0f * nativeProjection.m22())
                .m32(nativeProjection.m33() - 2.0f * nativeProjection.m32());
    }
}
