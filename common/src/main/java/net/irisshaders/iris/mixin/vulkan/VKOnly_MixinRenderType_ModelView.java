package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderType.class)
public class VKOnly_MixinRenderType_ModelView {
	@WrapMethod(method = "writeDynamicTransforms")
	private GpuBufferSlice iris$captureUploadedModelView(Matrix4f modelView, Operation<GpuBufferSlice> original) {
		GpuBufferSlice buffer = original.call(modelView);
		// The engine has now applied layer transforms to this matrix and uploaded
		// exactly these values into PreparedRenderType's DynamicTransforms UBO.
		IrisVulkanEntityContext.capturePreparedModelView(buffer, modelView);
		return buffer;
	}
}
