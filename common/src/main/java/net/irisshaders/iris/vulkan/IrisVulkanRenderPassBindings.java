package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPassAccessor;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanRenderPassAccessor;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import org.joml.Vector4fc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class IrisVulkanRenderPassBindings {
	// Track compiled instances, not RenderPipeline descriptions: an unchanged
	// description may be shared by an Iris override and the engine's own pipeline.
	private static final Set<VulkanRenderPipeline> IRIS_PIPELINES =
		Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
	private static final Map<String, String> UNIFORM_ALIASES = Map.of(
		"iris_DynamicTransforms", "DynamicTransforms",
		"iris_Projection", "Projection",
		"iris_Globals", "Globals",
		"iris_Fog", "Fog",
		"iris_CloudInfo", "CloudInfo"
	);
	private static final Set<String> WARNED_DUMMY_TEXTURES = ConcurrentHashMap.newKeySet();
	private static final Set<String> WARNED_SHADOW_FALLBACKS = ConcurrentHashMap.newKeySet();
	private static final Set<String> LOGGED_SCREEN_PASS_BINDINGS = ConcurrentHashMap.newKeySet();
	private static final Map<RenderPass, ScreenPassContext> SCREEN_PASS_CONTEXTS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<RenderPass, GpuBuffer> SNAPSHOT_BUFFERS =
		Collections.synchronizedMap(new WeakHashMap<>());
	private static GpuTexture dummyTexture;
	private static GpuTextureView dummyTextureView;
	private static net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture defaultNormal;
	private static net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture defaultSpecular;
	private static net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture cloudWhite;
	private static TextureBinding cloudWhiteBinding;

	private IrisVulkanRenderPassBindings() {
	}

	static void registerPipeline(VulkanRenderPipeline pipeline) {
		IRIS_PIPELINES.add(pipeline);
	}

	static void unregisterPipeline(VulkanRenderPipeline pipeline) {
		IRIS_PIPELINES.remove(pipeline);
	}

	public static void closePackResources() {
		IrisVulkanCustomTextures.close();
		if (defaultNormal != null) defaultNormal.close();
		if (defaultSpecular != null) defaultSpecular.close();
		if (cloudWhite != null) cloudWhite.close();
		defaultNormal = null;
		defaultSpecular = null;
		cloudWhite = null;
		cloudWhiteBinding = null;
	}

	/** Standard Iris values for vanilla resources without normal/specular maps. */
	public static void preparePackResources() {
		// Upload pack/noise textures before opening any draw pass. RenderPearl
		// prohibits transfers during an active pass, including first-use uploads.
		IrisVulkanCustomTextures.noise(currentNoiseTextureResolution());
		if (defaultNormal == null) defaultNormal = new net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture(
			net.irisshaders.iris.pbr.texture.PBRType.NORMAL.getDefaultValue());
		if (defaultSpecular == null) defaultSpecular = new net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture(
			net.irisshaders.iris.pbr.texture.PBRType.SPECULAR.getDefaultValue());
		if (cloudWhite == null) {
			cloudWhite = new net.irisshaders.iris.targets.backed.NativeImageBackedSingleColorTexture(255, 255, 255, 255);
			cloudWhiteBinding = new TextureBinding(cloudWhite.getTextureView(), RenderSystem.getSamplerCache()
				.getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST, false));
		}
	}

	public static void apply(RenderPass pass, VulkanRenderPass backend,
							 List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		VulkanRenderPipeline compiled = ((VKOnly_VulkanRenderPassAccessor) backend).iris$getPipeline();

		if (compiled == null || compiled.isClosed() || !IRIS_PIPELINES.contains(compiled)) {
			// Vanilla atlas/UI passes and other mods own their bindings. Their
			// declared layouts can include unused uniforms (such as Globals during
			// atlas initialization) that are absent from the compiled shader.
			return;
		}
		IrisVulkanStoragePipeline.bind(backend);

		RenderPipeline pipeline = IrisNativeVulkan.pipelineInfo(compiled);
		ScreenPassContext screenPassContext = SCREEN_PASS_CONTEXTS.remove(pass);

		if (screenPassContext != null) {
			bindScreenPassResources(pass, pipeline, screenPassContext.depthView(), screenPassContext.finalSourceView(),
				screenPassContext.passLabel(), screenPassContext.stage());

			if (LOGGED_SCREEN_PASS_BINDINGS.add(screenPassContext.passLabel())) {
				Iris.logger.info("Bound native Vulkan screen pass {} with compiled pipeline {}, samplers={}, dummySamplers={}.",
					screenPassContext.passLabel(), pipeline.getLocation(),
					IrisVulkanLayouts.samplers(pipeline.getBindGroupLayouts()),
					IrisNativeVulkan.screenPassDummySamplers());
			}

			return;
		}

		RenderSystem.bindDefaultUniforms(pass);
		var boundTextures = ((VKOnly_RenderPassAccessor) pass).iris$getUniforms();
		var key = IrisNativeVulkan.worldShaderKey(pipeline);
		if (key != null) {
			var directives = IrisNativeVulkan.getWorldDirectives(key);
			var alpha = directives == null ? key.getAlphaTest() : directives.getAlphaTestOverride().orElse(key.getAlphaTest());
			net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentAlphaTest(alpha.reference());
		}
		TextureBinding primary = primaryTexture(boundTextures, key);
		if (isUsable(primary)) IrisVulkanUniformSnapshot.setPrimaryTextureSize(primary.view().texture().getWidth(0), primary.view().texture().getHeight(0));
		bindUniformAliases(pass, backend, pipeline);
		bindTextureAliases(pass, backend, pipeline, colorAttachments);
	}

	public static void prepareScreenPassResources(RenderPass pass, String passLabel, TextureStage stage,
												  GpuTextureView depthView) {
		prepareScreenPassResources(pass, passLabel, stage, depthView, null);
	}

	public static void prepareScreenPassResources(RenderPass pass, String passLabel, TextureStage stage,
												  GpuTextureView depthView, GpuTextureView finalSourceView) {
		SCREEN_PASS_CONTEXTS.put(pass, new ScreenPassContext(passLabel, stage, depthView, finalSourceView));
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView) {
		bindScreenPassResources(pass, pipeline, depthView, "<unknown>");
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   String passLabel) {
		bindScreenPassResources(pass, pipeline, depthView, passLabel, TextureStage.COMPOSITE_AND_FINAL);
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   String passLabel, TextureStage stage) {
		bindScreenPassResources(pass, pipeline, depthView, null, passLabel, stage);
	}

	public static void bindScreenPassResources(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   GpuTextureView finalSourceView, String passLabel, TextureStage stage) {
		RenderSystem.bindDefaultUniforms(pass);
		bindScreenPassUniforms(pass, pipeline, passLabel);
		bindScreenPassTextures(pass, pipeline, depthView, finalSourceView, passLabel, stage);
	}

	private static void bindScreenPassUniforms(RenderPass pass, RenderPipeline pipeline, String passLabel) {
		List<BindGroupLayout.UniformDescription> requiredUniforms = IrisVulkanLayouts.buffers(pipeline.getBindGroupLayouts());
		GpuBufferSlice snapshotBuffer = bindUniformSnapshot(pass, pipeline, passLabel);

		for (BindGroupLayout.UniformDescription uniform : requiredUniforms) {
			String name = uniform.name();

			if (name.equals(IrisVulkanUniformSnapshot.BLOCK_NAME)) {
				if (snapshotBuffer == null) {
					throw missingUniform(passLabel, name);
				}

				continue;
			}

			if (bindDefaultUniform(pass, name)) {
				continue;
			}

			throw missingUniform(passLabel, name);
		}
	}

	private static GpuBufferSlice bindUniformSnapshot(RenderPass pass, RenderPipeline pipeline, String passLabel) {
		IrisVulkanShaderResources.ResourceSet resources = IrisVulkanShaderResources.resourcesFor(pipeline);
		if (resources == null || resources.uniformFields().isEmpty()) {
			return null;
		}

		List<BindGroupLayout.UniformDescription> requiredUniforms = IrisVulkanLayouts.buffers(pipeline.getBindGroupLayouts());
		if (requiredUniforms.stream().noneMatch(uniform ->
			uniform.name().equals(IrisVulkanUniformSnapshot.BLOCK_NAME))) {
			return null;
		}

		if (IrisNativeVulkan.worldDevelopmentEnabled()) {
			GpuBufferSlice snapshot = IrisVulkanUniformSnapshot.uploadTransient(resources.uniformFields());
			pass.setUniform(IrisVulkanUniformSnapshot.BLOCK_NAME, snapshot);
			return snapshot;
		}

		GpuBuffer buffer = SNAPSHOT_BUFFERS.get(pass);
		if (buffer == null || buffer.isClosed()) {
			IrisVulkanUniformSnapshot.Snapshot snapshot = IrisVulkanUniformSnapshot.capture(resources.uniformFields());
			buffer = snapshot.upload();
			SNAPSHOT_BUFFERS.put(pass, buffer);
			GpuBuffer retainedBuffer = buffer;
			RenderSystem.queueFencedTask(() -> {
				if (SNAPSHOT_BUFFERS.remove(pass, retainedBuffer)) {
					retainedBuffer.close();
				}
			});
		}

		pass.setUniform(IrisVulkanUniformSnapshot.BLOCK_NAME, buffer.slice());
		return buffer.slice();
	}

	private static IllegalStateException missingUniform(String passLabel, String name) {
		return new IllegalStateException("Missing Vulkan " + passLabel + " uniform binding for " + name
			+ "; the resource must be supported explicitly or the pass must be disabled");
	}

	private static void bindScreenPassTextures(RenderPass pass, RenderPipeline pipeline, GpuTextureView depthView,
											   GpuTextureView finalSourceView, String passLabel, TextureStage stage) {
		List<String> requiredSamplers = IrisVulkanLayouts.samplers(pipeline.getBindGroupLayouts());
		java.util.HashSet<String> boundSamplers = new java.util.HashSet<>();

		for (String sampler : requiredSamplers) {
			if (!boundSamplers.add(sampler)) {
				continue;
			}

			TextureBinding binding = screenPassTextureBinding(sampler, depthView, finalSourceView, passLabel, stage);
			if (!isUsable(binding)) {
				throw new IllegalStateException("Invalid Vulkan screen pass texture binding for " + sampler);
			}

			pass.setUniform(sampler, binding.view(), binding.sampler());
		}
	}

	private static TextureBinding screenPassTextureBinding(String sampler, GpuTextureView depthView, String passLabel,
														  TextureStage stage) {
		return screenPassTextureBinding(sampler, depthView, null, passLabel, stage);
	}

	private static TextureBinding screenPassTextureBinding(String sampler, GpuTextureView depthView,
														  GpuTextureView finalSourceView, String passLabel,
														  TextureStage stage) {
		if (IrisNativeVulkan.shouldDummyScreenPassSampler(sampler)) {
			if (WARNED_DUMMY_TEXTURES.add("screen:" + passLabel + ":" + sampler)) {
				Iris.logger.warn("Using dummy Vulkan screen pass texture for {} sampler {} because iris.vulkan.screenPassDummySamplers={} is set.",
					passLabel, sampler, IrisNativeVulkan.screenPassDummySamplers());
			}

			return dummyTextureBinding();
		}

		if (sampler.equals("noisetex")) {
			return fromCustom(IrisVulkanCustomTextures.noise(currentNoiseTextureResolution()));
		}

		if (isShadowSampler(sampler)) {
			var binding = IrisVulkanShadowRenderer.binding(sampler);
			if (binding != null) return new TextureBinding(binding.view(), binding.sampler());
			if (IrisNativeVulkan.worldDevelopmentEnabled()) throw new IllegalStateException("Missing rendered native shadow target " + sampler);
			if (WARNED_SHADOW_FALLBACKS.add(sampler)) {
				Iris.logger.warn("Using a fully-lit fallback for native Vulkan shadow sampler {}; real shadow targets are not wired yet.", sampler);
			}
			return dummyTextureBinding();
		}

		IrisVulkanCustomTextures.Binding customBinding = IrisVulkanCustomTextures.find(stage, sampler);

		if (customBinding != null) {
			return fromCustom(customBinding);
		}

		GpuSampler gpuSampler = RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST);

		if (isFinalSourceSampler(sampler) && finalSourceView != null && !finalSourceView.isClosed()) {
			return new TextureBinding(finalSourceView, IrisVulkanGbufferTargets.samplerForTarget(finalSourceView));
		}

		GpuTextureView targetView = IrisVulkanGbufferTargets.colorSamplerView(sampler);

		if (targetView != null) {
			return new TextureBinding(targetView, IrisVulkanGbufferTargets.samplerForTarget(targetView));
		}

		GpuTextureView snapshotDepth = IrisVulkanGbufferTargets.depthSamplerView(sampler);
		if (snapshotDepth != null && !snapshotDepth.isClosed()) return new TextureBinding(snapshotDepth, gpuSampler);

		if ((sampler.equals("depthtex0") || sampler.equals("gdepthtex") || sampler.equals("depthtex1") || sampler.equals("depthtex2"))
			&& depthView != null && !depthView.isClosed()) {
			return new TextureBinding(depthView, gpuSampler);
		}

		if (isAlbedoSampler(sampler)) {
			targetView = IrisVulkanGbufferTargets.colorSamplerView("colortex" + IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET);

			if (targetView != null) {
				return new TextureBinding(targetView, gpuSampler);
			}
		}

		throw new IllegalStateException("Missing Vulkan screen pass " + passLabel
			+ " texture binding for " + sampler + "; the resource must be supported explicitly");
	}

	private static boolean isFinalSourceSampler(String sampler) {
		return sampler.equals("colortex" + IrisVulkanGbufferTargets.FINAL_SOURCE_TARGET);
	}

	private static boolean isShadowSampler(String sampler) {
		return sampler.equals("shadowtex0") || sampler.equals("shadowtex1")
			|| sampler.equals("shadowcolor0") || sampler.equals("shadowcolor1");
	}

	private static TextureBinding fromCustom(IrisVulkanCustomTextures.Binding binding) {
		return new TextureBinding(binding.view(), binding.sampler());
	}

	private static void bindUniformAliases(RenderPass pass, VulkanRenderPass backend, RenderPipeline pipeline) {
		Map<String, Object> uniforms = ((VKOnly_RenderPassAccessor) pass).iris$getUniforms();
		List<BindGroupLayout.UniformDescription> requiredUniforms = IrisVulkanLayouts.buffers(pipeline.getBindGroupLayouts());
		String passLabel = "render pass " + pipeline.getLocation();
		GpuBufferSlice snapshotBuffer = bindUniformSnapshot(pass, pipeline, passLabel);

		for (BindGroupLayout.UniformDescription uniform : requiredUniforms) {
			String name = uniform.name();

			if (name.equals(IrisVulkanUniformSnapshot.BLOCK_NAME)) {
				if (snapshotBuffer == null) {
					throw missingUniform(passLabel, name);
				}

				continue;
			}

			String source = UNIFORM_ALIASES.get(name);
			GpuBufferSlice aliasedUniform = source != null && uniforms.get(source) instanceof GpuBufferSlice slice ? slice : null;
			if (aliasedUniform != null) {
				// Vanilla can change transforms between draws in the same pass.
				// Refresh the alias from that current source instead of reusing ours.
				pass.setUniform(name, aliasedUniform);
				continue;
			}

			GpuBufferSlice directUniform = uniforms.get(name) instanceof GpuBufferSlice slice ? slice : null;
			if (directUniform != null) {
				continue;
			}

			if (bindDefaultUniform(pass, name)) {
				continue;
			}

			throw missingUniform(passLabel, name);
		}
	}

	private static boolean bindDefaultUniform(RenderPass pass, String name) {
		if (name.equals("Projection")) {
			GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();

			if (projection != null) {
				pass.setUniform(name, projection);
				return true;
			}
		}

		if (name.equals("Globals")) {
			GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();

			if (globals != null) {
				pass.setUniform(name, globals);
				return true;
			}
		}

		return false;
	}

	private static void bindTextureAliases(RenderPass pass, VulkanRenderPass backend, RenderPipeline pipeline,
										   List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		Map<String, Object> textures = ((VKOnly_RenderPassAccessor) pass).iris$getUniforms();
		List<String> requiredSamplers = IrisVulkanLayouts.samplers(pipeline.getBindGroupLayouts());
		ShaderKey key = IrisNativeVulkan.worldShaderKey(pipeline);

		for (String sampler : requiredSamplers) {
			TextureBinding binding = findTextureBinding(sampler, textures, key);

			if (isUsable(binding)) {
				pass.setUniform(sampler, binding.view(), binding.sampler());
				continue;
			}
			if (isSodium(key) && isAlbedoSampler(sampler)) {
				throw new IllegalStateException("Missing Sodium terrain atlas u_BlockTex for " + sampler);
			}

			TextureBinding directBinding = textureBinding(textures.get(sampler));
			if (isUsable(directBinding)) {
				continue;
			}

			GpuTextureView view = findRenderTargetView(sampler, colorAttachments);
			GpuSampler gpuSampler = view == null ? null : IrisVulkanGbufferTargets.depthSamplerView(sampler) != null
				? RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.NEAREST, false)
				: IrisVulkanGbufferTargets.samplerForTarget(view);

			if (view != null && gpuSampler != null) {
				pass.setUniform(sampler, view, gpuSampler);
				continue;
			}

			throw new IllegalStateException("Missing Vulkan texture binding for " + sampler
				+ "; the resource must be supported explicitly");
		}
	}

	private static TextureBinding findTextureBinding(String sampler, Map<String, Object> textures, ShaderKey key) {
		if (sampler.equals("glintTexture")) {
			// The producer supplies the correct item or armor foil texture and sampler.
			return firstTexture(textures, "GlintSampler", "glintTexture");
		}
		if (isShadowSampler(sampler)) {
			var binding = IrisVulkanShadowRenderer.binding(sampler);
			if (binding != null) return new TextureBinding(binding.view(), binding.sampler());
		}
		var custom = IrisVulkanCustomTextures.find(TextureStage.GBUFFERS_AND_SHADOW, sampler);
		if (custom != null) return fromCustom(custom);
		if (IrisVulkanLegacyGlint.active() && isAlbedoSampler(sampler)) {
			return firstTexture(textures, "GlintSampler");
		}
		if ((key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM)
			&& (isAlbedoSampler(sampler) || sampler.equals("gcolor") || sampler.equals("colortex0")
				|| sampler.equals("lightmap") || sampler.equals("u_LightTex") || sampler.equals("iris_overlay"))) {
			// Match IrisSamplers.addLevelSamplers for the untextured cloud producer.
			// These draws bind only CloudInfo/CloudFaces, so retained skin/terrain
			// textures are never authoritative. Upload white before opening a pass.
			if (!isUsable(cloudWhiteBinding)) throw new IllegalStateException("Native cloud white texture was not prepared");
			return cloudWhiteBinding;
		}
		if (sampler.equals("normals") && defaultNormal != null) return new TextureBinding(defaultNormal.getTextureView(), IrisSamplers.getTerrainCache(1));
		if (sampler.equals("specular") && defaultSpecular != null) return new TextureBinding(defaultSpecular.getTextureView(), IrisSamplers.getTerrainCache(1));
		if (sampler.equals("noisetex")) {
			return fromCustom(IrisVulkanCustomTextures.noise(currentNoiseTextureResolution()));
		}

		if (isAlbedoSampler(sampler)) {
			TextureBinding primary = primaryTexture(textures, key);
			return primary != null || isSodium(key) ? primary : firstTexture(textures, "colortex0", "gcolor");
		}

		if (sampler.equals("lightmap") || sampler.equals("u_LightTex")) {
			TextureBinding bound = producerLightmap(textures, key);
			if (isUsable(bound)) return bound;
			// Vanilla lines do not bind a lightmap, but the pack's line program can sample it.
			// Supply the engine's current lightmap instead of borrowing an unrelated albedo atlas.
			GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();
			return lightmap == null ? null : new TextureBinding(lightmap,
				RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR, false));
		}

		if (sampler.equals("iris_overlay")) {
			return firstTexture(textures, "Sampler1", "iris_overlay", "Sampler0", "u_BlockTex");
		}

		return null;
	}

	private static boolean isSodium(ShaderKey key) {
		return key != null && key.patch == Patch.SODIUM;
	}

	private static TextureBinding primaryTexture(Map<String, Object> textures, ShaderKey key) {
		if (key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM) return cloudWhiteBinding;
		// A world pass retains bindings across entities and terrain, including
		// attachment-driven reopenings. Only the current producer's namespace is
		// authoritative: Sampler0 may still contain a skin/font/particle texture
		// during Sodium draws, and u_BlockTex may remain during later entity draws.
		return isSodium(key) ? firstTexture(textures, "u_BlockTex")
			: firstTexture(textures, "Sampler0", "u_MainSampler", "gtexture", "tex", "texture");
	}

	private static TextureBinding producerLightmap(Map<String, Object> textures, ShaderKey key) {
		return isSodium(key) ? firstTexture(textures, "u_LightTex")
			: firstTexture(textures, "Sampler2", "lightmap");
	}

	private static int currentNoiseTextureResolution() {
		try {
			Optional<ShaderPack> currentPack = Iris.getCurrentPack();

			if (currentPack.isEmpty()) {
				return 256;
			}

			NamespacedId dimension = Iris.getCurrentDimension();
			if (dimension == null) {
				dimension = DimensionId.OVERWORLD;
			}

			return Math.max(1, currentPack.get().getProgramSet(dimension).getPackDirectives().getNoiseTextureResolution());
		} catch (RuntimeException e) {
			Iris.logger.warn("Could not read shaderpack noisetex resolution for native Vulkan; using 256: {}", e.getMessage());
			return 256;
		}
	}

	private static boolean isAlbedoSampler(String sampler) {
		return sampler.equals("tex") || sampler.equals("texture") || sampler.equals("gtexture") ||
			sampler.equals("u_MainSampler");
	}

	private static GpuTextureView findRenderTargetView(String sampler,
												   List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments) {
		GpuTextureView depth = IrisVulkanGbufferTargets.depthSamplerView(sampler);
		if (depth != null) return depth;
		int logicalTarget = IrisVulkanGbufferTargets.colorSamplerTarget(sampler);
		GpuTextureView gbufferView = IrisVulkanGbufferTargets.colorSamplerView(sampler);

		if (gbufferView != null) {
			for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : colorAttachments) {
				if (attachment != null && IrisVulkanGbufferTargets.isCurrentColorAttachment(logicalTarget,
					attachment.textureView())) {
					return IrisNativeVulkan.worldDevelopmentEnabled()
						? IrisVulkanGbufferTargets.feedbackView(logicalTarget) : IrisVulkanGbufferTargets.nextView(logicalTarget);
				}
			}
			return gbufferView;
		}

		if ((sampler.equals("colortex0") || sampler.equals("gcolor")) && !colorAttachments.isEmpty()) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.getFirst();

			if (attachment != null) {
				return attachment.textureView();
			}
		}

		if (sampler.equals("depthtex0") || sampler.equals("gdepthtex") || sampler.equals("depthtex1") || sampler.equals("depthtex2")) {
			var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();

			if (target != null && target.getDepthTextureView() != null) {
				return target.getDepthTextureView();
			}
		}

		return null;
	}

	private static GpuSampler findFallbackSampler(Map<String, Object> textures) {
		TextureBinding existing = findAnyTexture(textures);

		if (existing != null) {
			return existing.sampler();
		}

		return IrisSamplers.getTerrainCache(1);
	}

	private static TextureBinding firstTexture(Map<String, Object> textures, String... names) {
		for (String name : names) {
			TextureBinding texture = textureBinding(textures.get(name));

			if (texture != null) {
				return texture;
			}
		}

		return null;
	}

	private static TextureBinding findAnyTexture(Map<String, Object> textures) {
		return textures.values().stream().map(IrisVulkanRenderPassBindings::textureBinding)
			.filter(IrisVulkanRenderPassBindings::isUsable).findFirst().orElse(null);
	}

	private static TextureBinding textureBinding(Object value) {
		return value instanceof TextureViewAndSampler pair ? new TextureBinding(pair.view(), pair.sampler()) : null;
	}

	private static boolean isUsable(TextureBinding binding) {
		return binding != null && binding.view() != null && !binding.view().isClosed() && binding.sampler() != null;
	}

	private static TextureBinding dummyTextureBinding() {
		if (dummyTexture == null || dummyTexture.isClosed() || dummyTextureView == null || dummyTextureView.isClosed()) {
			dummyTexture = RenderSystem.getDevice().createTexture(() -> "Iris dummy Vulkan texture",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			dummyTextureView = RenderSystem.getDevice().createTextureView(dummyTexture);

			try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false)) {
				image.setPixel(0, 0, 0xFFFFFFFF);
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(dummyTexture, image);
			}
		}

		return new TextureBinding(dummyTextureView, IrisSamplers.getTerrainCache(1));
	}

	private record TextureBinding(GpuTextureView view, GpuSampler sampler) {
	}

	private record ScreenPassContext(String passLabel, TextureStage stage, GpuTextureView depthView,
									 GpuTextureView finalSourceView) {
	}
}
