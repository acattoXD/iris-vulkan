package net.irisshaders.iris.vulkan;

import org.joml.Vector2f;
import org.joml.Vector3i;

import java.util.Map;

/** Binding and dispatch contracts that could compile successfully while addressing the wrong GPU data. */
public final class IrisVulkanComputeContractTest {
	public static void main(String[] args) {
		var local = new Vector3i(8, 8, 8);
		assertGroups(new Vector3i(241, 136, 1), IrisVulkanComputeExecutor.calculateWorkGroups(null, null, local, 1921, 1081));
		assertGroups(new Vector3i(121, 68, 1), IrisVulkanComputeExecutor.calculateWorkGroups(null, new Vector2f(.5f), local, 1921, 1081));
		assertGroups(new Vector3i(64, 32, 64), IrisVulkanComputeExecutor.calculateWorkGroups(new Vector3i(64, 32, 64),
			new Vector2f(.5f), local, 1921, 1081));
		String source = """
			#version 430 compatibility
			layout(local_size_x = 4 * 2, local_size_y = 1, local_size_z = 1) in;
			layout(std430, binding = 7) buffer ActualPackBuffer { uint values[]; };
			uniform vec3 cameraPosition;
			uniform float unused;
			layout(r32ui) coherent uniform uimage3D volume;
			void main() {
			    uint id = gl_GlobalInvocationID.x;
			    values[id] = uint(cameraPosition.x) + imageLoad(volume, ivec3(id, 0, 0)).x;
			    imageStore(volume, ivec3(id, 0, 0), uvec4(values[id]));
			}
			""";
		var prepared = IrisVulkanComputeCompiler.prepareSource("binding-contract", source, Map.of("volume", "r32ui"));
		var storage = prepared.descriptors().stream().filter(d -> d.kind() == IrisVulkanComputeCompiler.Kind.STORAGE_BUFFER).findFirst().orElseThrow();
		if (storage.sourceBinding() != 7 || storage.binding() == 7) throw new AssertionError("Pack binding must survive Vulkan remapping");
		if (prepared.uniforms().size() != 1 || !prepared.uniforms().getFirst().name().equals("cameraPosition")) {
			throw new AssertionError("Only the live uniform should enter the std140 snapshot");
		}
		try (var binary = IrisVulkanComputeCompiler.compileSpirv(prepared)) {
			assertGroups(new Vector3i(8, 1, 1), binary.localSize());
		}
		try {
			IrisVulkanComputeCompiler.prepareSource("missing-format", source.replace("layout(r32ui)", ""), Map.of());
			throw new AssertionError("Images without a known format must be rejected");
		} catch (UnsupportedOperationException expected) { }
		System.out.println("PASS: pack SSBO indices, storage image qualifiers, live UBOs, SPIR-V local size and dispatch rounding");
	}

	private static void assertGroups(Vector3i expected, Vector3i actual) {
		if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
	}
}
