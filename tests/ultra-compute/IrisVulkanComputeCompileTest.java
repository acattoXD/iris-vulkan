package net.irisshaders.iris.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Compiles active pack compute sources without starting Minecraft or creating a graphics device. */
public final class IrisVulkanComputeCompileTest {
	public static void main(String[] args) throws Exception {
		Map<String, String> formats = Map.of("voxel_img", "r16ui", "floodfill_img", "rgba16f",
			"floodfill_img_copy", "rgba16f", "wsr_img", "r16ui", "wsr_lod_img", "r8ui",
			"puddle_img", "r8ui", "playerAtlas_img", "rgba8");
		int count = 0;
		for (String argument : args) {
			Path file = Path.of(argument);
			String name = file.getParent().getFileName() + "_" + file.getFileName();
			var prepared = IrisVulkanComputeCompiler.prepareSource(name, Files.readString(file), formats);
			Path output = Path.of("build/ultra-compute", name + ".prepared.glsl");
			Files.createDirectories(output.getParent());
			Files.writeString(output, prepared.source());
			try (var spirv = IrisVulkanComputeCompiler.compileSpirv(prepared)) {
				if (spirv.localSize().x != 8 || spirv.localSize().y != 8 || spirv.localSize().z != 8) {
					throw new AssertionError("Unexpected flood-fill local size " + spirv.localSize());
				}
				var words = spirv.bytes().asIntBuffer();
				boolean hasImageWrite = false;
				boolean hasImageFetch = false;
				for (int offset = 5; offset < words.limit();) {
					int instruction = words.get(offset);
					int opcode = instruction & 0xffff;
					hasImageWrite |= opcode == 99;
					hasImageFetch |= opcode == 95;
					offset += instruction >>> 16;
				}
				if (!hasImageWrite || !hasImageFetch) throw new AssertionError("Flood-fill stores and voxel/light fetches must survive compilation");
				byte[] binary = new byte[spirv.bytes().remaining()];
				spirv.bytes().duplicate().get(binary);
				Files.write(Path.of("build/ultra-compute", name + ".spv"), binary);
				System.out.println("Compiled " + name + ": " + binary.length + " bytes, local " + spirv.localSize());
				System.out.println("Descriptors: " + prepared.descriptors());
				System.out.println("Live uniforms: " + prepared.uniforms());
			}
			if (prepared.descriptors().stream().filter(d -> d.kind() == IrisVulkanComputeCompiler.Kind.STORAGE_IMAGE).count() != 2) {
				throw new AssertionError("Both flood-fill image stores must be retained");
			}
			++count;
		}
		if (count != 3) throw new AssertionError("Expected all three dimension sources, got " + count);
		System.out.println("PASS: " + count + " actual ULTRA flood-fill programs compiled to Vulkan SPIR-V");
	}
}
