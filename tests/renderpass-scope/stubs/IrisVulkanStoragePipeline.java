package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.vulkan.VulkanRenderPass;

/** Descriptor-command sink. Existing storage reflection tests cover the actual GPU descriptor layouts. */
public final class IrisVulkanStoragePipeline {
    public static int bindCalls;
    public static void bind(VulkanRenderPass pass) { bindCalls++; }
}
