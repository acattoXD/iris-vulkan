package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Tests against Mojang's actual SPIR-V rebinder: unused producer slots must remain in the consumer. */
public final class IrisVulkanInputLinkTest {
	public static void main(String[] args) throws Exception {
		String vertex = """
			#version 450 core
			layout(location=0) in vec3 Position;
			out float iris_FogFragCoord;
			out vec2 texCoord;
			void main() {
			    iris_FogFragCoord = 0.0;
			    texCoord = Position.xy;
			    gl_Position = vec4(Position, 1.0);
			}
			""";
		String fragment = """
			#version 450 core
			in float iris_FogFragCoord;
			in vec2 texCoord;
			in mat3 tbnMatrix;
			in vec3 viewVector;
			layout(location=0) out vec4 color;
			void main() { color = vec4(texCoord, 0.0, 1.0); }
			""";
		String paired = IrisVulkanShaderPruning.removeUnmatchedUnusedInputs(vertex, fragment);
		check(paired.contains("in float iris_FogFragCoord;"), "The unused matched input is a required interface slot");
		check(paired.contains("in vec2 texCoord;"), "Live texture coordinates survive");
		check(!paired.contains("in mat3 tbnMatrix;") && !paired.contains("in vec3 viewVector;"), "Unused absent Sildur inputs are removed");
		check(paired.length() == fragment.length() && paired.lines().count() == fragment.lines().count(), "Offsets and line numbers stay stable");
		try (GlslCompiler compiler = new GlslCompiler();
			 var vs = compiler.createIntermediary("matched-output-vertex", vertex, ShaderType.VERTEX);
			 var unsafe = compiler.createIntermediary("unpaired-input-fragment", IrisVulkanShaderPruning.removeUnusedInputs(fragment), ShaderType.FRAGMENT);
			 var safe = compiler.createIntermediary("paired-input-fragment", paired, ShaderType.FRAGMENT)) {
			List<String> producers = vs.outputs().stream().map((Object value) -> name(value)).toList();
			unsafe.rebind(producers, List.of());
			safe.rebind(producers, List.of());
			int producer = location(vs, vs.outputs(), "texCoord");
			int incorrect = location(unsafe, unsafe.inputs(), "texCoord");
			int corrected = location(safe, safe.inputs(), "texCoord");
			check(incorrect != producer, "The test must reproduce Mojang's missing-input location compaction");
			check(corrected == producer, "Pair-aware pruning must preserve the actual linked location");
			System.out.println("Mojang SPIR-V texCoord locations: vertex=" + producer + ", unpaired fragment=" + incorrect
				+ ", paired fragment=" + corrected);
		}
		String qualified = "flat out vec3 retainedA, retainedB;\nlayout(location=4) out vec3 retainedC;\n"
			+ "out InterfaceBlock { vec3 retainedD; } block;\n#define MAKE_OUTPUT out vec3 retainedE;\n";
		String consumers = "in vec3 retainedA;\nin vec3 retainedB;\nin vec3 retainedC;\nin vec3 retainedD;\nin vec3 retainedE;\n";
		check(consumers.equals(IrisVulkanShaderPruning.removeUnmatchedUnusedInputs(qualified, consumers)), "All qualified/shared/block/macro names are retained conservatively");
		check(fragment.equals(IrisVulkanShaderPruning.removeUnmatchedUnusedInputs("#define MAKE(n) n ## Input\n", fragment)), "Producer token pasting prevents proof of absence");
		check(fragment.equals(IrisVulkanShaderPruning.removeUnmatchedUnusedInputs(null, fragment)), "Unknown producer must not prune inputs");
		int pairs = 0, matched = 0;
		Pattern input = Pattern.compile("(?m)^\\h*in\\h+\\w+\\h+(\\w+)\\h*;");
		for (String argument : args) {
			try (var paths = Files.walk(Path.of(argument))) {
				for (Path frag : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".frag.glsl")).toList()) {
					Path vert = frag.resolveSibling(frag.getFileName().toString().replace(".frag.glsl", ".vert.glsl"));
					if (!Files.isRegularFile(vert)) continue;
					String producer = Files.readString(vert), consumer = Files.readString(frag);
					String output = IrisVulkanShaderPruning.removeUnmatchedUnusedInputs(producer, consumer);
					var declarations = input.matcher(consumer);
					while (declarations.find()) {
						if (Pattern.compile("\\b" + Pattern.quote(declarations.group(1)) + "\\b").matcher(producer).find()) {
							check(output.contains(declarations.group()), "Removed matched producer input from " + frag + ": " + declarations.group());
							++matched;
						}
					}
					++pairs;
				}
			}
		}
		System.out.println("PASS: pair-aware input pruning, real SPIR-V interface preservation, Sildur absent declarations, and "
			+ matched + " matched inputs across " + pairs + " actual shader pairs");
	}

	private static String name(Object resource) {
		try { return invoke(resource, "name").toString(); }
		catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}
	private static Object invoke(Object object, String method) throws ReflectiveOperationException {
		Method accessor = object.getClass().getDeclaredMethod(method);
		accessor.setAccessible(true);
		return accessor.invoke(object);
	}
	private static int location(IntermediaryShaderModule module, List<?> resources, String name) throws ReflectiveOperationException {
		Object resource = resources.stream().filter(value -> name(value).equals(name)).findFirst().orElseThrow();
		int offset = ((Number) invoke(resource, "locationOffset")).intValue();
		return module.spirv().asIntBuffer().get(offset);
	}
	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
