package net.irisshaders.iris.probe.mixin;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.irisshaders.iris.probe.NativePortalProbe;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PreparedRenderType.class)
public abstract class ProbePortalDrawMixin {
    @Inject(method = "drawFromBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;III)V", at = @At("RETURN"))
    private void iris$portalDrawCompleted(GpuBuffer vertices, GpuBuffer indices, IndexType type, int baseVertex,
                                         int firstIndex, int indexCount, CallbackInfo ci) {
        NativePortalProbe.drawn((PreparedRenderType) (Object) this, indexCount);
    }
}
