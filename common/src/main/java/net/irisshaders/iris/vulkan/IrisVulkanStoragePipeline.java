package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanRenderPassAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.KHRPushDescriptor.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/** Adds storage descriptors to Mojang's real graphics pipelines without exposing them as vanilla textures. */
public final class IrisVulkanStoragePipeline {
	private static final int STAGES = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
	private static final String QUALIFIERS = "(?:(?:coherent|volatile|restrict|readonly|writeonly|lowp|mediump|highp)\\s+)*";
	private static final Pattern IMAGE = Pattern.compile("(?m)^\\h*(?:layout\\s*\\(([^)]*)\\)\\s*)?(" + QUALIFIERS
		+ ")uniform\\s+(" + QUALIFIERS + ")([iu]?image\\w+)\\s+([A-Za-z_]\\w*)\\s*;");
	private static final Pattern ADVANCED = Pattern.compile("\\b(?:[iu]?image\\w+|[iu]?sampler(?:1D|3D)\\w*|buffer)\\b");
	private static final Map<VulkanRenderPipeline, List<IrisVulkanStorageReflection.Binding>> PIPELINES =
		Collections.synchronizedMap(new WeakHashMap<>());

	private IrisVulkanStoragePipeline() { }

	/** Supply the actual pack formats before shaderc validates unformatted OpenGL image declarations. */
	public static String prepareSource(String source) {
		if (source == null) return null;
		Map<String, String> formats = new LinkedHashMap<>();
		Iris.getCurrentPack().ifPresent(pack -> pack.getIrisCustomImages().forEach(image ->
			formats.put(image.name(), IrisVulkanStorageResources.glslFormat(image.internalTextureFormat()))));
		Matcher images = IMAGE.matcher(source);
		while (images.find()) {
			String name = images.group(5);
			var image = IrisVulkanStorageResources.image(name);
			if (image != null) formats.put(name, image.glslFormat());
			else {
				int target = IrisVulkanColorImages.target(name);
				if (target >= 0) {
					var gpuFormat = IrisVulkanGbufferTargets.effectiveFormat(target, null);
					if (gpuFormat != null) formats.putIfAbsent(name, IrisVulkanColorImages.format(gpuFormat));
				}
			}
		}
		return prepareSource(source, formats);
	}

	public static String prepareSource(String source, Map<String, String> formats) {
		if (source == null) return null;
		Matcher images = IMAGE.matcher(source);
		StringBuilder result = new StringBuilder();
		while (images.find()) {
			String name = images.group(5);
			String format = formats.get(name);
			List<String> layout = new ArrayList<>();
			boolean explicitFormat = false;
			if (images.group(1) != null) for (String qualifier : images.group(1).split(",")) {
				String value = qualifier.trim();
				if (isImageFormat(value)) {
					explicitFormat = true;
					if (format != null && !format.equals(value)) {
						throw new IllegalArgumentException("Storage image " + name + " declares " + value + " but its pack allocation is " + format);
					}
				}
				layout.add(value);
			}
			if (!explicitFormat) {
				if (format == null) throw new UnsupportedOperationException("No native storage image format declared for " + name);
				layout.add(format);
			}
			String replacement = "layout(" + String.join(", ", layout) + ") " + images.group(2) + "uniform "
				+ images.group(3) + images.group(4) + " " + name + ";";
			images.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		images.appendTail(result);
		return result.toString();
	}

	private static boolean isImageFormat(String value) {
		return value.matches("(?:r|rg|rgba)(?:8|16|32)(?:f|i|ui|_snorm)?") || value.equals("rgb10_a2")
			|| value.equals("rgb10_a2ui") || value.equals("r11f_g11f_b10f");
	}

	public static boolean hasAdvancedResources(String... sources) {
		for (String source : sources) if (source != null && ADVANCED.matcher(source).find()) return true;
		for (String sampler : storageSamplerNames()) {
			Pattern declaration = Pattern.compile("\\b[iu]?sampler\\w*\\s+" + Pattern.quote(sampler) + "\\b");
			for (String source : sources) if (source != null && declaration.matcher(source).find()) return true;
		}
		return false;
	}

	public static boolean isStorageSampler(String name) {
		if (IrisVulkanStorageResources.sampledImage(name) != null) return true;
		return Iris.getCurrentPack().map(pack -> pack.getIrisCustomImages().stream().anyMatch(image -> name.equals(image.samplerName())))
			.orElse(false);
	}

	public static Set<String> storageSamplerNames() {
		Set<String> result = new LinkedHashSet<>();
		Iris.getCurrentPack().ifPresent(pack -> pack.getIrisCustomImages().forEach(image -> {
			if (image.samplerName() != null && !image.samplerName().isBlank()) result.add(image.samplerName());
		}));
		return result;
	}

	private static final ThreadLocal<List<IrisVulkanStorageReflection.Binding>> COMPILING_STORAGE = new ThreadLocal<>();

	/** The backend owns its shader modules/layouts; source SPIR-V remains caller-owned. */
	public static VulkanRenderPipeline compile(VulkanDevice device, BackendRenderPipeline.CreateInfo createInfo,
			List<IrisVulkanStorageReflection.Binding> advanced) {
		if (COMPILING_STORAGE.get() != null) throw new IllegalStateException("Nested storage pipeline compilation");
		COMPILING_STORAGE.set(advanced);
		try {
			VulkanRenderPipeline compiled = VulkanRenderPipeline.compile(device, createInfo);
			if (!advanced.isEmpty()) PIPELINES.put(compiled, advanced);
			return compiled;
		} finally {
			COMPILING_STORAGE.remove();
		}
	}

	/** Extend only the descriptor-layout call belonging to an Iris compile on this thread. */
	public static void extendDescriptorLayout(VkDevice device, VkDescriptorSetLayoutCreateInfo createInfo, MemoryStack stack) {
		List<IrisVulkanStorageReflection.Binding> advanced = COMPILING_STORAGE.get();
		if (advanced == null || advanced.isEmpty()) return;
		var normal = createInfo.pBindings();
		int normalCount = normal == null ? 0 : normal.remaining();
		int total = normalCount + advanced.size();
		if (!device.getCapabilities().VK_KHR_push_descriptor) {
			throw new UnsupportedOperationException("Native storage graphics requires VK_KHR_push_descriptor");
		}
		var push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
		var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(push.address());
		vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
		if (total > push.maxPushDescriptors()) {
			throw new UnsupportedOperationException("Native graphics requires " + total + " push descriptors; device supports " + push.maxPushDescriptors());
		}
		VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(total, stack);
		for (int i = 0; i < normalCount; ++i) bindings.get(i).set(normal.get(normal.position() + i));
		for (int i = 0; i < advanced.size(); ++i) {
			var binding = advanced.get(i);
			if (binding.binding() != normalCount + i) throw new IllegalStateException("Non-contiguous storage descriptor suffix");
			bindings.get(normalCount + i).binding(binding.binding()).descriptorCount(1)
				.descriptorType(binding.kind().vkType()).stageFlags(STAGES);
		}
		createInfo.pBindings(bindings);
	}

	/** Invoke after selecting a pipeline and before each native draw; no barriers are recorded inside rendering. */
	public static void bind(VulkanRenderPass pass) {
		var access = (VKOnly_VulkanRenderPassAccessor) pass;
		VulkanRenderPipeline pipeline = access.iris$getPipeline();
		List<IrisVulkanStorageReflection.Binding> bindings = PIPELINES.get(pipeline);
		if (bindings == null || bindings.isEmpty()) return;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindings.size(), stack);
			for (int i = 0; i < bindings.size(); ++i) {
				var binding = bindings.get(i);
				var write = writes.get(i).sType$Default().dstBinding(binding.binding()).descriptorCount(1)
					.descriptorType(binding.kind().vkType());
				switch (binding.kind()) {
					case STORAGE_BUFFER -> {
						var buffer = IrisVulkanStorageResources.buffer(binding.sourceBinding());
						if (buffer == null) throw missing(pipeline, binding);
						write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.vkBuffer()).offset(0).range(buffer.size()));
					}
					case STORAGE_IMAGE -> {
						var image = IrisVulkanStorageResources.image(binding.name());
						if (image != null) {
							write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
						} else {
							var color = IrisVulkanColorImages.view(binding.name());
							if (!(color instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView nativeView)) throw missing(pipeline, binding);
							write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(nativeView.vkImageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
						}
					}
					case SAMPLED_IMAGE -> {
						var image = IrisVulkanStorageResources.sampledImage(binding.name());
						if (image == null) throw missing(pipeline, binding);
						write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(image.vkImageView())
							.sampler(image.vkSampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
					}
				}
			}
			vkCmdPushDescriptorSetKHR(access.iris$getCommandBuffer(), VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(), 0, writes);
		}
	}

	private static IllegalStateException missing(VulkanRenderPipeline pipeline, IrisVulkanStorageReflection.Binding binding) {
		return new IllegalStateException("Missing native storage resource " + binding.kind() + " " + binding.name()
			+ " for " + IrisVulkanGraphicsCompiler.descriptor(pipeline).getLocation());
	}

	public static List<IrisVulkanStorageReflection.Binding> bindings(VulkanRenderPipeline pipeline) {
		return PIPELINES.getOrDefault(pipeline, List.of());
	}

	/** Native pipeline destruction remains owned by Mojang/Iris's existing fenced destruction path. */
	public static void unregister(RenderPipeline pipeline) {
		synchronized (PIPELINES) { PIPELINES.keySet().removeIf(compiled -> IrisVulkanGraphicsCompiler.descriptor(compiled) == pipeline); }
	}

	public static void unregister(VulkanRenderPipeline pipeline) { PIPELINES.remove(pipeline); }
	public static void unregisterDevice(VulkanDevice device) {
		synchronized (PIPELINES) { PIPELINES.keySet().removeIf(compiled -> compiled.device() == device); }
	}
}
