package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import net.irisshaders.iris.vulkan.IrisVulkanColorImages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanConst.class, remap = false)
public class VKOnly_MixinVulkanConst_ColorImages {
    @ModifyReturnValue(method = "textureUsageToVk", at = @At("RETURN"))
    private static int iris$colorImageUsage(int original) {
        return IrisVulkanColorImages.allocationUsage(original);
    }
}
