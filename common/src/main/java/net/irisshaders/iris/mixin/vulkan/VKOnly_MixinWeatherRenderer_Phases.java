package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WeatherEffectRenderer.class)
public class VKOnly_MixinWeatherRenderer_Phases {
	@WrapMethod(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V")
	private void iris$weather(WeatherRenderState state, RenderPass pass, RenderPipeline renderPipeline, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && !pipeline.shouldRenderWeather()) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.RAIN_SNOW)) { original.call(state, pass, renderPipeline); }
	}
}
