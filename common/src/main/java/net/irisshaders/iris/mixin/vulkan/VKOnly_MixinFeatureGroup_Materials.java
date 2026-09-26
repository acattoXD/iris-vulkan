package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class VKOnly_MixinFeatureGroup_Materials {
	@Shadow private StagedVertexBuffer.Draw lastDraw;
	@Unique private IrisVulkanEntityContext.Material iris$lastMaterial;
	@Inject(method = "getVertexBuilder", at = @At("HEAD"))
	private void iris$splitMaterialDraw(RenderType renderType, CallbackInfoReturnable<VertexConsumer> ci) {
		var material = IrisVulkanEntityContext.current();
		if (!material.equals(iris$lastMaterial)) {
			lastDraw = null;
			iris$lastMaterial = material;
		}
	}
}
