package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class VKOnly_MixinDefaultChunkRenderer_Shadow {
	@Redirect(method = "prepare", at = @At(value = "FIELD",
		target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useBlockFaceCulling:Z"))
	private boolean iris$includeAllShadowCasterFaces(SodiumOptions.PerformanceSettings options) {
		return !IrisVulkanShadowRenderer.active() && options.useBlockFaceCulling;
	}
}
