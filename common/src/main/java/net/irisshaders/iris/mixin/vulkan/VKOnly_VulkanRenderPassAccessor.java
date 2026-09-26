package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import it.unimi.dsi.fastutil.objects.ReferenceList;

@Mixin(VulkanRenderPass.class)
public interface VKOnly_VulkanRenderPassAccessor {
	@Accessor("commandBuffer")
	VkCommandBuffer iris$getCommandBuffer();

	@Accessor("pipeline")
	VulkanRenderPipeline iris$getPipeline();

	@Accessor("uniforms")
	ReferenceList<Object> iris$getUniforms();
}
