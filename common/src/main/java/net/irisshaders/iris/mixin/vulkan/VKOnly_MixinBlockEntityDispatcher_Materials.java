package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public class VKOnly_MixinBlockEntityDispatcher_Materials {
	@WrapMethod(method = "submit")
	private <S extends BlockEntityRenderState> void iris$captureBlockEntity(S state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, Operation<Void> original) {
		var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		int id = ids == null ? 0 : ids.getInt(((VKOnly_BlockEntityStateAccessor) state).iris$blockState());
		try (var ignored = IrisVulkanEntityContext.enter(new IrisVulkanEntityContext.Material(0, id, 0, true))) {
			original.call(state, pose, collector, camera);
		}
	}
}
