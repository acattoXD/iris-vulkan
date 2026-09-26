package com.mojang.blaze3d.systems;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;

/** Records low-level binding side effects; production apply/ownership logic is not replaced. */
public final class RenderPass {
    public final VulkanRenderPass backend;
    public int uniformWrites, textureWrites;
    public RenderPass(VulkanRenderPass backend) { this.backend = backend; }
    public void setUniform(String name, GpuBufferSlice value) { uniformWrites++; backend.uniforms.put(name, value); }
    public void setUniform(String name, GpuBuffer value) { setUniform(name, value.slice()); }
    public void bindTexture(String name, GpuTextureView view, GpuSampler sampler) { textureWrites++; }
}
