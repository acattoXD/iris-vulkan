package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ModelFeatureRenderer.Submit.class, BlockModelFeatureRenderer.Submit.class, CustomFeatureRenderer.Submit.class,
	ItemFeatureRenderer.Submit.class, TextFeatureRenderer.Submit.class, MovingBlockFeatureRenderer.Submit.class})
public class VKOnly_MixinFeatureSubmits_Materials implements IrisVulkanEntityContext.MaterialSubmit {
	@Unique private IrisVulkanEntityContext.Material iris$material = IrisVulkanEntityContext.EMPTY;
	@Inject(method = "<init>", at = @At("RETURN"))
	private void iris$captureMaterial(CallbackInfo ci) { iris$material = IrisVulkanEntityContext.current(); }
	@Override public IrisVulkanEntityContext.Material iris$material() { return iris$material; }
}
