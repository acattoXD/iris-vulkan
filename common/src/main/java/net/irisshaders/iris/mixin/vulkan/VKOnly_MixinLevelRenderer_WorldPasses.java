package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.MojLambdas;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.irisshaders.iris.vulkan.IrisVulkanWorldRenderPass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native lifecycle points are attached to execution boundaries, not render-type construction. */
@Mixin(LevelRenderer.class)
public class VKOnly_MixinLevelRenderer_WorldPasses {
	@Shadow @Final private LevelTargetBundle targets;

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FramePass;executes(Ljava/lang/Runnable;)V", ordinal = 0, shift = At.Shift.AFTER))
	private void iris$scheduleNativeHorizon(CallbackInfo ci, @Local FrameGraphBuilder frame, @Local(ordinal = 0) FramePass clearPass) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline == null) return;
		FramePass horizon = frame.addPass("iris_native_horizon");
		this.targets.main = horizon.readsAndWrites(this.targets.main);
		horizon.requires(clearPass);
		horizon.executes(() -> {
			GpuBufferSlice fog = RenderSystem.getShaderFog();
			try {
				pipeline.onBeginClear();
			} finally {
				RenderSystem.setShaderFog(fog);
			}
		});
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;addMainPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Z)V"))
	private void iris$renderNativeShadows(CallbackInfo ci) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) {
			var renderer = (LevelRendererAccessor) this;
			pipeline.renderShadows(renderer, Minecraft.getInstance().gameRenderer.mainCamera(), renderer.getLevelRenderState().cameraRenderState);
		}
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void iris$beforeNativeTranslucents(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame features,
		RenderPass pass, CallbackInfo ci) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) IrisVulkanWorldRenderPass.runOutsidePass(pass, pipeline::beginTranslucents);
	}
}
