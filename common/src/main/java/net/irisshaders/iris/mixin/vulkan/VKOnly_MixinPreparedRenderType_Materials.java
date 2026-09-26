package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.irisshaders.iris.vulkan.IrisVulkanLegacyGlint;
import net.irisshaders.iris.vulkan.IrisVulkanShadowDrawPolicy;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreparedRenderType.class)
public class VKOnly_MixinPreparedRenderType_Materials implements IrisVulkanEntityContext.MaterialDraw {
	@Unique private IrisVulkanEntityContext.Material iris$material = IrisVulkanEntityContext.EMPTY;
	@Unique private Matrix4fc iris$modelView;
	@Inject(method = "<init>", at = @At("RETURN"))
	private void iris$captureDrawMaterial(CallbackInfo ci) {
		iris$material = IrisVulkanEntityContext.current();
		iris$modelView = IrisVulkanEntityContext.takePreparedModelView(((PreparedRenderType) (Object) this).dynamicTransforms());
	}
	@Override public IrisVulkanEntityContext.Material iris$material() { return iris$material; }
	@Inject(method = "equals", at = @At("RETURN"), cancellable = true)
	private void iris$materialEquality(Object other, CallbackInfoReturnable<Boolean> ci) {
		if (ci.getReturnValueZ()) ci.setReturnValue(other instanceof IrisVulkanEntityContext.MaterialDraw draw && iris$material.equals(draw.iris$material()));
	}
	@Inject(method = "hashCode", at = @At("RETURN"), cancellable = true)
	private void iris$materialHash(CallbackInfoReturnable<Integer> ci) { ci.setReturnValue(31 * ci.getReturnValueI() + iris$material.hashCode()); }
	@WrapMethod(method = "draw")
	private void iris$drawMaterial(StagedVertexBuffer.ExecuteInfo draw, RenderPass pass, RenderPipeline pipeline, Operation<Void> original) {
		if (IrisVulkanShadowDrawPolicy.shouldSkip(IrisVulkanShadowRenderer.active(), pipeline)) return;
		try (var ignored = IrisVulkanEntityContext.enterDraw(iris$material);
			 var matrices = IrisVulkanEntityContext.enterModelView(iris$modelView)) {
			RenderPipeline overlay = IrisVulkanLegacyGlint.overlayFor(pipeline, (PreparedRenderType) (Object) this);
			original.call(draw, pass, pipeline);
			if (overlay != null) IrisVulkanLegacyGlint.replay(overlay, replayPipeline -> original.call(draw, pass, replayPipeline));
		}
	}
}
