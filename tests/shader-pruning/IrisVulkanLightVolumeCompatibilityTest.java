package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Verifies the real Ultra hand accumulator has a zero store before its first SPIR-V load. */
public final class IrisVulkanLightVolumeCompatibilityTest {
	public static void main(String[] args) throws Exception {
		String original = Files.readString(Path.of(args[0]));
		String fixed = patch(original);
		check(!original.equals(fixed), "Known Ultra hand accumulator is patched");
		check(patch(fixed).equals(fixed), "Compatibility correction is idempotent");
		check(original.lines().count() == fixed.lines().count(), "Shader diagnostic line numbers are preserved");
		String initialized = original.replace("vec4 lightVolume;", "vec4 lightVolume = vec4(0.75);");
		check(patch(initialized).equals(initialized), "Explicit nonzero initializers remain unchanged");
		String otherFunction = original.replace("GetComplexLightVolume", "OtherLightVolume");
		check(patch(otherFunction).equals(otherFunction), "Unrelated shader functions remain unchanged");
		String commented = "/* vec4 GetComplexLightVolume(vec3 pos, sampler3D ff_sampler) { vec4 lightVolume; } */";
		check(patch(commented).equals(commented), "Commented examples are not patched");
		check(patch("vec4 GetLightVolume(vec3 pos) { vec4 lightVolume; return lightVolume; }")
			.equals("vec4 GetLightVolume(vec3 pos) { vec4 lightVolume; return lightVolume; }"), "Other local variables are not blanket-initialized");
		try (GlslCompiler compiler = new GlslCompiler()) {
			State before = inspect(compiler, "ultra-hand-before.frag", original);
			State after = inspect(compiler, "ultra-hand-after.frag", fixed);
			check(before.readBeforeStore, "Original compiled pack reads the accumulator before writing it");
			check(!after.readBeforeStore && after.firstStoreIsZero, "Patched SPIR-V stores all four zero components before reading the accumulator");
			System.out.println("PASS: actual Ultra hand shader compiles; original SPIR-V loads an uninitialized accumulator; patched SPIR-V stores vec4(0) first; initialized/unrelated code and diagnostic line numbers unchanged");
		}
	}

	private static String patch(String source) { return IrisVulkanShaderCompatibility.initializeLightVolumeAccumulator(source); }
	private static State inspect(GlslCompiler compiler, String name, String source) throws Exception {
		try (var module = compiler.createIntermediary(name, source, ShaderType.FRAGMENT)) {
			IntBuffer words = module.spirv().duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
			Map<Integer, String> names = new HashMap<>();
			Map<Integer, int[]> constants = new HashMap<>();
			for (int cursor = 5; cursor < words.limit();) {
				int count = words.get(cursor) >>> 16, op = words.get(cursor) & 65535;
				if (op == 5) names.put(words.get(cursor + 1), string(words, cursor + 2, cursor + count));
				if (op == 43 || op == 44 || op == 46) {
					int[] value = new int[count - 2]; value[0] = op;
					for (int word = 3; word < count; word++) value[word - 2] = words.get(cursor + word);
					constants.put(words.get(cursor + 2), value);
				}
				cursor += count;
			}
			boolean wanted = false, stored = false, readBeforeStore = false, firstStoreIsZero = false;
			int variable = -1;
			for (int cursor = 5; cursor < words.limit();) {
				int count = words.get(cursor) >>> 16, op = words.get(cursor) & 65535;
				if (op == 54) wanted = names.getOrDefault(words.get(cursor + 2), "").startsWith("GetComplexLightVolume(");
				if (wanted && op == 59 && "lightVolume".equals(names.get(words.get(cursor + 2)))) {
					variable = words.get(cursor + 2);
					if (count == 5) { stored = true; firstStoreIsZero = zero(words.get(cursor + 4), constants); }
				}
				if (wanted && op == 61 && words.get(cursor + 3) == variable && !stored) readBeforeStore = true;
				if (wanted && op == 62 && words.get(cursor + 1) == variable && !stored) {
					stored = true;
					firstStoreIsZero = zero(words.get(cursor + 2), constants);
				}
				if (op == 56) wanted = false;
				cursor += count;
			}
			check(variable >= 0, "Compiled function exposes the known accumulator");
			return new State(readBeforeStore, firstStoreIsZero);
		}
	}

	private static boolean zero(int id, Map<Integer, int[]> constants) {
		int[] value = constants.get(id);
		if (value == null) return false;
		if (value[0] == 46) return true;
		for (int i = 1; i < value.length; i++) if (value[0] == 44 ? !zero(value[i], constants) : value[i] != 0) return false;
		return true;
	}
	private static String string(IntBuffer words, int start, int end) {
		StringBuilder text = new StringBuilder();
		for (int i = start; i < end; i++) for (int b = 0; b < 4; b++) {
			int character = words.get(i) >>> (b * 8) & 255;
			if (character == 0) return text.toString();
			text.append((char) character);
		}
		return text.toString();
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private record State(boolean readBeforeStore, boolean firstStoreIsZero) { }
}
