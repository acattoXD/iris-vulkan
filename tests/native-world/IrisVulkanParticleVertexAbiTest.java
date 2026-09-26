package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;

import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests real 26.2 particle producer bytes and shaderc's reflected/rebound particle interface without a GPU. */
public final class IrisVulkanParticleVertexAbiTest {
	public static void main(String[] args) throws Exception {
		VertexFormat alias = IrisVulkanShaderResources.aliasVanillaVertexFormat(DefaultVertexFormat.PARTICLE);
		check(alias.getVertexSize() == 28, "Particle alias must retain the 28-byte producer stride");
		checkAttribute(alias, "iris_Position", 0, GpuFormat.RGB32_FLOAT);
		checkAttribute(alias, "iris_UV0", 12, GpuFormat.RG32_FLOAT);
		checkAttribute(alias, "iris_Color", 20, GpuFormat.RGBA8_UNORM);
		checkAttribute(alias, "iris_UV2", 24, GpuFormat.RG16_SINT);
		checkProducer(alias);
		checkShaderInterfaces(Path.of(args[0]), alias);
		System.out.println("PASS: exact particle producer bytes, 28-byte aliased stride, and compiled particle stage locations");
	}

	private static void checkProducer(VertexFormat alias) {
		try (ByteBufferBuilder staging = new ByteBufferBuilder(112)) {
			BufferBuilder builder = new BufferBuilder(staging, PrimitiveTopology.QUADS, DefaultVertexFormat.PARTICLE);
			for (int i = 0; i < 4; i++) {
				builder.addVertex(i + 1.0f, i + 2.0f, i + 3.0f)
					.setUv(0.125f + i / 16.0f, 0.625f + i / 16.0f)
					.setColor(0xff123456).setLight(0x00f000a0);
			}
			try (var mesh = builder.buildOrThrow()) {
				var bytes = mesh.vertexBuffer().order(ByteOrder.nativeOrder());
				check(bytes.remaining() == 112, "Four particles vertices must occupy exactly 112 bytes");
				for (int i = 0; i < 4; i++) {
					int offset = i * alias.getVertexSize();
					int uv = offset + alias.getElement("iris_UV0").offset();
					check(bytes.getFloat(uv) == 0.125f + i / 16.0f, "U survives native alias, vertex " + i);
					check(bytes.getFloat(uv + 4) == 0.625f + i / 16.0f, "V survives native alias, vertex " + i);
					int color = offset + alias.getElement("iris_Color").offset();
					check(Byte.toUnsignedInt(bytes.get(color)) == 0x12 && Byte.toUnsignedInt(bytes.get(color + 1)) == 0x34
						&& Byte.toUnsignedInt(bytes.get(color + 2)) == 0x56 && Byte.toUnsignedInt(bytes.get(color + 3)) == 0xff,
						"RGBA survives native alias, vertex " + i);
					int light = offset + alias.getElement("iris_UV2").offset();
					check(bytes.getShort(light) == 0xa0 && bytes.getShort(light + 2) == 0xf0, "Light coordinates survive native alias, vertex " + i);
				}
			}
		}
	}

	private static void checkShaderInterfaces(Path dumpDirectory, VertexFormat alias) throws Exception {
		String stem = "iris_particles_minecraft_pipeline_opaque_particle";
		try (GlslCompiler compiler = new GlslCompiler();
			 IntermediaryShaderModule vertex = compiler.createIntermediary(stem + ".vert", Files.readString(dumpDirectory.resolve(stem + ".vert.glsl")), ShaderType.VERTEX);
			 IntermediaryShaderModule fragment = compiler.createIntermediary(stem + ".frag", Files.readString(dumpDirectory.resolve(stem + ".frag.glsl")), ShaderType.FRAGMENT)) {
			List<String> attributes = alias.getElements().stream().map(element -> element.name()).toList();
			List<VulkanBindGroupLayout.Entry> entries = new ArrayList<>();
			addResources(entries, vertex);
			addResources(entries, fragment);
			vertex.rebind(attributes, entries);
			List<String> outputs = names(vertex.outputs());
			fragment.rebind(outputs, entries);
			Map<String, Integer> actualInputs = locations(vertex, vertex.inputs());
			for (int i = 0; i < attributes.size(); i++) {
				String name = attributes.get(i);
				check(actualInputs.get(name) != null && actualInputs.get(name) == i, "SPIR-V " + name + " must use native attribute " + i + ": " + actualInputs);
			}
			Map<String, Integer> vertexOutputs = locations(vertex, vertex.outputs());
			Map<String, Integer> fragmentInputs = locations(fragment, fragment.inputs());
			for (var input : fragmentInputs.entrySet()) {
				check(input.getValue().equals(vertexOutputs.get(input.getKey())), "Particle varying location mismatch: " + input + " versus " + vertexOutputs);
			}
			System.out.println("Particle vertex inputs: " + actualInputs);
			System.out.println("Particle vertex outputs: " + vertexOutputs);
			System.out.println("Particle fragment inputs: " + fragmentInputs);
		}
	}

	private static void addResources(List<VulkanBindGroupLayout.Entry> entries, IntermediaryShaderModule module) throws Exception {
		for (Object uniform : module.uniformBuffers()) {
			addResource(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER, (String) component(uniform, "name"));
		}
		for (Object sampler : module.samplers()) {
			addResource(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE, (String) component(sampler, "name"));
		}
	}

	private static void addResource(List<VulkanBindGroupLayout.Entry> entries, VulkanBindGroupLayout.VulkanBindGroupEntryType type, String name) {
		if (entries.stream().noneMatch(entry -> entry.type() == type && entry.name().equals(name))) {
			entries.add(new VulkanBindGroupLayout.Entry(type, name, null));
		}
	}

	private static List<String> names(List<?> variables) throws Exception {
		List<String> result = new ArrayList<>();
		for (Object variable : variables) result.add((String) component(variable, "name"));
		return result;
	}

	private static Map<String, Integer> locations(IntermediaryShaderModule module, List<?> variables) throws Exception {
		Map<String, Integer> locations = new LinkedHashMap<>();
		for (Object variable : variables) {
			locations.put((String) component(variable, "name"), module.spirv().asIntBuffer().get((Integer) component(variable, "locationOffset")));
		}
		return locations;
	}

	private static Object component(Object record, String name) throws Exception {
		Method accessor = record.getClass().getDeclaredMethod(name);
		accessor.setAccessible(true);
		return accessor.invoke(record);
	}

	private static void checkAttribute(VertexFormat format, String name, int offset, GpuFormat type) {
		var attribute = format.getElement(name);
		check(attribute != null && attribute.offset() == offset && attribute.format() == type, "Unexpected particle attribute " + name + ": " + attribute);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
