package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.mixinterface.NativeVulkanHandRenderer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Util;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class VKOnly_MixinGameRenderer_WorldPasses implements NativeVulkanHandRenderer {
	@Shadow @Final private Projection hudProjection;
	@Shadow @Final private ProjectionMatrixBuffer hud3dProjectionMatrixBuffer;
	@Shadow protected abstract void renderItemInHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depth);
	@Unique private RenderBuffers iris$handBuffers;
	@Unique private FeatureRenderDispatcher iris$handFeatures;
	@Unique private boolean iris$earlyHand;
	@Unique private int iris$handMatrixPushes;

	@Override
	public void iris$renderSolidHandBeforeTranslucents() {
		var client = Minecraft.getInstance();
		var renderer = (GameRenderer) (Object) this;
		var state = renderer.gameRenderState();
		var camera = state.levelRenderState.cameraRenderState;
		if (iris$handFeatures == null) {
			iris$handBuffers = new RenderBuffers(Runtime.getRuntime().availableProcessors());
			iris$handFeatures = new FeatureRenderDispatcher(iris$handBuffers, client.getModelManager(),
				client.getAtlasManager(), client.font, state);
		}
		// Match render3dHud's native projection, without the OpenGL hand Z scale.
		// The shader bridge compresses hand depth exactly once after pack vertex code.
		hudProjection.setupPerspective(0.05F, camera.depthFar, camera.hudFov,
			state.windowRenderState.width, state.windowRenderState.height);
		var projection = RenderSystem.getProjectionMatrixBuffer();
		var projectionType = RenderSystem.getProjectionType();
		var modelView = RenderSystem.getModelViewStack();
		modelView.pushMatrix();
		modelView.identity();
		iris$earlyHand = true;
		try {
			RenderSystem.setProjectionMatrix(hud3dProjectionMatrixBuffer.getBuffer(hudProjection), ProjectionType.PERSPECTIVE);
			// Retain vanilla's first-person/HUD/spectator/sleeping checks, bobbing,
			// pose submission and resource scopes. Only preparation/execution is split.
			renderItemInHand(camera, state.levelRenderState.playerRenderState, renderer.mainRenderTarget().getDepthTextureView());
		} finally {
			iris$earlyHand = false;
			RenderSystem.setProjectionMatrix(projection, projectionType);
			modelView.popMatrix();
			iris$handBuffers.endFrame();
		}
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
	private FeatureRenderDispatcher.PreparedFrame iris$prepareHandFeatures(FeatureRenderDispatcher dispatcher, SubmitNodeStorage submits,
		Operation<FeatureRenderDispatcher.PreparedFrame> original) {
		// LevelRenderer still owns the shared dispatcher's only PreparedFrame here.
		return original.call(iris$earlyHand ? iris$handFeatures : dispatcher, submits);
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;)V"))
	private void iris$splitHandFeatures(RenderPass pass, FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline == null || !pipeline.isRenderingWorld()) {
			original.call(pass, frame);
		} else if (iris$earlyHand) {
			frame.executeSolid(pass);
		} else {
			frame.executeTranslucent(pass);
			frame.executeTranslucentAfterTerrain(pass);
			frame.executeSeeThrough(pass);
			frame.executeAlwaysOnTop(pass);
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void iris$closeHandFeatures(CallbackInfo ci) {
		try {
			if (iris$handFeatures != null) iris$handFeatures.close();
		} finally {
			iris$handFeatures = null;
			if (iris$handBuffers != null) iris$handBuffers.close();
			iris$handBuffers = null;
		}
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;"))
	private Matrix4fStack iris$trackHandMatrixPush(Matrix4fStack stack, Operation<Matrix4fStack> original) {
		Matrix4fStack result = original.call(stack);
		if (IrisVulkanPhaseContext.handProjection() != null) iris$handMatrixPushes++;
		return result;
	}

	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;"))
	private Matrix4fStack iris$trackHandMatrixPop(Matrix4fStack stack, Operation<Matrix4fStack> original) {
		Matrix4fStack result = original.call(stack);
		if (iris$handMatrixPushes > 0) iris$handMatrixPushes--;
		return result;
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void iris$advanceNativeFrameClock(CallbackInfo ci) {
		var deltaTracker = Minecraft.getInstance().getDeltaTracker();
		CapturedRenderingState.INSTANCE.setRealTickDelta(deltaTracker.getGameTimeDeltaPartialTick(true));
		SystemTimeUniforms.COUNTER.beginFrame();
		SystemTimeUniforms.TIMER.beginFrame(Util.getNanos());
	}

	@WrapOperation(method = "render3dHud", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void iris$preserveWorldDepthForHand(CommandEncoder encoder, GpuTexture texture, double clearDepth, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline == null || !pipeline.isRenderingWorld()) {
			original.call(encoder, texture, clearDepth);
			return;
		}
		// depthtex2 must retain the world before the hand. Native hand shaders
		// compress depth toward the near plane instead of clearing terrain depth.
		pipeline.beginHand();
	}

	@WrapMethod(method = "renderItemInHand")
	private void iris$renderNativeHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depth, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline == null || !pipeline.isRenderingWorld()) {
			original.call(cameraState, playerState, depth);
			return;
		}
		// HandWater can sample the world translucency which has just finished.
		// depthtex1/2 remain the solid-with-hand and opaque-without-hand snapshots.
		if (!iris$earlyHand) IrisVulkanGbufferTargets.snapshotDepth(0);
		IrisVulkanPhaseContext.captureHandMatrices(hudProjection.getMatrix(new Matrix4f()), cameraState.viewRotationMatrix);
		try (var ignored = IrisVulkanPhaseContext.enter(iris$earlyHand ? WorldRenderingPhase.HAND_SOLID : WorldRenderingPhase.HAND_TRANSLUCENT)) {
			original.call(cameraState, playerState, depth);
		} finally {
			// Vanilla pops only on its normal return path. Restore its push if
			// feature preparation or drawing throws before that return.
			while (iris$handMatrixPushes > 0) {
				RenderSystem.getModelViewStack().popMatrix();
				iris$handMatrixPushes--;
			}
			IrisVulkanPhaseContext.clearHandMatrices();
		}
	}

	@Inject(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V", shift = At.Shift.AFTER))
	private void iris$finishNativeWorld(CallbackInfo ci) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) pipeline.finalizeLevelRendering();
	}

	@Inject(method = "renderLevel", at = @At("RETURN"))
	private void iris$finishNativeGame(CallbackInfo ci) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) pipeline.finalizeGameRendering();
	}

	@Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
	private void iris$useShaderPackTransparency(CallbackInfoReturnable<Boolean> ci) {
		// The pack owns transparent gbuffer outputs and their deferred composition.
		// Vanilla OIT targets cannot replace that contract; keep the user's option intact.
		if (IrisVulkanPhaseContext.pipeline() != null) ci.setReturnValue(false);
	}
}
