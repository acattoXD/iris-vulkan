package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CloudRenderer.class)
public class VKOnly_MixinCloudRenderer_Phases {
	@WrapMethod(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V")
	private void iris$clouds(CloudStatus status, RenderPass pass, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null) {
			status = switch (pipeline.getCloudSetting()) {
				case OFF -> CloudStatus.OFF;
				case FAST -> CloudStatus.FAST;
				case FANCY -> CloudStatus.FANCY;
				case DEFAULT -> status;
			};
		}
		if (status == CloudStatus.OFF) return;
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.CLOUDS)) {
			original.call(status, pass);
		}
	}
}
