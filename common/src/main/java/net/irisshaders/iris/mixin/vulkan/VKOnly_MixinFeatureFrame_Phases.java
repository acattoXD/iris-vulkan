package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class VKOnly_MixinFeatureFrame_Phases {
	@WrapMethod(method = "executeSolid")
	private void iris$solidFeatures(RenderPass pass, Operation<Void> original) {
		WorldRenderingPhase phase = IrisVulkanPhaseContext.featurePhase(false);
		try (var ignored = IrisVulkanPhaseContext.enter(phase)) {
			original.call(pass);
		}
	}
	@WrapMethod(method = {"executeTranslucent", "executeTranslucentAfterTerrain"})
	private void iris$translucentFeatures(RenderPass pass, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(IrisVulkanPhaseContext.featurePhase(true))) { original.call(pass); }
	}
	@WrapMethod(method = "executeOutline")
	private void iris$outlineFeatures(RenderPass pass, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.OUTLINE)) { original.call(pass); }
	}
}
