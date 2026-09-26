package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.joml.Vector2f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Native compute passes sharing the engine's graphics submission order and deferred resource lifetime. */
public final class IrisVulkanComputeExecutor implements AutoCloseable {
	private static volatile DispatchRecord lastDispatch;
	private static long dispatchSequence;
	private final ProgramSet programs;
	private final Map<ComputeSource, Program> compiled = new IdentityHashMap<>();
	private boolean setupDispatched;
	private boolean closed;
	private long dispatchCount;

	public IrisVulkanComputeExecutor(ProgramSet programs) {
		this.programs = programs;
	}

	/** Setup runs once per pipeline lifetime, after pack storage initialization and uniform registration. */
	public void dispatchSetup(int width, int height) {
		checkOpen();
		if (setupDispatched) return;
		dispatch(programs.getSetup(), TextureStage.SETUP, width, height);
		setupDispatched = true;
	}

	/** shadow.csh runs immediately before the shadow graphics program. */
	public void dispatchShadow(int width, int height) {
		dispatch(programs.getShadowCompute(), TextureStage.GBUFFERS_AND_SHADOW, width, height);
	}

	/** Run before the graphics program at this index, including indices having only a compute source. */
	public void dispatchStage(ProgramArrayId stage, int index, int width, int height) {
		checkOpen();
		ComputeSource[][] sources = programs.getCompute(stage);
		if (index < 0 || index >= sources.length) return;
		dispatch(sources[index], textureStage(stage), width, height);
	}

	public void dispatchFinal(int width, int height) {
		dispatch(programs.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, width, height);
	}

	public boolean hasStage(ProgramArrayId stage, int index) {
		ComputeSource[][] sources = programs.getCompute(stage);
		if (index < 0 || index >= sources.length || sources[index] == null) return false;
		for (ComputeSource source : sources[index]) if (source != null && source.isValid()) return true;
		return false;
	}

	public long dispatchCount() { return dispatchCount; }

	/** Read-only diagnostic of work actually appended to the native encoder; GPU completion requires a fence. */
	public static DispatchRecord lastDispatch() { return lastDispatch; }
	public record DispatchRecord(String name, int localX, int localY, int localZ,
								 int groupsX, int groupsY, int groupsZ, boolean indirect, long sequence) { }

	private void dispatch(ComputeSource[] sources, TextureStage stage, int width, int height) {
		checkOpen();
		if (sources == null) return;
		for (ComputeSource source : sources) {
			if (source == null || !source.isValid()) continue;
			Program program = compiled.computeIfAbsent(source, value -> new Program(device(), value, stage));
			program.dispatch(width, height);
			++dispatchCount;
		}
	}

	private static TextureStage textureStage(ProgramArrayId stage) {
		return switch (stage) {
			case Setup -> TextureStage.SETUP;
			case Begin -> TextureStage.BEGIN;
			case ShadowComposite -> TextureStage.SHADOWCOMP;
			case Prepare -> TextureStage.PREPARE;
			case Deferred -> TextureStage.DEFERRED;
			case Composite -> TextureStage.COMPOSITE_AND_FINAL;
		};
	}

	private static VulkanDevice device() {
		var backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend();
		if (backend instanceof VulkanDevice vulkan) return vulkan;
		throw new IllegalStateException("Native compute requires Minecraft's Vulkan device");
	}

	private void checkOpen() {
		if (closed) throw new IllegalStateException("Native compute executor is closed");
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		compiled.values().forEach(Program::close);
		compiled.clear();
	}

	private final class Program implements AutoCloseable {
		private final VulkanDevice device;
		private final ComputeSource source;
		private final TextureStage stage;
		private final IrisVulkanComputeCompiler.Prepared prepared;
		private final Vector3i localSize;
		private final IrisVulkanComputeBindings.DeviceLimits limits;
		private final List<IrisVulkanComputeBindings.DescriptorCount> poolCounts;
		private final boolean pushDescriptors;
		private long descriptorLayout;
		private long pipelineLayout;
		private long pipeline;
		private boolean logged;

		private Program(VulkanDevice device, ComputeSource source, TextureStage stage) {
			this.device = device;
			this.source = source;
			this.stage = stage;
			this.prepared = IrisVulkanComputeCompiler.prepare(source);
			this.poolCounts = IrisVulkanComputeBindings.poolCounts(prepared.descriptors().stream()
				.mapToInt(descriptor -> descriptor.kind().vkType()).toArray());
			for (var field : prepared.uniforms()) {
				if (IrisVulkanUniformSnapshot.field(field.name(), field.type(), null).isEmpty()) {
					throw new UnsupportedOperationException("Native compute " + source.getName() + " has no live uniform source for "
						+ field.name() + ": " + IrisVulkanUniformSnapshot.unsupportedReason(field.name(), field.type(), null));
				}
			}
			try (var spirv = IrisVulkanComputeCompiler.compileSpirv(prepared); MemoryStack stack = MemoryStack.stackPush()) {
				localSize = new Vector3i(spirv.localSize());
				limits = IrisVulkanComputeBindings.queryLimits(device.vkDevice(), stack);
				pushDescriptors = limits.usePushDescriptors(prepared.descriptors().size());
				validateQueue(device, stack);
				limits.validateLocalSize(localSize, source.getName());
				var output = stack.mallocLong(1);
				VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(prepared.descriptors().size(), stack);
				for (int i = 0; i < prepared.descriptors().size(); ++i) {
					var descriptor = prepared.descriptors().get(i);
					bindings.get(i).binding(descriptor.binding()).descriptorType(descriptor.kind().vkType())
						.descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
				}
				check(vkCreateDescriptorSetLayout(device.vkDevice(), VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().flags(pushDescriptors ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0)
					.pBindings(bindings), null, output), "create compute descriptor layout");
				descriptorLayout = output.get(0);
				check(vkCreatePipelineLayout(device.vkDevice(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(descriptorLayout)), null, output), "create compute pipeline layout");
				pipelineLayout = output.get(0);
				check(vkCreateShaderModule(device.vkDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default()
					.pCode(spirv.bytes()), null, output), "create compute shader module");
				long module = output.get(0);
				try {
					VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
					info.get(0).sType$Default().layout(pipelineLayout).stage(VkPipelineShaderStageCreateInfo.calloc(stack)
						.sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main")));
					check(vkCreateComputePipelines(device.vkDevice(), VK_NULL_HANDLE, info, null, output), "create compute pipeline");
					pipeline = output.get(0);
				} finally {
					vkDestroyShaderModule(device.vkDevice(), module, null);
				}
			} catch (RuntimeException | Error exception) {
				close();
				throw exception;
			}
		}

		private void dispatch(int width, int height) {
			if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid compute viewport " + width + "x" + height);
			var encoder = device.createCommandEncoder();
			// Fresh values for this dispatch. The engine copies and retains this slice through
			// submission completion; it must not be cached, re-sliced via its buffer, or closed.
			GpuBufferSlice snapshot = prepared.uniforms().isEmpty() ? null : IrisVulkanUniformSnapshot.uploadTransient(prepared.uniforms());
			long descriptorPool = VK_NULL_HANDLE;
			boolean submitted = false;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Vector3i groups = source.getIndirectPointer() == null ? workGroups(source, localSize, width, height) : null;
				if (groups != null) limits.validateWorkGroups(groups, source.getName());
				IrisVulkanStorageResources.Buffer indirectBuffer = null;
				if (groups == null) {
					var pointer = source.getIndirectPointer();
					indirectBuffer = IrisVulkanStorageResources.buffer(pointer.buffer());
					if (indirectBuffer == null || pointer.offset() < 0 || (pointer.offset() & 3) != 0 || pointer.offset() > indirectBuffer.size() - 12) {
						throw new IllegalStateException("Invalid native compute indirect pointer for " + source.getName() + ": " + pointer);
					}
				}
				long descriptorSet = VK_NULL_HANDLE;
				if (!pushDescriptors && !prepared.descriptors().isEmpty()) {
					var output = stack.mallocLong(1);
					VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(poolCounts.size(), stack);
					for (int i = 0; i < poolCounts.size(); ++i) {
						var count = poolCounts.get(i);
						sizes.get(i).type(count.type()).descriptorCount(count.count());
					}
					check(vkCreateDescriptorPool(device.vkDevice(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
						.maxSets(1).pPoolSizes(sizes), null, output), "create compute descriptor pool");
					descriptorPool = output.get(0);
					check(vkAllocateDescriptorSets(device.vkDevice(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
						.descriptorPool(descriptorPool).pSetLayouts(stack.longs(descriptorLayout)), output), "allocate compute descriptor set");
					descriptorSet = output.get(0);
				}
				VkWriteDescriptorSet.Buffer writes = descriptorWrites(descriptorSet, snapshot, stack);
				if (!pushDescriptors && writes.hasRemaining()) vkUpdateDescriptorSets(device.vkDevice(), writes, null);
				var commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
				IrisVulkanStorageResources.barrier(commandBuffer);
				vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
				if (pushDescriptors) {
					// Push descriptors have independent compute/graphics state. dstSet is ignored;
					// the per-dispatch image views, samplers and UBO slice are copied into this command.
					if (writes.hasRemaining()) vkCmdPushDescriptorSetKHR(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
						pipelineLayout, 0, writes);
				} else if (descriptorSet != VK_NULL_HANDLE) {
					vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
						stack.longs(descriptorSet), null);
				}
				if (groups != null) {
					vkCmdDispatch(commandBuffer, groups.x, groups.y, groups.z);
				} else {
					vkCmdDispatchIndirect(commandBuffer, indirectBuffer.vkBuffer(), source.getIndirectPointer().offset());
				}
				IrisVulkanStorageResources.barrier(commandBuffer);
				check(vkEndCommandBuffer(commandBuffer), "end compute command buffer");
				// execute() ends any preceding transfer/graphics command buffer and appends this
				// primary buffer in order. Future render passes bind their own graphics state.
				encoder.execute(commandBuffer);
				submitted = true;
				lastDispatch = new DispatchRecord(source.getName(), localSize.x, localSize.y, localSize.z,
					groups == null ? -1 : groups.x, groups == null ? -1 : groups.y, groups == null ? -1 : groups.z,
					groups == null, ++dispatchSequence);
				if (!logged) {
					Iris.logger.info("Dispatched native Vulkan compute {}: localSize={}, workGroups={}, {} resource bindings ({} descriptors).",
						source.getName(), localSize, groups == null ? "indirect" : groups, prepared.descriptors().size(),
						pushDescriptors ? "push" : "allocated");
					logged = true;
				}
			} finally {
				if (descriptorPool != VK_NULL_HANDLE) {
					long retainedPool = descriptorPool;
					if (submitted) encoder.queueForDestroy(() -> vkDestroyDescriptorPool(device.vkDevice(), retainedPool, null));
					else vkDestroyDescriptorPool(device.vkDevice(), retainedPool, null);
				}
			}
		}

		private VkWriteDescriptorSet.Buffer descriptorWrites(long set, GpuBufferSlice snapshot, MemoryStack stack) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(prepared.descriptors().size(), stack);
			for (int i = 0; i < prepared.descriptors().size(); ++i) {
				var descriptor = prepared.descriptors().get(i);
				VkWriteDescriptorSet write = writes.get(i).sType$Default().dstSet(set).dstBinding(descriptor.binding())
					.descriptorType(descriptor.kind().vkType()).descriptorCount(1);
				switch (descriptor.kind()) {
					case UNIFORM_BUFFER -> {
						if (snapshot == null || !(snapshot.buffer() instanceof VulkanGpuBuffer buffer)) {
							throw new IllegalStateException("Compute snapshot is not a Vulkan buffer slice");
						}
						write.pBufferInfo(IrisVulkanComputeBindings.uniformSlice(buffer.vkBuffer(), buffer.size(),
							snapshot.offset(), snapshot.length(), limits, stack));
					}
					case STORAGE_BUFFER -> {
						var buffer = IrisVulkanStorageResources.buffer(descriptor.sourceBinding());
						if (buffer == null) throw missing(descriptor);
						write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.vkBuffer()).offset(0).range(buffer.size()));
					}
					case STORAGE_IMAGE -> {
						var image = IrisVulkanStorageResources.image(descriptor.name());
						long imageView;
						if (image != null) {
							if (!image.glslFormat().equals(descriptor.imageFormat())) throw new IllegalStateException("Compute image format changed: " + descriptor.name());
							imageView = image.vkImageView();
						} else {
							var color = IrisVulkanColorImages.view(descriptor.name(), descriptor.type(), descriptor.imageFormat());
							if (!(color instanceof VulkanGpuTextureView nativeView)) throw missing(descriptor);
							imageView = nativeView.vkImageView();
						}
						write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(imageView)
							.imageLayout(VK_IMAGE_LAYOUT_GENERAL));
					}
					case SAMPLED_IMAGE -> {
						SampledImage image = sampledImage(descriptor);
						write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.view())
							.sampler(image.sampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
					}
				}
			}
			return writes;
		}

		private SampledImage sampledImage(IrisVulkanComputeCompiler.Descriptor descriptor) {
			String name = descriptor.name();
			var storage = IrisVulkanStorageResources.sampledImage(name);
			if (storage != null) return new SampledImage(storage.vkImageView(), storage.vkSampler());
			var custom = name.equals("noisetex")
				? IrisVulkanCustomTextures.noise(programs.getPackDirectives().getNoiseTextureResolution())
				: IrisVulkanCustomTextures.find(stage, name);
			if (custom != null) return sampled(custom.view(), custom.sampler());
			var shadow = IrisVulkanShadowRenderer.binding(name);
			if (shadow != null) return sampled(shadow.view(), shadow.sampler());
			GpuTextureView view = IrisVulkanGbufferTargets.colorSamplerView(name);
			if (view != null) return sampled(view, IrisVulkanGbufferTargets.samplerForTarget(view));
			view = IrisVulkanGbufferTargets.depthSamplerView(name);
			if (view != null) return sampled(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			throw missing(descriptor);
		}

		private IllegalStateException missing(IrisVulkanComputeCompiler.Descriptor descriptor) {
			return new IllegalStateException("Missing native compute " + source.getName() + " " + descriptor.kind() + " " + descriptor.name());
		}

		@Override public void close() {
			long retiredPipeline = pipeline, retiredLayout = pipelineLayout, retiredDescriptors = descriptorLayout;
			pipeline = pipelineLayout = descriptorLayout = VK_NULL_HANDLE;
			device.createCommandEncoder().queueForDestroy(() -> {
				if (retiredPipeline != VK_NULL_HANDLE) vkDestroyPipeline(device.vkDevice(), retiredPipeline, null);
				if (retiredLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device.vkDevice(), retiredLayout, null);
				if (retiredDescriptors != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device.vkDevice(), retiredDescriptors, null);
			});
		}
	}

	private record SampledImage(long view, long sampler) { }

	private static SampledImage sampled(GpuTextureView view, GpuSampler sampler) {
		if (!(view instanceof VulkanGpuTextureView image) || !(sampler instanceof VulkanGpuSampler nativeSampler)) {
			throw new IllegalStateException("Compute sampled texture is not native Vulkan");
		}
		return new SampledImage(image.vkImageView(), nativeSampler.vkSampler());
	}

	/** Mirrors Iris's OpenGL compute work-group rounding, including workGroupsRender. */
	public static Vector3i workGroups(ComputeSource source, Vector3i localSize, int width, int height) {
		return calculateWorkGroups(source.getWorkGroups(), source.getWorkGroupRelative(), localSize, width, height);
	}

	static Vector3i calculateWorkGroups(Vector3i absolute, Vector2f relative, Vector3i localSize, int width, int height) {
		if (absolute != null) return new Vector3i(absolute);
		double scaledWidth = relative == null ? width : Math.ceil(width * relative.x);
		double scaledHeight = relative == null ? height : Math.ceil(height * relative.y);
		return new Vector3i((int) Math.ceil(scaledWidth / localSize.x), (int) Math.ceil(scaledHeight / localSize.y), 1);
	}

	private static void validateQueue(VulkanDevice device, MemoryStack stack) {
		var count = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, null);
		var families = VkQueueFamilyProperties.calloc(count.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(device.vkDevice().getPhysicalDevice(), count, families);
		if ((families.get(device.graphicsQueue().queueFamilyIndex()).queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) {
			throw new UnsupportedOperationException("Minecraft's graphics queue does not support native compute dispatch");
		}
	}

	private static void check(int result, String operation) {
		if (result != VK_SUCCESS) throw new IllegalStateException("Failed to " + operation + " (VkResult " + result + ")");
	}
}
