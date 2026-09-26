// SPDX-License-Identifier: LGPL-3.0-only
package net.irisshaders.iris.vulkan;

import java.util.Set;

/** CPU-only regression checks; this test does not create a Minecraft or Vulkan device. */
public final class IrisVulkanTargetIndicesTest {
	public static void main(String[] arguments) {
		check(IrisVulkanTargetIndices.LOGICAL_TARGET_COUNT == 32, "Iris logical color range");
		check(IrisVulkanTargetIndices.MAX_COLOR_ATTACHMENTS == 8, "Independent MRT attachment limit");
		String[] aliases = {"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4"};
		for (int index = 0; index < aliases.length; index++) {
			check(IrisVulkanTargetIndices.colorSamplerTarget(aliases[index]) == index, aliases[index]);
		}
		for (int index : new int[] {0, 7, 8, 12, 18, 19, 31}) {
			check(IrisVulkanTargetIndices.colorSamplerTarget("colortex" + index) == index, "High logical index " + index);
		}
		for (String invalid : new String[] {"colortex32", "colortex-1", "colortex+1", "colortex", "colortex999999999999", "colortex1Format", "mycolortex1"}) {
			check(IrisVulkanTargetIndices.colorSamplerTarget(invalid) == -1, "Reject " + invalid);
		}

		IrisVulkanTargetIndices.validateDrawBuffers("high sparse targets", new int[] {18, 19});
		IrisVulkanTargetIndices.validateDrawBuffers("eight attachments", new int[] {0, 3, 7, 8, 12, 18, 19, 31});
		fails(() -> IrisVulkanTargetIndices.validateDrawBuffers("nine attachments", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8}));
		fails(() -> IrisVulkanTargetIndices.validateDrawBuffers("duplicate", new int[] {18, 18}));
		fails(() -> IrisVulkanTargetIndices.validateDrawBuffers("out of range", new int[] {32}));
		fails(() -> IrisVulkanTargetIndices.validateDrawBuffers("negative", new int[] {-1}));

		IrisVulkanTargetIndices sparse = new IrisVulkanTargetIndices();
		sparse.requireDeclaredTextures("""
			#version 450 core
			#line 1 "unused-colortex27.glsl"
			// uniform sampler2D colortex22;
			/* uniform sampler2D colortex23; */
			uniform sampler2D colortex0, colortex18;
			const int colortex12Format = 1;
			vec4 my_colortex31 = vec4(0.0);
			""");
		sparse.requireDrawBuffers("composite", new int[] {19});
		check(sparse.indices().equals(Set.of(0, 18, 19)), "Sparse plan must not allocate intervening or comment-only targets");
		Set<Integer> frozen = sparse.indices();
		sparse.require(31, "explicit mipmap/flip");
		check(frozen.equals(Set.of(0, 18, 19)), "Published allocation plan is a stable snapshot");
		check(sparse.indices().equals(Set.of(0, 18, 19, 31)), "Explicit metadata target is included");
		fails(() -> sparse.require(32, "invalid flip"));
		fails(() -> sparse.requireDeclaredTextures("uniform sampler2D colortex32;"));

		IrisVulkanTargetIndices images = new IrisVulkanTargetIndices();
		images.requireDeclaredTextures("""
			layout(rgba16f) readonly uniform image2D colorimg12;
			uniform highp usampler2D colortex8;
			uniform sampler2D gaux3;
			uniform image3D floodfill_img;
			layout(std430) buffer OptionalData { vec4 value; };
			""");
		check(images.indices().equals(Set.of(6, 8, 12)), "Color-image declarations map to logical targets; custom images/SSBOs do not");
		for (int index = 0; index < 32; index++) {
			String name = "colortex" + index;
			retains("uniform sampler2D " + name + ";\nvoid main(){}", Set.of(), "Unused target " + index);
			retains("uniform sampler2D " + name + ";\nvec4 helper(){return texture(" + name + ",vec2(0));}",
				Set.of(index), "Reference in helper is conservatively live " + index);
			retains("uniform sampler2D " + name + "[2];", Set.of(index), "Sampler array retained " + index);
			retains("layout(rgba16f) writeonly uniform image2D colorimg" + index + ";", Set.of(index), "Writable image retained " + index);
		}
		for (int index = 0; index < aliases.length; index++) {
			retains("uniform sampler2D " + aliases[index] + ";", Set.of(), "Unused legacy alias");
			retains("uniform sampler2D " + aliases[index] + ";void main(){texture(" + aliases[index] + ",vec2(0));}",
				Set.of(index), "Used legacy alias");
		}
		retains("""
			#version 450 core
			#line 8 "unused-samplers.glsl"
			uniform highp usampler2D colortex8;
			// colortex8 would be used by another preset.
			/* texture(colortex8, uv); */
			const bool colortex8Clear = false;
			const int colortex8Format = 1;
			vec4 other_colortex8;
			""", Set.of(), "Metadata, comments, identifiers and line paths cannot read a target");
		retains("uniform sampler2D colortex8, colortex9;", Set.of(8,9), "Multiple declarations remain conservative");
		retains("uniform sampler2D colortex8 = anotherSampler;", Set.of(8), "Initializer remains conservative");
		retains("uniform sampler2D colortex8; uniform image2D colorimg8;", Set.of(8), "Image keeps an unused sampler's target");
		retains("layout(binding=3) uniform mediump sampler2D colortex8;", Set.of(), "Simple binding and precision qualifiers");
		retains("uniform sampler2D colortex8; uniform sampler2D colortex9; void main(){texture(colortex9,vec2(0));}",
			Set.of(9), "Only the referenced simple declaration remains");
		retains("uniform sampler2D colortex8;\n#define PICK(x) colortex##x\nvoid main(){texture(PICK(8),vec2(0));}",
			Set.of(8), "Unexpanded token pasting cannot drop a target");
		retains("uniform sampler2D colortex8;\n#include \"other.glsl\"", Set.of(8), "Unresolved include keeps declarations");
		retains("uniform sampler2D colortex8;\n#if OPTION\n#endif", Set.of(8), "Unresolved condition keeps declarations");
		retains("uniform sampler2D colortex8;\n#line 1 \"colortex8.glsl\"", Set.of(8), "Retain declarations kept by the native resource compiler");
		retains("uniform sampler2D colortex8;\nuniform sampler2D colortex8;", Set.of(8), "Duplicate declarations remain conservative");
		IrisVulkanTargetIndices explicit = new IrisVulkanTargetIndices();
		explicit.require(8, "mipmap/flip/storage requirement");
		explicit.requireReferencedTextures("uniform sampler2D colortex8;");
		check(explicit.indices().equals(Set.of(8)), "Pruning a declaration must not remove another dependency");
		fails(() -> new IrisVulkanTargetIndices().requireReferencedTextures("uniform sampler2D colortex32;"));
		System.out.println("PASS: logical color targets, sparse declaration plans, canonical aliases, and independent MRT limits");
	}

	private static void retains(String source, Set<Integer> expected, String description) {
		IrisVulkanTargetIndices indices = new IrisVulkanTargetIndices();
		indices.requireReferencedTextures(source);
		check(indices.indices().equals(expected), description + ": " + indices.indices());
	}

	private static void check(boolean condition, String description) {
		if (!condition) {
			throw new AssertionError(description);
		}
	}

	private static void fails(Runnable operation) {
		try {
			operation.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError("Expected invalid target configuration to be rejected");
	}
}
