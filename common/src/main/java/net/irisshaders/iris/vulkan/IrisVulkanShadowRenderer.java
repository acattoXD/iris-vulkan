package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderRegionAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderSectionManagerAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_SodiumWorldRendererAccessor;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.BoxCuller;
import net.irisshaders.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Draws the pack's shadow programs over the real Sodium region meshes and the
 * extracted entity geometry. It deliberately builds a separate caster list:
 * chunks behind the player still cast shadows, and the main view's asynchronous
 * occlusion tree, render lists and per-frame uniforms must remain untouched.
 */
public final class IrisVulkanShadowRenderer implements NativeVulkanWorldRenderingPipeline.NativeShadowPass {
	private static IrisVulkanShadowRenderer rendering;
	private static IrisVulkanShadowRenderer latest;
	private final WorldRenderingPipeline pipeline;
	private final ProgramSet programs;
	private final ProgramFallbackResolver resolver;
	private final PackShadowDirectives directives;
	private final float sunPathRotation;
	private final Map<PipelineKey, RenderPipeline> adaptedPipelines = new HashMap<>();
	private final Map<RenderPipeline, ShaderKey> pipelineKeys = new java.util.IdentityHashMap<>();
	private final LevelRenderState entityState = new LevelRenderState();
	private SubmitNodeStorage entitySubmits = new SubmitNodeStorage();
	private final Matrix4f modelView = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private IrisVulkanShadowTargets targets;
	private UniformBufferManager terrainUniforms;
	private ClientLevel uniformLevel;
	private int uniformRenderDistance;
	private RenderBuffers entityBuffers;
	private FeatureRenderDispatcher entityFeatures;
	private ShaderKey currentKey;
	private int[] currentDrawBuffers = new int[0];
	private boolean matricesValid;
	private boolean closed;
	private boolean loggedDraw;
	private int renderedSections;
	private int renderedEntities;
	private int renderedBlockEntities;
	private long renderedFrames;
	private final IrisVulkanPipelineWarmup pipelineWarmup = new IrisVulkanPipelineWarmup();

	public IrisVulkanShadowRenderer(ProgramSet programs, WorldRenderingPipeline pipeline) {
		this.programs = programs;
		this.pipeline = pipeline;
		this.resolver = new ProgramFallbackResolver(programs);
		this.directives = programs.getPackDirectives().getShadowDirectives();
		this.sunPathRotation = programs.getPackDirectives().getSunPathRotation();
	}

	public static boolean active() {
		return rendering != null;
	}

	public static ShaderKey shaderKey() {
		return rendering == null ? null : rendering.currentKey;
	}

	public static ShaderKey shaderKey(RenderPipeline pipeline) {
		if (rendering == null || rendering.currentKey == null) return null;
		ShaderKey stored = rendering.pipelineKeys.get(pipeline);
		if (stored != null) return stored;
		ShaderKey mapped = IrisPipelines.getPipeline(null, pipeline);
		if (mapped == null) return rendering.currentKey;
		if (rendering.currentKey == ShaderKey.SHADOW_BLOCK && mapped == ShaderKey.SHADOW_ENTITIES_CUTOUT) {
			return ShaderKey.SHADOW_BLOCK;
		}
		return mapped;
	}

	public static int[] drawBufferIndices() {
		return rendering == null ? new int[0] : rendering.currentDrawBuffers.clone();
	}

	public static IrisVulkanShadowTargets.Binding binding(String sampler) {
		return latest == null || latest.targets == null ? null : latest.targets.binding(sampler);
	}

	/** The shader-visible matrices keep the pack's legacy [-1,1] clip-space convention. */
	public static Matrix4f uniformMatrix(String name) {
		IrisVulkanShadowRenderer source = rendering != null ? rendering : latest;
		if (source == null || !source.matricesValid || source.closed) return null;
		return switch (name) {
			case "shadowModelView" -> new Matrix4f(source.modelView);
			case "shadowProjection" -> new Matrix4f(source.projection);
			case "shadowModelViewInverse" -> new Matrix4f(source.modelView).invert();
			case "shadowProjectionInverse" -> new Matrix4f(source.projection).invert();
			default -> null;
		};
	}

	public static String patchVertexDepth(String source) {
		return IrisVulkanShadowMath.patchVertexDepth(source);
	}

	/** Called before ordinary gbuffer routing by CommandEncoder's native hook. */
	public static RenderPassDescriptor rewriteRenderPass(RenderPassDescriptor descriptor) {
		if (rendering == null || rendering.currentKey == null || descriptor.depthAttachment() == null) {
			return null;
		}
		return rendering.targets.rewrite(descriptor, rendering.currentDrawBuffers);
	}

	/** Separate immutable pipelines keep forward shadow depth out of the main reversed-Z cache. */
	public static RenderPipeline adaptPipeline(RenderPipeline original,
												 List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> attachments) {
		IrisVulkanShadowRenderer renderer = rendering;
		if (renderer == null || renderer.currentKey == null || !renderer.targets.matches(attachments, renderer.currentDrawBuffers)) {
			return original;
		}
		if (renderer.pipelineKeys.containsKey(original)) return original;
		PipelineKey key = new PipelineKey(original, shaderKey(original), renderer.targets.formats(renderer.currentDrawBuffers));
		return renderer.adaptedPipelines.computeIfAbsent(key, ignored -> {
			ColorTargetState[] colors = key.formats().stream()
				.map(format -> new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
				.toArray(ColorTargetState[]::new);
			Identifier location = Identifier.fromNamespaceAndPath("iris", "native_shadow/"
				+ key.shader().name().toLowerCase(java.util.Locale.ROOT) + "/" + original.getLocation().getNamespace()
				+ "/" + original.getLocation().getPath());
			RenderPipeline result = VKOnly_RenderPipelineAccessor.iris$create(location,
				original.getShaders(), original.getShaderDefines(), original.getBindGroupLayouts(),
				colors, new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true), original.getPolygonMode(), false,
				original.getVertexFormatBindings().toArray(VertexFormat[]::new), original.getPrimitiveTopology(), original.pushConstantSize(), original.getSortKey());
			IrisPipelines.copyPipeline(original, result);
			renderer.pipelineKeys.put(result, key.shader());
			return result;
		});
	}

	@Override
	public void render(LevelRendererAccessor levelRenderer, Camera camera, CameraRenderState playerState) {
		if (closed) throw new IllegalStateException("Native shadow renderer has been closed");
		if (rendering != null) throw new IllegalStateException("Native shadow rendering cannot be nested");
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
		if (sodium == null) throw new IllegalStateException("Native shadows require the active Sodium terrain renderer");
		RenderSectionManager sections = ((VKOnly_SodiumWorldRendererAccessor) sodium).iris$getRenderSectionManager();
		if (sections == null) return;
		boolean indexedRendering = ((VKOnly_SodiumWorldRendererAccessor) sodium).iris$usesTranslucencySorting();
		ensureResources(client);
		Vec3 cameraPos = camera.position();
		PoseStack shadowPose = prepareMatrices(cameraPos);
		double distance = casterDistance(client);
		List<ChunkRenderList> lists = buildCasterLists(sections, cameraPos, distance);
		ChunkRenderListIterable iterable = reverse -> reverse ? lists.reversed().iterator() : lists.iterator();
		// Main terrain was prepared before addMainPass. Both views share Sodium's
		// region command caches and renderer-wide shouldDraw flags, so retain the
		// exact regular lists to restore after consuming our independent casters.
		ChunkRenderListIterable regularLists = sections.getRenderLists();
		CameraTransform regularTransform = new CameraTransform(playerState.pos.x(), playerState.pos.y(), playerState.pos.z());
		ChunkRenderMatrices matrices = new ChunkRenderMatrices(projection, modelView);
		CameraTransform transform = new CameraTransform(cameraPos.x(), cameraPos.y(), cameraPos.z());
		GpuSampler sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true);
		WorldRenderingPhase previousPhase = pipeline.getPhase();
		boolean previousShadowActive = ShadowRenderer.ACTIVE;
		float previousAlphaTest = CapturedRenderingState.INSTANCE.getCurrentAlphaTest();
		RenderBuffers previousBuffers = levelRenderer.getRenderBuffers();
		targets.beginFrame(RenderSystem.getDevice().createCommandEncoder());
		terrainUniforms.prepareFrame();
		terrainUniforms.update(matrices, FogParameters.NONE);
		var savedProjection = RenderSystem.getProjectionMatrixBuffer();
		var savedProjectionType = RenderSystem.getProjectionType();
		ByteBuffer projectionData = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
		projection.get(projectionData);
		GpuBuffer shadowProjection = RenderSystem.getDevice().createBuffer(() -> "Iris native shadow projection",
			GpuBuffer.USAGE_UNIFORM, projectionData);
		latest = this;
		rendering = this;
		ShadowRenderer.ACTIVE = true;
		ShadowRenderer.RESOLUTION = targets.resolution();
		ShadowRenderer.renderDistance = (int) Math.ceil(distance / 16.0);
		ShadowRenderer.MODELVIEW = new Matrix4f(modelView);
		ShadowRenderer.PROJECTION = new Matrix4f(projection);
		RenderSystem.getModelViewStack().pushMatrix();
		RenderSystem.getModelViewStack().set(modelView);
		RenderSystem.setProjectionMatrix(shadowProjection.slice(), directives.getFov() == null
			? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE);
		levelRenderer.setRenderBuffers(entityBuffers);
		Throwable renderFailure = null;
		try {
			if (!pipelineWarmup.attempted()) {
				select(ShaderKey.SHADOW_ENTITIES_CUTOUT, WorldRenderingPhase.ENTITIES);
				pipelineWarmup.warmup(programs, true);
			}
			// Cached batches are indexed by region/pass, so invalidate around the independent list.
			for (ChunkRenderList list : lists) list.getRegion().clearAllCachedBatches();
			// Since 26.3 render() only consumes prepared batches. Preparation can
			// allocate/upload index and indirect buffers and must precede every
			// shadow RenderPass. The active shadow scope also disables face culling.
			sections.getChunkRenderer().prepare(iterable, transform, indexedRendering);
			if (directives.shouldRenderTerrain()) {
				drawTerrain(sections, iterable, matrices, transform, sampler, DefaultTerrainRenderPasses.SOLID,
					ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID, WorldRenderingPhase.TERRAIN_SOLID, indexedRendering);
				drawTerrain(sections, iterable, matrices, transform, sampler, DefaultTerrainRenderPasses.CUTOUT,
					ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT, WorldRenderingPhase.TERRAIN_CUTOUT, indexedRendering);
			}
			renderEntityCasters(client, camera, cameraPos, shadowPose, lists, distance);
			targets.copyOpaqueDepth(RenderSystem.getDevice().createCommandEncoder());
			RenderSystem.getModelViewStack().set(modelView);
			if (directives.shouldRenderTranslucent()) {
				drawTerrain(sections, iterable, matrices, transform, sampler, DefaultTerrainRenderPasses.TRANSLUCENT,
					ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT, WorldRenderingPhase.TERRAIN_TRANSLUCENT, indexedRendering);
			}
			currentKey = null;
			pipeline.setPhase(WorldRenderingPhase.NONE);
			targets.finishFrame(RenderSystem.getDevice().createCommandEncoder());
			renderedFrames++;
			if (!loggedDraw && renderedSections > 0) {
				loggedDraw = true;
				Iris.logger.info("Rendered native Vulkan shadow maps: {}x{}, {} terrain sections, {} entities, {} block entities; opaque depth copied before translucent casters.",
					targets.resolution(), targets.resolution(), renderedSections, renderedEntities, renderedBlockEntities);
			}
		} catch (RuntimeException | Error failure) {
			renderFailure = failure;
			try { Iris.logger.error("Native Vulkan shadow rendering failed; preserving the primary draw failure", failure); }
			catch (RuntimeException | Error loggingFailure) { preserveFailure(failure, loggingFailure); }
			throw failure;
		} finally {
			// An unclosed backend pass can make fence/endFrame cleanup fail too.
			// Keep the original draw error and restore every independent piece of
			// state; secondary failures belong on that error, not in its place.
			Throwable cleanupFailure = renderFailure;
			for (ChunkRenderList list : lists) {
				try { list.getRegion().clearAllCachedBatches(); }
				catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			}
			try { entitySubmits = new SubmitNodeStorage(); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { entityBuffers.endFrame(); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { terrainUniforms.endFrame(); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { levelRenderer.setRenderBuffers(previousBuffers); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { RenderSystem.getModelViewStack().popMatrix(); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { RenderSystem.queueFencedTask(shadowProjection::close); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			try { pipeline.setPhase(previousPhase); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			ShadowRenderer.ACTIVE = previousShadowActive;
			currentKey = null;
			currentDrawBuffers = new int[0];
			rendering = null;
			try { CapturedRenderingState.INSTANCE.setCurrentAlphaTest(previousAlphaTest); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			// The player's batches were invalidated with the shared region caches.
			// Rebuild them after restoring ordinary face culling, including each
			// pass's shouldDraw flag, before the main frame graph executes.
			try { sections.getChunkRenderer().prepare(regularLists, regularTransform, indexedRendering); }
			catch (RuntimeException | Error failure) { cleanupFailure = preserveFailure(cleanupFailure, failure); }
			if (renderFailure == null && cleanupFailure != null) {
				if (cleanupFailure instanceof Error error) throw error;
				throw (RuntimeException) cleanupFailure;
			}
		}
	}

	private static Throwable preserveFailure(Throwable primary, Throwable secondary) {
		if (primary == null) return secondary;
		if (primary != secondary) primary.addSuppressed(secondary);
		return primary;
	}

	private void ensureResources(Minecraft client) {
		if (targets == null) targets = new IrisVulkanShadowTargets(programs);
		int distance = client.options.getEffectiveRenderDistance();
		if (terrainUniforms == null || uniformLevel != client.level || uniformRenderDistance != distance) {
			if (terrainUniforms != null) terrainUniforms.delete();
			terrainUniforms = new UniformBufferManager(client.level, distance);
			uniformLevel = client.level;
			uniformRenderDistance = distance;
		}
		if (entityBuffers == null) {
			entityBuffers = new RenderBuffers(1);
			entityFeatures = new FeatureRenderDispatcher(entityBuffers, client.getModelManager(), client.getAtlasManager(),
				client.font, client.gameRenderer.gameRenderState());
		}
	}

	private PoseStack prepareMatrices(Vec3 camera) {
		float limit = Math.max(16.0f, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f);
		float near = directives.getNearPlane() == -1.0f ? -limit : directives.getNearPlane();
		float far = directives.getFarPlane() == -1.0f ? limit : directives.getFarPlane();
		float angle = ShadowRenderer.getSunAngle(CelestialUniforms.isDay()) / 360.0f;
		PoseStack pose = new PoseStack();
		ShadowMatrices.createModelViewMatrix(pose, angle, directives.getIntervalSize(), sunPathRotation,
			camera.x(), camera.y(), camera.z(), near, far);
		modelView.set(pose.last().pose());
		projection.set(IrisVulkanShadowMath.projection(directives.getDistance(), near, far, directives.getFov()));
		matricesValid = true;
		return pose;
	}

	private double casterDistance(Minecraft client) {
		double requested = directives.getDistanceRenderMul() < 0.0f
			? client.options.getEffectiveRenderDistance() * 16.0 : directives.getDistance() * directives.getDistanceRenderMul();
		return Math.max(0.0, requested);
	}

	private List<ChunkRenderList> buildCasterLists(RenderSectionManager manager, Vec3 camera, double distance) {
		List<ChunkRenderList> lists = new ArrayList<>();
		renderedSections = 0;
		for (RenderRegion region : ((VKOnly_RenderSectionManagerAccessor) manager).iris$getRegions().getLoadedRegions()) {
			if (region.getResources() == null) continue;
			ChunkRenderList list = new ChunkRenderList(region);
			for (RenderSection section : ((VKOnly_RenderRegionAccessor) region).iris$getSections()) {
				if (section == null) continue;
				// Do not cull by the linear light frustum: packs can distort it non-linearly.
				if (!IrisVulkanShadowMath.intersectsDistance(section.getOriginX() - 1.0, section.getOriginY() - 1.0,
					section.getOriginZ() - 1.0, section.getOriginX() + 17.0, section.getOriginY() + 17.0,
					section.getOriginZ() + 17.0, camera.x(), camera.y(), camera.z(), distance)) continue;
				list.add(section.getSectionIndex());
			}
			if (list.size() > 0) {
				lists.add(list);
				renderedSections += list.getSectionsWithGeometryCount();
			}
		}
		return lists;
	}

	private void select(ShaderKey key, WorldRenderingPhase phase) {
		ProgramSource source = resolver.resolve(key.getProgram()).orElseThrow(() ->
			new IllegalStateException("Missing pack shadow program for " + key));
		currentKey = key;
		currentDrawBuffers = source.getDirectives().getDrawBuffers().clone();
		CapturedRenderingState.INSTANCE.setCurrentAlphaTest(source.getDirectives().getAlphaTestOverride()
			.orElse(key.getAlphaTest()).reference());
		pipeline.setPhase(phase);
	}

	private void drawTerrain(RenderSectionManager manager, ChunkRenderListIterable lists, ChunkRenderMatrices matrices,
							 CameraTransform transform, GpuSampler sampler, TerrainRenderPass pass, ShaderKey key,
							 WorldRenderingPhase phase, boolean indexedRendering) {
		select(key, phase);
		// Existing translucent meshes carry offsets into their region's index buffer.
		// Disabling indexed drawing does not reset those offsets for the global
		// quad buffer; consume Sodium's existing indices without scheduling a sort.
		try (RenderPass renderPass = openShadowPass("Iris native shadow terrain " + key.name())) {
			manager.getChunkRenderer().render(matrices, lists, pass, transform, FogParameters.NONE, indexedRendering, renderPass,
				sampler, terrainUniforms.getUniformBuffer(), terrainUniforms.getSectionTimeInfo(), null);
		}
	}

	/** The native command hook replaces these main-target views with the selected shadow attachments. */
	private RenderPass openShadowPass(String label) {
		var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label,
			main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty());
	}

	private void drawEntityFeatures(String label) {
		try (var frame = entityFeatures.prepareFrame(entitySubmits);
			 RenderPass renderPass = openShadowPass(label)) {
			FeatureRenderDispatcher.renderAllFeatures(renderPass, frame);
		}
	}

	private void renderEntityCasters(Minecraft client, Camera camera, Vec3 position, PoseStack pose,
									List<ChunkRenderList> lists, double distance) {
		entityState.reset();
		camera.extractRenderState(entityState.cameraRenderState, client.getDeltaTracker());
		entityState.cameraRenderState.viewRotationMatrix = new Matrix4f(modelView);
		entityState.cameraRenderState.projectionMatrix = new Matrix4f(projection);
		double entityDistance = directives.getEntityShadowDistanceMul() < 0 ? distance
			: distance * directives.getEntityShadowDistanceMul();
		BoxCullingFrustum frustum = new BoxCullingFrustum(new BoxCuller(entityDistance));
		frustum.prepare(position.x(), position.y(), position.z());
		entityState.cameraRenderState.cullFrustum = frustum;
		ShadowRenderer.FRUSTUM = frustum;
		renderedEntities = 0;
		renderedBlockEntities = 0;
		RenderSystem.getModelViewStack().identity();
		if (directives.shouldRenderEntities() || directives.shouldRenderPlayer()) {
			select(ShaderKey.SHADOW_ENTITIES_CUTOUT, WorldRenderingPhase.ENTITIES);
			for (Entity entity : client.level.entitiesForRendering()) {
				if (!directives.shouldRenderEntities() && entity != client.player
					&& entity != client.player.getVehicle()) continue;
				if (entity instanceof AbstractClientPlayer player && player.isSpectator()) continue;
				float tick = client.getDeltaTracker().getGameTimeDeltaPartialTick(!client.level.tickRateManager().isEntityFrozen(entity));
				if (!client.getEntityRenderDispatcher().shouldRender(entity, frustum, position.x(), position.y(), position.z(), tick)
					&& !entity.hasIndirectPassenger(client.player)) continue;
				EntityRenderState state = client.getEntityRenderDispatcher().extractEntity(entity, tick);
				client.getEntityRenderDispatcher().submit(state, entityState.cameraRenderState,
					state.x - position.x(), state.y - position.y(), state.z - position.z(), pose, entitySubmits);
				renderedEntities++;
			}
			drawEntityFeatures("Iris native shadow entities");
			entitySubmits = new SubmitNodeStorage();
		}
		if (directives.shouldRenderBlockEntities() || directives.shouldRenderLightBlockEntities()) {
			select(ShaderKey.SHADOW_BLOCK, WorldRenderingPhase.BLOCK_ENTITIES);
			Set<BlockEntity> blockEntities = new HashSet<>();
			for (ChunkRenderList list : lists) {
				RenderRegion region = list.getRegion();
				for (RenderSection section : ((VKOnly_RenderRegionAccessor) region).iris$getSections()) {
					if (section == null) continue;
					BlockEntity[] culled = region.getCulledBlockEntities(section.getSectionIndex());
					BlockEntity[] global = region.getGlobalBlockEntities(section.getSectionIndex());
					if (culled != null) blockEntities.addAll(Arrays.asList(culled));
					if (global != null) blockEntities.addAll(Arrays.asList(global));
				}
			}
			float tick = CapturedRenderingState.INSTANCE.getTickDelta();
			for (BlockEntity entity : blockEntities) {
				if (!directives.shouldRenderBlockEntities() && entity.getBlockState().getLightEmission() == 0) continue;
				var blockPos = entity.getBlockPos();
				if (!IrisVulkanShadowMath.intersectsDistance(blockPos.getX(), blockPos.getY(), blockPos.getZ(),
					blockPos.getX() + 1.0, blockPos.getY() + 1.0, blockPos.getZ() + 1.0,
					position.x(), position.y(), position.z(), entityDistance)) continue;
				BlockEntityRenderState state = client.getBlockEntityRenderDispatcher().tryExtractRenderState(entity, tick, null, true);
				if (state == null) continue;
				pose.pushPose();
				pose.translate(state.blockPos.getX() - position.x(), state.blockPos.getY() - position.y(), state.blockPos.getZ() - position.z());
				client.getBlockEntityRenderDispatcher().submit(state, pose, entitySubmits, entityState.cameraRenderState);
				pose.popPose();
				renderedBlockEntities++;
			}
			drawEntityFeatures("Iris native shadow block entities");
			entitySubmits = new SubmitNodeStorage();
		}
	}

	public String debugDescription() {
		return "Native Vulkan shadows: " + renderedSections + " sections, " + renderedEntities + " entities, "
			+ renderedBlockEntities + " block entities; " + renderedFrames + " frames";
	}

	@Override
	public void close() {
		if (closed) return;
		if (rendering == this) throw new IllegalStateException("Cannot close native shadows while rendering");
		closed = true;
		if (latest == this) latest = null;
		if (targets != null) targets.close();
		if (terrainUniforms != null) terrainUniforms.delete();
		if (entityFeatures != null) entityFeatures.close();
		if (entityBuffers != null) entityBuffers.close();
		adaptedPipelines.clear();
		pipelineKeys.clear();
	}

	private record PipelineKey(RenderPipeline original, ShaderKey shader, List<com.mojang.renderpearl.api.GpuFormat> formats) {
	}
}
