package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

@Mixin(QuadParticleFeatureRenderer.class)
public class VKOnly_MixinParticleRenderer_Phases {
	@WrapMethod(method = "executeGroup")
	private void iris$particles(FeatureFrameContext context, OitStage oit, RenderPass pass, int index, List<QuadParticleFeatureRenderer.Submit> submits, boolean ordered, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.PARTICLES)) { original.call(context, oit, pass, index, submits, ordered); }
	}
}
