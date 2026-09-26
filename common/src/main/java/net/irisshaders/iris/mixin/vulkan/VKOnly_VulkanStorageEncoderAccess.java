package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Records storage transfers in the engine's graphics submission, outside render passes. */
@Mixin(VulkanCommandEncoder.class)
public interface VKOnly_VulkanStorageEncoderAccess {
    @Invoker("commandBuffer")
    VkCommandBuffer iris$storageCommandBuffer();
}
