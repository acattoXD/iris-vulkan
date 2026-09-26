package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A caster can affect the player's view while its own geometry is occluded from that view. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class VKOnly_MixinSodiumWorldRenderer_Shadow {
	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void iris$ignorePlayerCameraOcclusionForShadowCasters(CallbackInfoReturnable<Boolean> cir) {
		if (IrisVulkanShadowRenderer.active()) cir.setReturnValue(true);
	}
}
