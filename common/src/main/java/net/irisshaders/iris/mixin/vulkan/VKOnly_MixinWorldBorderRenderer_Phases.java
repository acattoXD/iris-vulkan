package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WorldBorderRenderer.class)
public class VKOnly_MixinWorldBorderRenderer_Phases {
	@WrapMethod(method = "render")
	private void iris$worldBorder(WorldBorderRenderState state, RenderPass pass, Vec3 camera, double distance, Operation<Void> original) {
		try (var ignored = IrisVulkanPhaseContext.enter(WorldRenderingPhase.WORLD_BORDER)) { original.call(state, pass, camera, distance); }
	}
}
