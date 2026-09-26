package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(RenderTypeFeatureRenderer.class)
public class VKOnly_MixinFeatureRenderer_Materials {
	@WrapOperation(method = "prepareGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer;buildGroup(Lnet/minecraft/client/renderer/feature/FeatureFrameContext;Ljava/util/List;)V"))
	private <S extends SubmitNode> void iris$prepareMaterialRuns(RenderTypeFeatureRenderer<S> renderer, FeatureFrameContext context, List<S> submits, Operation<Void> original) {
		// All runs still feed the one Group allocated by prepareGroup. Its draw
		// list can consolidate equal materials while keeping different IDs apart.
		for (int start = 0; start < submits.size();) {
			var material = IrisVulkanEntityContext.fromSubmit(submits.get(start));
			int end = start + 1;
			while (end < submits.size() && material.equals(IrisVulkanEntityContext.fromSubmit(submits.get(end)))) end++;
			try (var ignored = IrisVulkanEntityContext.enter(material)) {
				original.call(renderer, context, submits.subList(start, end));
			}
			start = end;
		}
	}
}
