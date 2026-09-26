package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

import java.nio.file.Files;
import java.nio.file.Path;

/** Recompiles actual failed prepared stage dumps using Minecraft's shaderc/SPIRV-Cross frontend. */
public final class IrisVulkanDumpCompileTest {
	public static void main(String[] args) throws Exception {
		int shaders = 0;
		try (var compiler = new GlslCompiler(); var files = Files.list(Path.of(args[0]))) {
			for (Path file : files.filter(p -> p.toString().endsWith(".glsl")).sorted().toList()) {
				ShaderType type = file.toString().endsWith(".vert.glsl") ? ShaderType.VERTEX : ShaderType.FRAGMENT;
				String source = IrisVulkanShaderResources.normalizeNativeVersion(Files.readString(file));
				if (type == ShaderType.FRAGMENT) {
					ShaderKey key = null;
					for (ShaderKey candidate : ShaderKey.values()) {
						if (file.getFileName().toString().startsWith("iris_" + candidate.name().toLowerCase(java.util.Locale.ROOT) + "_")
							&& (key == null || candidate.name().length() > key.name().length())) key = candidate;
					}
					source = IrisVulkanShaderResources.patchWorldFragmentCoordinates(source, key);
				}
				try (var module = compiler.createIntermediary(file.getFileName().toString(), source, type)) {
					if (module.spirv() == null || module.spirv().remaining() == 0) throw new AssertionError("Empty SPIR-V: " + file);
				}
				shaders++;
				System.out.println("Compiled: " + file.getFileName());
			}
		}
		if (shaders == 0) throw new AssertionError("No prepared stage dumps found");
		System.out.println("PASS: " + shaders + " actual shader stage dumps compiled and reflected by Minecraft's native Vulkan frontend");
	}
}
