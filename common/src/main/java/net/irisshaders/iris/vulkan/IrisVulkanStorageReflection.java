package net.irisshaders.iris.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Reflects writable resources that Minecraft's shader frontend deliberately does not model. */
public final class IrisVulkanStorageReflection {
	private IrisVulkanStorageReflection() { }

	public enum Kind {
		STORAGE_IMAGE(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
		STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
		SAMPLED_IMAGE(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
		private final int vkType;
		Kind(int vkType) { this.vkType = vkType; }
		public int vkType() { return vkType; }
	}

	public record Resource(String name, Kind kind, int sourceBinding, int bindingWord, int setWord, int dimension) {
		String key() { return kind.name() + ":" + name; }
	}

	public record Binding(int binding, Kind kind, String name, int sourceBinding, int dimension) {
		String key() { return kind.name() + ":" + name; }
	}

	public record Reflection(List<Resource> resources) {
		public Set<String> sampledNames() {
			return resources.stream().filter(r -> r.kind() == Kind.SAMPLED_IMAGE).map(Resource::name)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
	}

	public static Reflection reflect(ByteBuffer spirv, Set<String> storageSamplers) {
		List<Resource> resources = new ArrayList<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var pointer = stack.mallocPointer(1);
			check(spvc_context_create(pointer), "create reflection context");
			long context = pointer.get(0);
			try {
				var words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
				check(spvc_context_parse_spirv(context, words, words.remaining(), pointer), "parse graphics SPIR-V");
				long ir = pointer.get(0);
				check(spvc_context_create_compiler(context, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "create reflection compiler");
				long compiler = pointer.get(0);
				check(spvc_compiler_create_shader_resources(compiler, pointer), "reflect graphics resources");
				long shaderResources = pointer.get(0);
				collect(resources, compiler, shaderResources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, Kind.STORAGE_IMAGE, storageSamplers, stack);
				collect(resources, compiler, shaderResources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, Kind.STORAGE_BUFFER, storageSamplers, stack);
				collect(resources, compiler, shaderResources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, Kind.SAMPLED_IMAGE, storageSamplers, stack);
			} finally {
				spvc_context_destroy(context);
			}
		}
		return new Reflection(List.copyOf(resources));
	}

	private static void collect(List<Resource> result, long compiler, long resources, int resourceType, Kind kind,
								Set<String> storageSamplers, MemoryStack stack) {
		var pointer = stack.mallocPointer(1);
		var count = stack.mallocPointer(1);
		check(spvc_resources_get_resource_list_for_type(resources, resourceType, pointer, count), "list " + kind);
		int length = Math.toIntExact(count.get(0));
		if (length == 0) return;
		var entries = SpvcReflectedResource.create(pointer.get(0), length);
		for (int i = 0; i < length; ++i) {
			var entry = entries.get(i);
			String name = entry.nameString();
			long type = spvc_compiler_get_type_handle(compiler, entry.type_id());
			int dimension = kind == Kind.STORAGE_BUFFER ? -1 : spvc_type_get_image_dimension(type);
			if (kind == Kind.SAMPLED_IMAGE && (dimension == SpvDim2D || dimension == SpvDimCube || dimension == SpvDimBuffer)
				&& !storageSamplers.contains(name)) continue;
			if (spvc_type_get_num_array_dimensions(type) != 0) {
				throw new UnsupportedOperationException("Native Vulkan storage descriptor arrays are not represented: " + name);
			}
			int bindingWord = offset(compiler, entry.id(), SpvDecorationBinding, name, stack);
			int setWord = offset(compiler, entry.id(), SpvDecorationDescriptorSet, name, stack);
			result.add(new Resource(name, kind, spvc_compiler_get_decoration(compiler, entry.id(), SpvDecorationBinding),
				bindingWord, setWord, dimension));
		}
	}

	private static int offset(long compiler, int id, int decoration, String name, MemoryStack stack) {
		var offset = stack.mallocInt(1);
		if (!spvc_compiler_get_binary_offset_for_decoration(compiler, id, decoration, offset)) {
			throw new IllegalArgumentException("Missing SPIR-V descriptor decoration " + decoration + " for " + name);
		}
		return offset.get(0);
	}

	/** Stable set-zero suffix after Mojang's normal descriptors; same names share a binding across stages. */
	public static List<Binding> bindings(int firstBinding, Reflection... stages) {
		Map<String, Binding> bindings = new LinkedHashMap<>();
		for (Reflection stage : stages) for (Resource resource : stage.resources()) {
			Binding previous = bindings.get(resource.key());
			if (previous != null) {
				if (previous.dimension() != resource.dimension()
					|| resource.kind() == Kind.STORAGE_BUFFER && previous.sourceBinding() != resource.sourceBinding()) {
					throw new IllegalArgumentException("Conflicting native storage declaration across shader stages: " + resource.name());
				}
				continue;
			}
			bindings.put(resource.key(), new Binding(firstBinding + bindings.size(), resource.kind(), resource.name(),
				resource.sourceBinding(), resource.dimension()));
		}
		return List.copyOf(bindings.values());
	}

	public static void rebind(ByteBuffer spirv, Reflection reflection, List<Binding> bindings) {
		Map<String, Binding> byName = new LinkedHashMap<>();
		for (Binding binding : bindings) byName.put(binding.key(), binding);
		var words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		for (Resource resource : reflection.resources()) {
			Binding binding = byName.get(resource.key());
			if (binding == null) throw new IllegalArgumentException("No native descriptor assigned for " + resource.name());
			words.put(resource.bindingWord(), binding.binding());
			words.put(resource.setWord(), 0);
		}
	}

	private static void check(int result, String action) {
		if (result != SPVC_SUCCESS) throw new IllegalStateException("Could not " + action + " (SPIRV-Cross " + result + ")");
	}
}
