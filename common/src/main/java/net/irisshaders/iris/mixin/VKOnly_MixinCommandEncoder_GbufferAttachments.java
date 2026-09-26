package net.irisshaders.iris.mixin;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FrontendCommandEncoder.class)
public class VKOnly_MixinCommandEncoder_GbufferAttachments {
	@ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;", at = @At("HEAD"), argsOnly = true)
	private RenderPassDescriptor iris$rewriteGbufferAttachments(RenderPassDescriptor descriptor) {
		if (net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer.active()) {
			RenderPassDescriptor rewritten = net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer.rewriteRenderPass(descriptor);
			return rewritten == null ? descriptor : rewritten;
		} else if (!net.irisshaders.iris.vulkan.IrisNativeVulkan.worldDevelopmentEnabled()) {
			return IrisVulkanGbufferTargets.rewriteMainRenderPass((CommandEncoder) (Object) this, descriptor);
		}
		return descriptor;
	}

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;", at = @At("RETURN"), cancellable = true)
	private void iris$routeWorldPrograms(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		cir.setReturnValue(net.irisshaders.iris.vulkan.IrisVulkanWorldRenderPass.wrap((CommandEncoder)(Object)this, descriptor, cir.getReturnValue()));
	}
}
