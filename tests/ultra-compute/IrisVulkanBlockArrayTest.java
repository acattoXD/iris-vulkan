package net.irisshaders.iris.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Regression for the live Ultra failure: a scalar UBO followed by function-body array accesses. */
public final class IrisVulkanBlockArrayTest {
	public static void main(String[] args) throws Exception {
		System.setProperty("iris.vulkan.worldDevelopment", "true");
		System.setProperty("iris.vulkan.storageDevelopment", "true");
		System.setProperty("iris.vulkan.screenPassMode", "all");
		assertArrays("""
			layout(std140) uniform IrisUniforms { mat4 projection; float frameTime; };
			float scratch[4];
			void main() {
			    if (frameTime > 0.0) { scratch[0] = 1.0; }
			    scratch[1] = 2.0;
			}
			""", Set.of());
		assertArrays("layout(std140) uniform Camera { mat4 projection; } camera;\n"
			+ "void main() { if (true) {} cameraPositions[0] = vec3(0.0); }", Set.of());
		assertArrays("layout(std430, binding=0) buffer BlockData { uvec4 data[]; } blockDataSSBO;", Set.of());
		assertArrays("layout(std140) uniform Camera { mat4 projection; } cameras[2];", Set.of("Camera"));
		assertArrays("layout(std430, binding=0) readonly buffer BlockData { uvec4 data[]; } blocks[4];", Set.of("BlockData"));
		assertArrays("layout(std140) uniform Matrices { mat4 values[3]; } matrices[2][3];", Set.of("Matrices"));
		assertArrays("uniform Nested { struct Data { vec4 color; } values[2]; } nested[2];", Set.of("Nested"));
		assertArrays("uniform Nested { struct Data { vec4 color; } values[2]; } nested;", Set.of());
		assertArrays("uniform Unsized { vec4 color; } unsized[];", Set.of("Unsized"));
		assertArrays("uniform First { vec4 data; };\nuniform Second { vec4 data; } instances[3];", Set.of("Second"));
		assertArrays("uniform Camera { vec4 color; } /* } pretend[99]; */ cameras /* array */ [2];", Set.of("Camera"));
		int realSources = 0;
		for (String argument : args) {
			try (var paths = Files.walk(Path.of(argument))) {
				for (Path path : paths.filter(Files::isRegularFile).filter(path -> path.toString().matches(".*\\.[vf]sh")).toList()) {
					assertArrays("layout(std140) uniform IrisUniforms { mat4 projection; };\n" + Files.readString(path), Set.of());
					++realSources;
				}
			}
		}
		System.out.println("PASS: scalar UBO/SSBOs, function-body accesses, member arrays, nested braces, true descriptor arrays, comments, and "
			+ realSources + " exact Ultra graphics sources");
	}

	private static void assertArrays(String source, Set<String> expected) {
		var resources = IrisVulkanShaderResources.ResourceSet.collect(source, "", List.of(), new LinkedHashSet<>());
		var actual = resources.unsupported().stream().filter(resource -> resource.type().equals("block array"))
			.map(IrisVulkanShaderResources.UnsupportedResource::name).collect(java.util.stream.Collectors.toSet());
		if (!actual.equals(expected)) throw new AssertionError("Expected block arrays " + expected + ", got " + actual + " in:\n" + source);
	}
}
