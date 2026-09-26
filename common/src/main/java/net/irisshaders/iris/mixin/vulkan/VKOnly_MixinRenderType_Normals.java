package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.irisshaders.iris.vulkan.IrisVulkanVertexFormats;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderType.class)
public class VKOnly_MixinRenderType_Normals {
	@Inject(method = "format", at = @At("RETURN"), cancellable = true)
	private void iris$normalProducerFormat(CallbackInfoReturnable<VertexFormat> result) {
		if (!IrisVulkanVertexFormats.worldProducerActive()) return;
		RenderType type = (RenderType) (Object) this;
		VertexFormat original = result.getReturnValue();
		VertexFormat extended = IrisVulkanVertexFormats.forShader(original, type.primitiveTopology(), IrisVulkanPhaseContext.mapPipeline(type.pipeline()));
		if (extended != original) result.setReturnValue(extended);
	}
}
