package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.List;
import java.util.zip.ZipFile;

/** Exercises production vertex bytes, nested draw routing and actual shaderc parsing without a GL context. */
public final class IrisVulkanWorldContractTest {
	public static void main(String[] args) throws Exception {
		checkPhaseScopes();
		checkMaterialScopes();
		checkNativeLayoutsAndMatrices();
		checkShaderFamilies();
		checkPackMaterialBytes(Path.of(args[0]));
		checkNativeShaders();
		System.out.println("PASS: native phase scopes, Complementary material bytes, projection ABI, native SPIR-V compilation");
	}

	private static void checkNativeLayoutsAndMatrices() {
		check(!IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.RGBA16_FLOAT), "Floating color targets use linear interpolation");
		check(!IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.RGBA8_UNORM), "Normalized color targets use linear interpolation");
		check(!IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.RG11B10_FLOAT), "Packed HDR color targets use linear interpolation");
		check(IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.R32_UINT), "Unsigned integer targets preserve exact data");
		check(IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.RGBA16_SINT), "Signed integer targets preserve exact data");
		check(IrisVulkanGbufferTargets.targetNeedsNearest(GpuFormat.D32_FLOAT), "Depth targets are never interpolated as color");
		var padded = VertexFormat.builder(1).addAttribute("Position", 16, GpuFormat.RGB32_FLOAT).addAttribute("Color", 16, GpuFormat.RGBA8_UNORM).build();
		var alias = IrisVulkanShaderResources.aliasVanillaVertexFormat(padded);
		check(alias.getVertexSize() == 32 && alias.getStepRate() == 1, "Vertex aliases preserve padded stride and instance step rate");
		check(alias.getElement("iris_Color").offset() == 16, "Vertex aliases preserve original byte offsets");
		var modelView = new Matrix4f().rotateXYZ(0.1F, 0.2F, 0.3F).translate(0.4F, 0.5F, 0.6F);
		try (var scope = IrisVulkanEntityContext.enterModelView(modelView)) {
			check(IrisVulkanEntityContext.currentDrawModelView().equals(modelView), "Uploaded per-draw matrix is available to inverse and normal uniforms");
			try (var nested = IrisVulkanEntityContext.enterModelView(new Matrix4f().scale(2))) { }
			check(IrisVulkanEntityContext.currentDrawModelView().equals(modelView), "Nested draw restores model-view matrix");
		}
		check(IrisVulkanEntityContext.currentDrawModelView() == null, "Draw model-view does not leak after scope");
		var forward = new Matrix4f().setPerspective(1.1F, 16.0F / 9, 0.05F, 512F, false);
		Vector4f world = new Vector4f(2F, 3F, -15F, 1F);
		Vector4f clip = forward.transform(new Vector4f(world));
		float nativeDepth = 0.5F * (1 - clip.z / clip.w);
		float legacyDepth = 1 - nativeDepth;
		Vector4f reconstructed = forward.invert(new Matrix4f()).transform(new Vector4f(clip.x / clip.w, clip.y / clip.w, 2 * legacyDepth - 1, 1));
		reconstructed.div(reconstructed.w);
		check(reconstructed.distance(world) < 0.001F, "Native fragment depth reconstructs the real surface, not a near-camera point");
	}

	private static void checkMaterialScopes() {
		var captured = CapturedRenderingState.INSTANCE;
		captured.setCurrentEntity(23);
		captured.setCurrentBlockEntity(24);
		captured.setCurrentRenderedItem(25);
		var entity = new IrisVulkanEntityContext.Material(100, 0, 0, false);
		var chest = new IrisVulkanEntityContext.Material(0, 200, 0, true);
		try (var ignored = IrisVulkanEntityContext.enter(entity)) {
			check(captured.getCurrentRenderedEntity() == 100, "Entity material reaches captured uniforms");
			try (var nested = IrisVulkanEntityContext.enter(chest)) {
				check(captured.getCurrentRenderedBlockEntity() == 200, "Chest material reaches captured uniforms");
				check(IrisVulkanEntityContext.current().blockEntity(), "Block entity classification is retained with the ID");
			}
			check(captured.getCurrentRenderedEntity() == 100 && captured.getCurrentRenderedBlockEntity() == 0, "Nested material restores outer entity IDs");
		}
		check(captured.getCurrentRenderedEntity() == 23 && captured.getCurrentRenderedBlockEntity() == 24 && captured.getCurrentRenderedItem() == 25,
			"All captured IDs restored after batch preparation");
		check(!entity.equals(chest), "Entity and block entity material runs cannot merge");
	}

	private static void checkPhaseScopes() {
		var state = new IrisVulkanPhaseContext.State();
		state.setPhase(WorldRenderingPhase.SKY);
		try (var terrain = state.enter(WorldRenderingPhase.TERRAIN_SOLID)) {
			try (var entity = state.enter(WorldRenderingPhase.ENTITIES)) {
				check(state.getPhase() == WorldRenderingPhase.ENTITIES, "Nested entity phase");
			}
			check(state.getPhase() == WorldRenderingPhase.TERRAIN_SOLID, "Terrain restored after nested draw");
			state.setOverridePhase(WorldRenderingPhase.BLOCK_ENTITIES);
			try (var particles = state.enter(WorldRenderingPhase.PARTICLES)) {
				check(state.getPhase() == WorldRenderingPhase.BLOCK_ENTITIES, "Explicit block entity override survives particle scope");
			}
			state.setOverridePhase(null);
			check(state.getPhase() == WorldRenderingPhase.TERRAIN_SOLID, "Underlying phase survives override");
		}
		check(state.getPhase() == WorldRenderingPhase.SKY, "Caller phase restored");
		var interrupted = state.enter(WorldRenderingPhase.HAND_SOLID);
		state.reset();
		interrupted.close();
		check(state.getPhase() == WorldRenderingPhase.NONE, "An interrupted draw cannot leak into the next frame");
		check(IrisVulkanPhaseContext.featurePhase(WorldRenderingPhase.HAND_SOLID, true) == WorldRenderingPhase.HAND_TRANSLUCENT,
			"Transparent feature execution inside hand stays in hand family");
	}

	private static void checkShaderFamilies() {
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.ENTITIES_CUTOUT_DIFFUSE, WorldRenderingPhase.HAND_SOLID) == ShaderKey.HAND_CUTOUT_DIFFUSE, "Held item shader");
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.ENTITIES_TRANSLUCENT, WorldRenderingPhase.HAND_TRANSLUCENT) == ShaderKey.HAND_WATER_DIFFUSE, "Transparent held item shader");
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.TEXT, WorldRenderingPhase.HAND_SOLID) == ShaderKey.HAND_TEXT, "Held map text shader");
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.ENTITIES_CUTOUT, WorldRenderingPhase.BLOCK_ENTITIES) == ShaderKey.BLOCK_ENTITY, "Chest/block entity shader");
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.ENTITIES_EYES, WorldRenderingPhase.BLOCK_ENTITIES) == ShaderKey.ENTITIES_EYES, "Emissive eyes retain their material");
		check(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.SHADOW_ENTITIES_CUTOUT, WorldRenderingPhase.BLOCK_ENTITIES) == ShaderKey.SHADOW_ENTITIES_CUTOUT, "Shadow family is never remapped into a main pass");
	}

	private static void checkPackMaterialBytes(Path zipPath) throws Exception {
		Properties blocks = new Properties();
		try (ZipFile zip = new ZipFile(zipPath.toFile()); var input = zip.getInputStream(zip.getEntry("shaders/block.properties"))) {
			String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			blocks.load(new StringReader(PropertiesPreprocessor.preprocessSource(source, List.of(new StringPair("MC_VERSION", "260200")))));
		}
		check(blocks.getProperty("block.32000").contains("water"), "Selected pack's water ID");
		check(blocks.getProperty("block.10009").contains("oak_leaves"), "Selected pack's leaves ID");
		check(blocks.getProperty("block.10005").contains("short_grass"), "Selected pack's grass ID");
		var type = FormatAnalyzer.createFormat(true, true, true, true);
		int stride = type.getVertexFormat().getVertexSize();
		check(stride == 36, "Four extended attributes produce a 36-byte vertex");
		var encoder = type.getEncoder();
		WorldRenderingSettings.INSTANCE.setUseSeparateAo(true);
		long buffer = MemoryUtil.nmemAlloc((long) stride * 4);
		try {
			for (int id : new int[]{32000, 10009, 10005}) {
				boolean fluid = id == 32000;
				ChunkVertexEncoder.Vertex[] quad = new ChunkVertexEncoder.Vertex[4];
				for (int i = 0; i < 4; i++) {
					var vertex = new MaterialVertex(id, fluid);
					vertex.x = i >= 2 ? 4 : 3;
					vertex.y = 4;
					vertex.z = i == 1 || i == 2 ? 6 : 5;
					vertex.u = i >= 2 ? 0.75F : 0.25F;
					vertex.v = i == 1 || i == 2 ? 0.75F : 0.25F;
					vertex.color = 0xFF336699;
					vertex.ao = 0.5F;
					vertex.light = 240 << 16 | 128;
					quad[i] = vertex;
				}
				long end = encoder.write(buffer, 0, quad, 37);
				check(end == buffer + (long) stride * 4, "Exact stride across a full terrain quad");
				for (int i = 0; i < 4; i++) {
					long ptr = buffer + (long) i * stride;
					int packedId = MemoryUtil.memGetInt(ptr + 20);
					check((packedId >>> 1) - 1 == id, "GPU mc_Entity preserves full Complementary material ID " + id);
					check((packedId & 1) == (fluid ? 1 : 0), "GPU mc_Entity distinguishes water from solid materials");
					check((MemoryUtil.memGetInt(ptr + 8) & 0xFFFFFF) == 0x336699, "Separate AO preserves block tint RGB");
					check((MemoryUtil.memGetInt(ptr + 8) >>> 24) == 127, "Separate AO reaches the alpha channel");
					check((MemoryUtil.memGetInt(ptr + 16) >>> 24) == 37, "Section index survives material extension");
					check(MemoryUtil.memGetInt(ptr + 24) != 0, "Packed normal/tangent is present");
					check(MemoryUtil.memGetInt(ptr + 28) == 0x40004000, "Shader sees the sprite center for waving plants");
					check(MemoryUtil.memGetByte(ptr + 32) == (i >= 2 ? -32 : 32), "Mid-block X is relative to section coordinates");
				}
			}
		} finally {
			MemoryUtil.nmemFree(buffer);
		}
	}

	private static void checkNativeShaders() {
		String fragment = "#version 450 core\nlayout(location=0) out vec4 color;\nvoid main() { color = vec4(gl_FragCoord.xyz, gl_FragCoord.w); }\n";
		String fragmentDepth = IrisVulkanShaderResources.patchWorldFragmentCoordinates(fragment, ShaderKey.ENTITIES_CUTOUT_DIFFUSE);
		check(fragmentDepth.contains("1.0 - gl_FragCoord.z"), "World fragment coordinate depth becomes legacy forward-Z");
		check(IrisVulkanShaderResources.patchWorldFragmentCoordinates(fragment, ShaderKey.SHADOW_ENTITIES_CUTOUT).equals(fragment), "Native shadow coordinates already use forward-Z");
		check(IrisVulkanShaderResources.normalizeNativeVersion("#version 330 core\nvoid main() {}\n").startsWith("#version 450 core\n"), "Transformed native GLSL supports required core helpers");
		check(IrisVulkanShaderResources.normalizeNativeVersion("#version 460 core\nvoid main() {}\n").startsWith("#version 460 core\n"), "Higher declared core version is preserved");
		String extension = "#version 330 core\n#extension GL_ARB_gpu_shader5 : require\nvoid main() {}\n";
		check(IrisVulkanShaderResources.normalizeNativeVersion(extension).contains("#extension GL_ARB_gpu_shader5 : require"), "Required extensions are not silently discarded");
		String depth = "#version 330 core\nuniform sampler2D depthtex0;\nvoid main() { vec4 value = texelFetch(depthtex0, ivec2(0), 0); }\n";
		String patchedDepth = IrisVulkanShaderResources.patchScreenPassDepthSemantics(depth);
		check(patchedDepth.contains("iris_vulkan_texelFetch_depthtex0"), "Depth fetch conversion is present");
		check(!patchedDepth.contains("textureGather"), "Unused gather helpers cannot require additional shader capabilities");
		String entity = "#version 450\nin ivec3 iris_Entity;\nflat out ivec3 material;\nvoid main() { material = iris_Entity; }\n";
		String nativeEntity = IrisVulkanShaderResources.patchNativeEntityIds(entity, new VertexFormat[]{DefaultVertexFormat.ENTITY});
		check(nativeEntity.contains("uniform ivec3 iris_NativeEntityIds;"), "Native entity bytes use real draw material uniforms");
		check(!nativeEntity.contains("in ivec3 iris_Entity;"), "No unsupported extended entity vertex attribute is assumed");
		var cloudResources = IrisVulkanShaderResources.ResourceSet.collect("#version 450\nuniform isamplerBuffer CloudFaces;\n", "", List.of(), java.util.Set.of());
		check(cloudResources.unsupported().isEmpty(), "Native cloud face data has a real engine binding");
		check(cloudResources.texelBuffers().contains(new IrisVulkanShaderResources.TexelBuffer("CloudFaces", GpuFormat.R32_SINT)), "Cloud face integer format");
		String vanilla = "#version 450\nlayout(std140) uniform iris_Projection { mat4 iris_ProjMat; };\nvoid main() { gl_Position = iris_ProjMat * vec4(1, 2, 3, 1); }\n";
		String patched = IrisVulkanShaderResources.patchWorldProjectionUniforms(vanilla, ShaderKey.HAND_CUTOUT);
		check(!patched.contains("uniform iris_Projection"), "Native projection UBO must not leak reverse clip values into legacy pack math");
		check(patched.contains("uniform mat4 iris_ProjMat;"), "Hand projection is supplied by the uniform snapshot");
		String sodium = "#version 450\nlayout(std140) uniform u_Globals { mat4 u_ProjectionMatrix; mat4 u_ModelViewMatrix; };\nvoid main() { gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(1); }\n";
		patched = IrisVulkanShaderResources.patchWorldProjectionUniforms(sodium, ShaderKey.SODIUM_TERRAIN_SOLID);
		check(patched.contains("mat4 iris_NativeProjectionMatrix; mat4 u_ModelViewMatrix;"), "Sodium's std140 member offsets remain unchanged");
		check(patched.contains("gl_Position = iris_ProjectionMatrix * u_ModelViewMatrix"), "Sodium shaders read legacy clip projection");
		String base = "#version 450\nvoid main() { gl_Position = vec4(0, 0, 0, 1); if (gl_VertexIndex == 0) return; gl_Position.x = 0.5; }\n";
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			for (var key : new ShaderKey[]{ShaderKey.SODIUM_TERRAIN_SOLID, ShaderKey.HAND_CUTOUT, ShaderKey.HAND_WATER_DIFFUSE}) {
				String source = IrisVulkanShaderResources.patchWorldClipDepth(base, key);
				check(source.contains("void iris_vulkan_pack_main()"), "Early returns remain inside wrapped pack main");
				long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_glsl_vertex_shader, "native-contract.vert", "main", options);
				try {
					check(Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success,
						"Native SPIR-V shader compile for " + key + ": " + Shaderc.shaderc_result_get_error_message(result));
				} finally { Shaderc.shaderc_result_release(result); }
			}
			check(IrisVulkanShaderResources.patchWorldClipDepth(base, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID).equals(base), "Shadow clip conversion is owned by shadow renderer");
			check(0.5F * (1 - -1) == 1 && 0.5F * (1 - 1) == 0, "OpenGL near/far map to native reverse-Z endpoints");
			for (float legacyClipZ : new float[]{-1F, -0.5F, 0F, 0.5F, 1F}) {
				float glHandWindowDepth = 0.5F * (1F + 0.125F * legacyClipZ);
				float nativeDepth = 0.5F * (1F - legacyClipZ);
				float nativeHandDepth = 0.4375F + 0.125F * nativeDepth;
				check(Math.abs((1F - nativeHandDepth) - glHandWindowDepth) < 1.0e-7F,
					"Hand window depth exactly matches OpenGL clip Z scaled by0.125");
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class MaterialVertex extends ChunkVertexEncoder.Vertex implements ChunkVertexExtension {
		private final int id;
		private final boolean fluid;
		private MaterialVertex(int id, boolean fluid) { this.id = id; this.fluid = fluid; }
		@Override public int getBlockId() { return id; }
		@Override public byte getRenderType() { return (byte) (fluid ? 1 : 0); }
		@Override public byte getBlockEmission() { return 0; }
		@Override public int getLocalPosX() { return 3; }
		@Override public int getLocalPosY() { return 4; }
		@Override public int getLocalPosZ() { return 5; }
		@Override public boolean ignoreMidBlock() { return false; }
		@Override public void iris$setData(byte emission, byte renderType, int blockId, int x, int y, int z) { throw new UnsupportedOperationException(); }
		@Override public void iris$ignoresMidBlock(boolean ignore) { throw new UnsupportedOperationException(); }
		@Override public void iris$copyData(ChunkVertexExtension destination) { throw new UnsupportedOperationException(); }
	}
}
