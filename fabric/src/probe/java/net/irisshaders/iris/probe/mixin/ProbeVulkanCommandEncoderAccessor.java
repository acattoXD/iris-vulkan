package net.irisshaders.iris.probe.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Probe-only assertion that raw storage readback commands run outside rendering. */
@Mixin(VulkanCommandEncoder.class)
public interface ProbeVulkanCommandEncoderAccessor {
    @Accessor("currentRenderPass")
    VulkanRenderPass iris$activeRenderPass();
}
