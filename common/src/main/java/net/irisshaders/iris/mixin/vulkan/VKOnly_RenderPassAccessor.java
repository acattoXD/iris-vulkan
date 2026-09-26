package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.Map;
import java.util.HashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FrontendRenderPass.class)
public interface VKOnly_RenderPassAccessor {
    @Accessor("backend") RenderPassBackend iris$getBackend();
    @Mutable @Accessor("backend") void iris$setBackend(RenderPassBackend backend);
    @Accessor("device") GpuDeviceBackend iris$getDevice();
    @Accessor("renderArea") RenderPass.RenderArea iris$getRenderArea();
    @Accessor("uniforms") HashMap<String, Object> iris$getUniforms();
    @Accessor("boundPipeline") FrontendRenderPipeline iris$getBoundPipeline();
}
