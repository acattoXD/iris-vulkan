package net.irisshaders.iris.mixin;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import net.irisshaders.iris.vulkan.IrisVulkanGbufferTargets;
import net.irisshaders.iris.vulkan.IrisVulkanRenderPassBindings;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Mixin(FrontendRenderPass.class)
public class VKOnly_MixinRenderPass_Bindings {
	@Shadow
	@Final
	private RenderPassBackend backend;

	@Shadow
	@Final
	private List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments;

	private void iris$bindVulkanResources() {
		if (backend instanceof VulkanRenderPass vulkanRenderPass) {
			IrisVulkanRenderPassBindings.apply((RenderPass) (Object) this, vulkanRenderPass, colorAttachments);
		}
	}

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private CompiledRenderPipeline iris$adaptPipelineForGbufferPass(CompiledRenderPipeline compiled) {
		RenderPipeline pipeline = IrisNativeVulkan.descriptorFor(compiled);
		if (pipeline == null) return compiled;
		RenderPipeline adapted = net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer.active()
			? net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer.adaptPipeline(pipeline, colorAttachments)
			: IrisVulkanGbufferTargets.adaptPipelineForGbufferPass(pipeline, colorAttachments);
		return adapted == pipeline ? compiled : IrisNativeVulkan.compiledFor(adapted);
	}

	@Inject(method = "drawIndexed(IIIII)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDrawIndexed(IntBuffer drawParameters, int firstIndex, int vertexOffset, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDrawIndexed(Lorg/lwjgl/PointerBuffer;Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts, IntBuffer baseVertices, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawIndexedIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndexedIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/pipeline/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V", at = @At("HEAD"))
	private <T> void iris$bindVulkanResourcesBeforeDrawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indexBuffer, IndexType indexType, Collection<String> dynamicUniforms, T uniformData, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "draw(IIII)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDraw(int vertexOffset, int vertexCount, int instanceCount, int firstInstance, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDraw(IntBuffer drawParameters, int vertexOffset, int firstInstance, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;Ljava/nio/IntBuffer;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeMultiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}

	@Inject(method = "drawIndirect(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;I)V", at = @At("HEAD"))
	private void iris$bindVulkanResourcesBeforeDrawIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		iris$bindVulkanResources();
	}
}
