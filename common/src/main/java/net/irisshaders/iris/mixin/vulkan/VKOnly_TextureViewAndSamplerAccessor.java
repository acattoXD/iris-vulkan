package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.mojang.renderpearl.backend.vulkan.VulkanRenderPass$TextureViewAndSampler")
public interface VKOnly_TextureViewAndSamplerAccessor {
	@Accessor("view")
	VulkanGpuTextureView iris$getView();

	@Accessor("sampler")
	VulkanGpuSampler iris$getSampler();
}
