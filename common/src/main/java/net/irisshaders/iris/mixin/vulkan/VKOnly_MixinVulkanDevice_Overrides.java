package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** RenderPearl moved frontend pipeline lookup out of the Vulkan device. */
@Mixin(RenderSystem.class)
public class VKOnly_MixinVulkanDevice_Overrides {
    @Inject(method = "getCompiledPipelineNullable", at = @At("HEAD"), cancellable = true)
    private static void iris$overridePipeline(RenderPipeline descriptor, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
        IrisNativeVulkan.tryOverrideCompiledPipeline(descriptor).ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getCompiledPipelineNullable", at = @At("RETURN"))
    private static void iris$rememberDescriptor(RenderPipeline descriptor, CallbackInfoReturnable<CompiledRenderPipeline> cir) {
        IrisNativeVulkan.registerDescriptor(cir.getReturnValue(), descriptor);
    }

    @Inject(method = "shutdownRenderer", at = @At("HEAD"))
    private static void iris$clearPipelineCache(CallbackInfo ci) {
        var frontend = RenderSystem.tryGetDevice();
        if (frontend != null && IrisBackend.getBackend(frontend) instanceof VulkanDevice device) {
            IrisNativeVulkan.clearDevicePipelineCache(device);
        }
    }
}
