package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Models atlas startup where Globals has not yet been initialized. */
public final class RenderSystem {
    public static int defaultBindingCalls;
    public static GpuBufferSlice defaultProjection;
    public static void bindDefaultUniforms(RenderPass pass) {
        defaultBindingCalls++;
        if (defaultProjection != null) pass.setUniform("Projection", defaultProjection);
    }
    public static GpuBufferSlice getProjectionMatrixBuffer() { return defaultProjection; }
    public static GpuBuffer getGlobalSettingsUniform() { return null; }
}
