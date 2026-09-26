package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** UI labels and the vanilla ground decal are not physical shadow casters. */
@Mixin(SubmitNodeStorage.class)
public class VKOnly_MixinSubmitNodeStorage_Shadow {
	@Inject(method = {"submitShadow", "submitNameTag"}, at = @At("HEAD"), cancellable = true)
	private void iris$excludeNonPhysicalShadowCasters(CallbackInfo ci) {
		if (IrisVulkanShadowRenderer.active()) ci.cancel();
	}
}
