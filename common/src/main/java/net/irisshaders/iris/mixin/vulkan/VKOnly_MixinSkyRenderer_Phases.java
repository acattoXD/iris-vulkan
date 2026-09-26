package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.math.Axis;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class VKOnly_MixinSkyRenderer_Phases {
	@WrapMethod(method = "renderSkyDisc")
	private void iris$sky(RenderPass pass, Vector3fc color, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && !pipeline.shouldRenderSkyDisc()) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.SKY)) { original.call(pass, color); }
	}
	@WrapMethod(method = "renderDarkDisc")
	private void iris$void(RenderPass pass, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.VOID)) { original.call(pass); }
	}
	@WrapMethod(method = "renderEndSky")
	private void iris$endSky(RenderPass pass, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.SKY)) { original.call(pass); }
	}
	@WrapMethod(method = "renderSun")
	private void iris$sun(RenderPass pass, float rain, PoseStack matrices, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && !pipeline.shouldRenderSun()) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.SUN)) { original.call(pass, rain, matrices); }
	}
	@WrapMethod(method = "renderMoon")
	private void iris$moon(RenderPass pass, MoonPhase moon, float rain, PoseStack matrices, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && !pipeline.shouldRenderMoon()) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.MOON)) { original.call(pass, moon, rain, matrices); }
	}
	@WrapMethod(method = "renderStars")
	private void iris$stars(RenderPass pass, float brightness, PoseStack matrices, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && !pipeline.shouldRenderStars()) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.STARS)) { original.call(pass, brightness, matrices); }
	}
	@WrapMethod(method = "renderSunriseAndSunset")
	private void iris$sunset(RenderPass pass, PoseStack matrices, float angle, Vector4fc color, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.SUNSET)) { original.call(pass, matrices, angle, color); }
	}
	@Inject(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V", ordinal = 0, shift = At.Shift.AFTER))
	private void iris$tiltSun(RenderPass pass, PoseStack matrices, float sunAngle, float moonAngle, float starAngle, MoonPhase moon, float rain, float stars, CallbackInfo ci) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) matrices.rotateDegrees(Axis.ZP, pipeline.getSunPathRotation());
	}
}
