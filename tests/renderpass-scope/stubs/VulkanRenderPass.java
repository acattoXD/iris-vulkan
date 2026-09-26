package com.mojang.blaze3d.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanRenderPassAccessor;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.HashMap;

/** Supplies the accessor surface normally installed by Mixin on the real backend. */
public final class VulkanRenderPass implements VKOnly_VulkanRenderPassAccessor {
    public VulkanRenderPipeline pipeline;
    public final HashMap<String, GpuBufferSlice> uniforms = new HashMap<>();
    public final HashMap<String, Object> textures = new HashMap<>();
    public VulkanRenderPass(VulkanRenderPipeline pipeline) { this.pipeline = pipeline; }
    public VkCommandBuffer iris$getCommandBuffer() { return null; }
    public VulkanRenderPipeline iris$getPipeline() { return pipeline; }
    public HashMap<String, GpuBufferSlice> iris$getUniforms() { return uniforms; }
    public HashMap<String, Object> iris$getTextures() { return textures; }
}
