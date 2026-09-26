package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.vulkan.IrisVulkanStoragePipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.LongBuffer;

@Mixin(value = VulkanRenderPipeline.class, remap = false)
public class VKOnly_MixinVulkanRenderPipeline_PushConstants {
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int iris$extendStorageDescriptors(VkDevice device, VkDescriptorSetLayoutCreateInfo createInfo,
            VkAllocationCallbacks allocator, LongBuffer result, Operation<Integer> original) {
        // RenderPearl now preserves pushConstantSize itself. Only storage descriptor
        // expansion is needed around its unmodified native pipeline compiler.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long originalBindings = createInfo.pBindings() == null ? 0 : createInfo.pBindings().address();
            int originalCount = createInfo.bindingCount();
            try {
                IrisVulkanStoragePipeline.extendDescriptorLayout(device, createInfo, stack);
                return original.call(device, createInfo, allocator, result);
            } finally {
                createInfo.pBindings(org.lwjgl.vulkan.VkDescriptorSetLayoutBinding.createSafe(originalBindings, originalCount));
            }
        }
    }
}
