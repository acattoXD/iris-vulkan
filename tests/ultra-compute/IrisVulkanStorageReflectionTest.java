package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;

import java.util.Map;
import java.util.Set;

import static net.irisshaders.iris.vulkan.IrisVulkanStorageReflection.Kind.*;

/** Exercises the real Mojang shaderc frontend and native reflection/remapping with mixed graphics resources. */
public final class IrisVulkanStorageReflectionTest {
	public static void main(String[] args) throws Exception {
		String vertex = """
			#version 450 core
			layout(location=0) in vec3 Position;
			layout(location=0) out vec2 uv;
			layout(std140) uniform Camera { mat4 projection; };
			layout(std430, binding=7) buffer BlockData { uint blocks[]; };
			writeonly uniform uimage3D voxel_img;
			uniform sampler2D albedo;
			void main() {
			    uv = Position.xy;
			    blocks[uint(gl_VertexID)] = 21u;
			    imageStore(voxel_img, ivec3(Position), uvec4(blocks[uint(gl_VertexID)]));
			    gl_Position = projection * vec4(Position + textureLod(albedo, uv, 0.0).xyz, 1.0);
			}
			""";
		String fragment = """
			#version 450 core
			layout(location=0) in vec2 uv;
			layout(location=0) out vec4 color;
			layout(std430, binding=7) readonly buffer BlockData { uint blocks[]; };
			uniform sampler2D albedo;
			uniform usampler3D voxel_sampler;
			uniform sampler3D floodfill_sampler;
			uniform sampler2D atlas_sampler;
			layout(r16ui) readonly uniform uimage3D voxel_img;
			void main() {
			    color = texture(albedo, uv) + texture(floodfill_sampler, vec3(uv, 0.5))
			        + texture(atlas_sampler, uv) + vec4(texture(voxel_sampler, vec3(uv, 0.5)))
			        + vec4(imageLoad(voxel_img, ivec3(0))) + vec4(blocks[0]);
			}
			""";
		Map<String, String> formats = Map.of("voxel_img", "r16ui");
		vertex = IrisVulkanStoragePipeline.prepareSource(vertex, formats);
		fragment = IrisVulkanStoragePipeline.prepareSource(fragment, formats);
		if (!vertex.contains("layout(r16ui) writeonly uniform uimage3D voxel_img")) throw new AssertionError("Missing real storage format");
		Set<String> samplers = Set.of("voxel_sampler", "floodfill_sampler", "atlas_sampler");
		try (var compiler = new GlslCompiler();
			 var vs = compiler.createIntermediary("storage-vertex", vertex, ShaderType.VERTEX);
			 var fs = compiler.createIntermediary("storage-fragment", fragment, ShaderType.FRAGMENT)) {
			var vertexResources = IrisVulkanStorageReflection.reflect(vs.spirv(), samplers);
			var fragmentResources = IrisVulkanStorageReflection.reflect(fs.spirv(), samplers);
			if (vertexResources.resources().size() != 2 || fragmentResources.resources().size() != 5) {
				throw new AssertionError("Unexpected reflection: " + vertexResources + " / " + fragmentResources);
			}
			if (fragmentResources.resources().stream().anyMatch(r -> r.name().equals("albedo"))) throw new AssertionError("Normal texture entered storage metadata");
			var merged = IrisVulkanStorageReflection.bindings(3, vertexResources, fragmentResources);
			if (merged.size() != 5) throw new AssertionError("Shared resources must be merged across stages: " + merged);
			var buffer = merged.stream().filter(b -> b.kind() == STORAGE_BUFFER).findFirst().orElseThrow();
			if (buffer.sourceBinding() != 7) throw new AssertionError("Pack SSBO binding changed");
			IrisVulkanStorageReflection.rebind(vs.spirv(), vertexResources, merged);
			IrisVulkanStorageReflection.rebind(fs.spirv(), fragmentResources, merged);
			var reboundVertex = IrisVulkanStorageReflection.reflect(vs.spirv(), samplers);
			var reboundFragment = IrisVulkanStorageReflection.reflect(fs.spirv(), samplers);
			for (var resource : reboundVertex.resources()) assertBinding(resource, merged);
			for (var resource : reboundFragment.resources()) assertBinding(resource, merged);
			if (!fragmentResources.sampledNames().equals(samplers)) throw new AssertionError("3D and storage-backed 2D samplers must bypass Mojang binding");
			System.out.println("Merged graphics storage descriptors: " + merged);
		}
		try {
			IrisVulkanStoragePipeline.prepareSource(fragment, Map.of("voxel_img", "rgba8"));
			throw new AssertionError("Storage format mismatch accepted");
		} catch (IllegalArgumentException expected) { }
		System.out.println("PASS: image-format preparation, SPIRV-Cross storage reflection, cross-stage sharing, SSBO indices and descriptor remapping");
	}

	private static void assertBinding(IrisVulkanStorageReflection.Resource resource,
									  java.util.List<IrisVulkanStorageReflection.Binding> bindings) {
		var expected = bindings.stream().filter(b -> b.name().equals(resource.name()) && b.kind() == resource.kind()).findFirst().orElseThrow();
		if (resource.sourceBinding() != expected.binding() || expected.binding() < 3) throw new AssertionError("Wrong native descriptor binding for " + resource);
	}
}
