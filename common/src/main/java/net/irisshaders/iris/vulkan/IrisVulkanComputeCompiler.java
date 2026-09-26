package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.VK10.*;

/** Compiles the pack's preprocessed compute source, preserving its image and SSBO operations. */
public final class IrisVulkanComputeCompiler {
	private static final String QUALIFIERS = "(?:(?:coherent|volatile|restrict|readonly|writeonly|lowp|mediump|highp)\\s+)*";
	private static final Pattern UNIFORM = Pattern.compile("(?m)^\\h*(?:layout\\s*\\(([^)]*)\\)\\s*)?(" + QUALIFIERS
		+ ")uniform\\s+(" + QUALIFIERS + ")([A-Za-z_]\\w*)\\s+([^;{}]+);");
	private static final Pattern STORAGE_BLOCK = Pattern.compile("(?m)^\\h*(?:layout\\s*\\(([^)]*)\\)\\s*)?("
		+ QUALIFIERS + ")buffer\\s+([A-Za-z_]\\w*)\\s*\\{");
	private static final Pattern DECLARATOR = Pattern.compile("\\h*([A-Za-z_]\\w*)\\h*(\\[[^]]*])?\\h*(?:=[\\s\\S]*)?");
	private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");
	private static final Pattern VERSION = Pattern.compile("(?m)^\\h*#\\h*version[^\\r\\n]*");

	private IrisVulkanComputeCompiler() { }

	public enum Kind {
		UNIFORM_BUFFER(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
		SAMPLED_IMAGE(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
		STORAGE_IMAGE(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
		STORAGE_BUFFER(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);

		private final int vkType;
		Kind(int vkType) { this.vkType = vkType; }
		public int vkType() { return vkType; }
	}

	/** sourceBinding is the pack's SSBO index; binding is the dense Vulkan set-zero binding. */
	public record Descriptor(int binding, Kind kind, String name, String type, int sourceBinding, String imageFormat) {
		public Descriptor(int binding, Kind kind, String name, String type, int sourceBinding) {
			this(binding, kind, name, type, sourceBinding, null);
		}
	}

	public record Prepared(String name, String source, List<Descriptor> descriptors,
						   List<IrisVulkanUniformSnapshot.Field> uniforms) { }

	public static Prepared prepare(ComputeSource compute) {
		Map<String, String> formats = new LinkedHashMap<>();
		IrisVulkanColorImages.declarations(compute.getParent()).forEach((name, image) ->
			formats.put(name, IrisVulkanStorageResources.glslFormat(image.internalTextureFormat())));
		for (var information : compute.getParent().getPack().getIrisCustomImages()) {
			formats.put(information.name(), IrisVulkanStorageResources.glslFormat(information.internalTextureFormat()));
		}
		return prepareSource(compute.getName(), compute.getSource().orElseThrow(), formats);
	}

	/** Also used by the offline audit with the exact active ProgramSet source and image formats. */
	public static Prepared prepareSource(String name, String source, Map<String, String> imageFormats) {
		Matcher version = VERSION.matcher(source);
		if (!version.find()) throw new IllegalArgumentException("Compute shader has no #version: " + name);
		source = version.replaceFirst("#version 450 core");
		// Comments must not look like live resource declarations or uses to the binding scanner.
		source = IrisVulkanShaderPruning.maskComments(source);
		source = IrisVulkanShaderCompatibility.renameSamplerParameters(source);
		// Compute packs can still use the compatibility names from GLSL 1.20. Their
		// typed sampler arguments select the same overload of the core built-in.
		source = source.replaceAll("\\btexture(?:1D|2D|3D|Cube)(Lod|Grad|ProjLod|ProjGrad|Proj)?(?=\\s*\\()", "texture$1");
		source = removeUnusedUniforms(source);
		List<Descriptor> descriptors = new ArrayList<>();
		LinkedHashMap<String, IrisVulkanUniformSnapshot.Field> fields = new LinkedHashMap<>();
		Matcher uniforms = UNIFORM.matcher(source);
		StringBuilder result = new StringBuilder();
		while (uniforms.find()) {
			String type = uniforms.group(4);
			String qualifiers = uniforms.group(2) + uniforms.group(3);
			StringBuilder replacement = new StringBuilder();
			for (String declaration : splitDeclarators(uniforms.group(5))) {
				Matcher declarator = DECLARATOR.matcher(declaration);
				if (!declarator.matches()) throw unsupported(name, "uniform declaration " + declaration);
				String uniformName = declarator.group(1);
				String array = declarator.group(2);
				if (type.matches("[iu]?sampler.*") || type.matches("[iu]?image.*")) {
					if (array != null) throw unsupported(name, "opaque uniform array " + uniformName);
					Kind kind = type.matches("[iu]?image.*") ? Kind.STORAGE_IMAGE : Kind.SAMPLED_IMAGE;
					int binding = descriptors.size();
					String imageFormat = null;
					if (kind == Kind.STORAGE_IMAGE) {
						String explicit = explicitImageFormat(uniforms.group(1));
						imageFormat = imageFormats.get(uniformName);
						if (imageFormat != null && explicit != null && !imageFormat.equals(explicit))
							throw unsupported(name, "image format mismatch for " + uniformName + ": shader " + explicit + ", allocation " + imageFormat);
						if (imageFormat == null) imageFormat = explicit;
						if (imageFormat == null) throw unsupported(name, "image format for " + uniformName);
					}
					descriptors.add(new Descriptor(binding, kind, uniformName, type, -1, imageFormat));
					replacement.append("layout(set = 0, binding = ").append(binding);
					if (kind == Kind.STORAGE_IMAGE) {
						replacement.append(", ").append(imageFormat);
					}
					replacement.append(") ").append(qualifiers).append("uniform ").append(type)
						.append(' ').append(uniformName).append(";\n");
				} else {
					if (array != null || !List.of("bool", "int", "uint", "float", "vec2", "vec3", "vec4",
						"ivec2", "ivec3", "ivec4", "mat3", "mat4").contains(type)) {
						throw unsupported(name, "uniform layout " + type + " " + uniformName);
					}
					// The executor checks these against live snapshot sources after custom uniforms
					// have been registered. Source compilation itself requires no running client.
					fields.put(uniformName, new IrisVulkanUniformSnapshot.Field(uniformName, type));
				}
			}
			uniforms.appendReplacement(result, Matcher.quoteReplacement(replacement.toString()));
		}
		uniforms.appendTail(result);
		source = result.toString();
		Matcher blocks = STORAGE_BLOCK.matcher(source);
		result = new StringBuilder();
		while (blocks.find()) {
			String layout = blocks.group(1);
			Matcher originalBinding = BINDING.matcher(layout == null ? "" : layout);
			if (!originalBinding.find()) throw unsupported(name, "SSBO without an explicit pack binding: " + blocks.group(3));
			int binding = descriptors.size();
			descriptors.add(new Descriptor(binding, Kind.STORAGE_BUFFER, blocks.group(3), "buffer",
				Integer.parseInt(originalBinding.group(1))));
			String memoryLayout = layout.contains("std140") ? "std140" : "std430";
			blocks.appendReplacement(result, Matcher.quoteReplacement("layout(" + memoryLayout + ", set = 0, binding = "
				+ binding + ") " + blocks.group(2) + "buffer " + blocks.group(3) + " {"));
		}
		blocks.appendTail(result);
		source = result.toString();
		if (Pattern.compile("\\buniform\\s+[A-Za-z_]\\w*\\s*\\{").matcher(source).find()) {
			throw unsupported(name, "a pack-owned uniform block without a live buffer source");
		}
		if (!fields.isEmpty()) {
			int binding = descriptors.size();
			descriptors.add(new Descriptor(binding, Kind.UNIFORM_BUFFER, IrisVulkanUniformSnapshot.BLOCK_NAME, "uniform", -1));
			String block = IrisVulkanUniformSnapshot.declaration(fields.values())
				.replace("layout(std140)", "layout(std140, set = 0, binding = " + binding + ")");
			int insertion = source.indexOf('\n') + 1;
			source = source.substring(0, insertion) + block + source.substring(insertion);
		}
		return new Prepared(name, source, List.copyOf(descriptors), List.copyOf(fields.values()));
	}

	private static String removeUnusedUniforms(String source) {
		Map<String, Integer> references = new LinkedHashMap<>();
		Matcher names = Pattern.compile("[A-Za-z_]\\w*").matcher(source);
		while (names.find()) references.merge(names.group(), 1, Integer::sum);
		Matcher uniforms = UNIFORM.matcher(source);
		StringBuilder result = new StringBuilder();
		while (uniforms.find()) {
			List<String> retained = new ArrayList<>();
			for (String declaration : splitDeclarators(uniforms.group(5))) {
				Matcher declarator = DECLARATOR.matcher(declaration);
				if (!declarator.matches() || references.getOrDefault(declarator.group(1), 0) > 1) retained.add(declaration);
			}
			String replacement = retained.isEmpty() ? "" : uniforms.group().substring(0,
				uniforms.start(5) - uniforms.start()) + String.join(", ", retained) + ";";
			uniforms.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		uniforms.appendTail(result);
		return result.toString();
	}

	private static List<String> splitDeclarators(String declaration) {
		List<String> result = new ArrayList<>();
		int start = 0, nesting = 0;
		for (int i = 0; i <= declaration.length(); ++i) {
			char c = i == declaration.length() ? ',' : declaration.charAt(i);
			if (c == '(' || c == '[') ++nesting;
			else if (c == ')' || c == ']') --nesting;
			else if (c == ',' && nesting == 0) {
				result.add(declaration.substring(start, i));
				start = i + 1;
			}
		}
		return result;
	}

	private static String explicitImageFormat(String layout) {
		if (layout == null) return null;
		for (String token : layout.split(",")) {
			String value = token.trim();
			if (value.matches("(?:r|rg|rgba)(?:8|16|32)(?:f|i|ui|_snorm)?") || value.equals("rgb10_a2")
				|| value.equals("rgb10_a2ui") || value.equals("r11f_g11f_b10f")) return value;
		}
		return null;
	}

	private static UnsupportedOperationException unsupported(String name, String resource) {
		return new UnsupportedOperationException("Native Vulkan compute " + name + " cannot bind " + resource);
	}

	public static Spirv compileSpirv(Prepared prepared) {
		long compiler = shaderc_compiler_initialize();
		long options = shaderc_compile_options_initialize();
		if (compiler == 0 || options == 0) {
			if (compiler != 0) shaderc_compiler_release(compiler);
			if (options != 0) shaderc_compile_options_release(options);
			throw new IllegalStateException("Could not initialize Vulkan compute shaderc");
		}
		long result = 0;
		try {
			shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
			shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_5);
			shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
			result = shaderc_compile_into_spv(compiler, prepared.source(), shaderc_compute_shader,
				prepared.name() + ".csh", "main", options);
			if (result == 0 || shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
				throw new IllegalStateException("Vulkan compute compilation failed for " + prepared.name() + ": "
					+ (result == 0 ? "shaderc returned no result" : shaderc_result_get_error_message(result)));
			}
			ByteBuffer binary = shaderc_result_get_bytes(result);
			if (binary == null || binary.remaining() < 20) throw new IllegalStateException("Empty compute SPIR-V: " + prepared.name());
			ByteBuffer owned = MemoryUtil.memAlloc(binary.remaining()).order(ByteOrder.nativeOrder());
			owned.put(binary).flip();
			try {
				return new Spirv(owned, localSize(owned));
			} catch (RuntimeException exception) {
				MemoryUtil.memFree(owned);
				throw exception;
			}
		} finally {
			if (result != 0) shaderc_result_release(result);
			shaderc_compile_options_release(options);
			shaderc_compiler_release(compiler);
		}
	}

	/** Read OpExecutionMode LocalSize from the compiled module rather than guessing GLSL constant expressions. */
	private static Vector3i localSize(ByteBuffer binary) {
		var words = binary.asIntBuffer();
		for (int offset = 5; offset < words.limit();) {
			int instruction = words.get(offset), count = instruction >>> 16, opcode = instruction & 0xffff;
			if (count == 0 || offset + count > words.limit()) throw new IllegalArgumentException("Malformed compute SPIR-V");
			if (opcode == 16 && count >= 6 && words.get(offset + 2) == 17) {
				return new Vector3i(words.get(offset + 3), words.get(offset + 4), words.get(offset + 5));
			}
			offset += count;
		}
		throw new UnsupportedOperationException("Compute module has no fixed LocalSize execution mode");
	}

	public record Spirv(ByteBuffer bytes, Vector3i localSize) implements AutoCloseable {
		@Override public void close() { MemoryUtil.memFree(bytes); }
	}
}
