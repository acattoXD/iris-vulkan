package net.irisshaders.iris.pipeline;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pathways.HorizonRenderer;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.BlockRenderType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Shader-pack state and world lifecycle for Minecraft's native Vulkan backend.
 * GPU work is supplied by {@link FramePasses}, so no OpenGL pipeline or texture
 * identifier is needed to initialize materials or advance the frame.
 */
public final class NativeVulkanWorldRenderingPipeline implements WorldRenderingPipeline {
	private final ProgramSet programSet;
	private final ShaderPack pack;
	private final PackDirectives directives;
	private final FramePasses framePasses;
	private final FrameUpdateNotifier frameUpdateNotifier = new FrameUpdateNotifier();
	private final IrisVulkanPhaseContext.State phaseState = new IrisVulkanPhaseContext.State();
	private final ParticleRenderingSettings particles;
	private Object2IntMap<BlockState> blockStateIds;
	private Map<Block, BlockRenderType> blockTypeIds;
	private NativeShadowPass shadowRenderer;
	private HorizonRenderer horizonRenderer;
	private GpuTextureView albedoTexture;
	private boolean renderingWorld;
	private boolean beforeTranslucents;
	private boolean handStarted;
	private boolean shadowsRendered;
	private boolean mainBound;
	private boolean destroyed;

	public NativeVulkanWorldRenderingPipeline(ProgramSet programSet, FramePasses framePasses) {
		this.programSet = Objects.requireNonNull(programSet, "programSet");
		this.pack = programSet.getPack();
		this.directives = programSet.getPackDirectives();
		this.framePasses = Objects.requireNonNull(framePasses, "framePasses");
		ParticleRenderingSettings requested = directives.getParticleRenderingSettings();
		this.particles = requested != ParticleRenderingSettings.UNSET ? requested
			: programSet.getComposite(ProgramArrayId.Deferred).length > 0 && !directives.shouldUseSeparateEntityDraws()
				? ParticleRenderingSettings.AFTER : ParticleRenderingSettings.MIXED;
		applyMaterialSettings();
	}

	private void applyMaterialSettings() {
		WorldRenderingSettings settings = WorldRenderingSettings.INSTANCE;
		settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
		settings.setEntityIds(pack.getIdMap().getEntityIdMap());
		settings.setItemIds(pack.getIdMap().getItemIdMap());
		settings.setAmbientOcclusionLevel(directives.getAmbientOcclusionLevel());
		settings.setDisableDirectionalShading(shouldDisableDirectionalShading());
		settings.setUseSeparateAo(directives.shouldUseSeparateAo());
		settings.setBreaksAnisotropy(directives.breaksAnisotropy());
		settings.setVoxelizeLightBlocks(directives.shouldVoxelizeLightBlocks());
		settings.setSeparateEntityDraws(directives.shouldUseSeparateEntityDraws());
		if (blockStateIds != null) {
			settings.setBlockStateIds(blockStateIds);
			settings.setBlockTypeIds(blockTypeIds);
		}
	}

	@Override
	public void beginLevelRendering() {
		checkAlive();
		// Tags are available only once the client has joined a world. Keep each
		// dimension's mappings so returning to a cached pipeline restores them.
		if (blockStateIds == null) {
			blockStateIds = BlockMaterialMapping.createBlockStateIdMap(pack.getIdMap().getBlockProperties(), pack.getIdMap().getTagEntries());
			blockTypeIds = BlockMaterialMapping.createBlockTypeMap(pack.getIdMap().getBlockRenderTypeMap());
		}
		applyMaterialSettings();
		if (WorldRenderingSettings.INSTANCE.isReloadRequired()) {
			if (Minecraft.getInstance().levelExtractor != null) Minecraft.getInstance().levelExtractor.allChanged();
			WorldRenderingSettings.INSTANCE.clearReloadRequired();
		}
		phaseState.reset();
		renderingWorld = true;
		mainBound = true;
		beforeTranslucents = true;
		handStarted = false;
		shadowsRendered = false;
		frameUpdateNotifier.onNewFrame();
		framePasses.beginFrame();
	}

	public void setShadowRenderer(NativeShadowPass renderer) {
		checkAlive();
		if (shadowRenderer != null && shadowRenderer != renderer) shadowRenderer.close();
		shadowRenderer = Objects.requireNonNull(renderer, "renderer");
	}

	@Override
	public void renderShadows(LevelRendererAccessor worldRenderer, Camera playerCamera, CameraRenderState renderState) {
		checkAlive();
		if (!usesShadowMaps()) {
			framePasses.afterShadows();
			return;
		}
		if (shadowRenderer == null) {
			throw new IllegalStateException("This shader pack requires native Vulkan shadow rendering, but no shadow renderer is installed");
		}
		shadowRenderer.render(worldRenderer, playerCamera, renderState);
		shadowsRendered = true;
		framePasses.afterShadows();
	}

	public boolean usesShadowMaps() {
		return directives.getShadowDirectives().isShadowEnabled().orElse(true)
			&& (programSet.get(ProgramId.Shadow).isPresent() || directives.getShadowDirectives().isShadowEnabled().orElse(false));
	}

	@Override
	public void beginHand() {
		checkAlive();
		if (renderingWorld && !handStarted) {
			framePasses.beforeHand();
			handStarted = true;
		}
	}

	@Override
	public void beginTranslucents() {
		checkAlive();
		if (renderingWorld && beforeTranslucents) {
			beginHand();
			framePasses.renderSolidHand();
			framePasses.beforeTranslucents();
			beforeTranslucents = false;
		}
	}

	@Override
	public void finalizeLevelRendering() {
		checkAlive();
		if (!renderingWorld) return;
		try {
			phaseState.reset();
			framePasses.finishWorld();
		} finally {
			renderingWorld = false;
			mainBound = false;
			albedoTexture = null;
		}
	}

	@Override
	public void finalizeGameRendering() {
		checkAlive();
		framePasses.finishGame();
	}

	@Override
	public void destroy() {
		if (destroyed) return;
		destroyed = true;
		renderingWorld = false;
		phaseState.reset();
		albedoTexture = null;
		try {
			if (shadowRenderer != null) shadowRenderer.close();
		} finally {
			try {
				if (horizonRenderer != null) horizonRenderer.destroy();
			} finally {
				framePasses.close();
			}
		}
	}

	private void checkAlive() {
		if (destroyed) throw new IllegalStateException("Tried to use a destroyed native Vulkan world pipeline");
	}

	public ProgramSet getProgramSet() { return programSet; }
	public PackDirectives getPackDirectives() { return directives; }
	public IrisVulkanPhaseContext.State phaseState() { return phaseState; }
	public boolean isRenderingWorld() { return renderingWorld; }
	public boolean isBeforeTranslucents() { return beforeTranslucents; }
	public boolean isHandStarted() { return handStarted; }
	public boolean shouldOverrideShaders() { return renderingWorld && mainBound; }
	public boolean skipAllRendering() { return directives.skipAllRendering(); }
	public GpuTextureView getAlbedoTexture() { return albedoTexture; }
	@Override public WorldRenderingPhase getPhase() { return phaseState.getPhase(); }
	@Override public void setPhase(WorldRenderingPhase phase) { phaseState.setPhase(phase); }
	@Override public void setOverridePhase(WorldRenderingPhase phase) { phaseState.setOverridePhase(phase); }
	@Override public FrameUpdateNotifier getFrameUpdateNotifier() { return frameUpdateNotifier; }
	@Override public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() { return directives.getTextureMap(); }
	@Override public void onSetAlbedoTex(GpuTextureView texture) { albedoTexture = texture; }
	// These legacy integer getters cannot represent a Vulkan image. Native
	// bindings use GpuTextureView, including getAlbedoTexture(), instead.
	@Override public int getAlbedoTex() { return 0; }
	@Override public int getCurrentNormalTexture() { return 0; }
	@Override public int getCurrentSpecularTexture() { return 0; }
	@Override public void setIsMainBound(boolean bound) { mainBound = bound; }
	@Override
	public void onBeginClear() {
		checkAlive();
		var level = Minecraft.getInstance().level;
		if (!renderingWorld || level == null || !shouldRenderSkyDisc()) return;
		var dimension = level.dimensionType();
		if (dimension.skybox() != DimensionType.Skybox.OVERWORLD && !dimension.hasSkyLight()) return;
		if (horizonRenderer == null) horizonRenderer = new HorizonRenderer();
		var fog = CapturedRenderingState.INSTANCE.getFogColor();
		// The cone supplies geometry below the vanilla upper sky disc. Alpha one
		// is required for the pack's sky composition and reflection passes.
		try (var ignored = phaseState.enter(WorldRenderingPhase.SKY)) {
			horizonRenderer.renderHorizon(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()),
				CapturedRenderingState.INSTANCE.getGbufferProjection(), new Vector4f((float) fog.x, (float) fog.y, (float) fog.z, 1F));
		}
	}
	@Override public boolean shouldDisableVanillaEntityShadows() {
		// Entity features are submitted before this frame's shadow pass executes.
		return shadowRenderer != null && usesShadowMaps();
	}
	@Override public boolean shouldDisableDirectionalShading() { return !directives.isOldLighting(); }
	@Override public boolean shouldDisableFrustumCulling() { return !directives.shouldUseFrustumCulling(); }
	@Override public boolean shouldDisableOcclusionCulling() { return !directives.shouldUseOcclusionCulling(); }
	@Override public CloudSetting getCloudSetting() { return directives.getCloudSetting(); }
	@Override public boolean shouldRenderUnderwaterOverlay() { return directives.underwaterOverlay(); }
	@Override public boolean shouldRenderVignette() { return directives.vignette(); }
	@Override public boolean shouldRenderSun() { return directives.shouldRenderSun(); }
	@Override public boolean shouldRenderWeather() { return directives.shouldRenderWeather(); }
	@Override public boolean shouldRenderWeatherParticles() { return directives.shouldRenderWeatherParticles(); }
	@Override public boolean shouldRenderMoon() { return directives.shouldRenderMoon(); }
	@Override public boolean shouldRenderStars() { return directives.shouldRenderStars(); }
	@Override public boolean shouldRenderSkyDisc() { return directives.shouldRenderSkyDisc(); }
	@Override public boolean shouldWriteRainAndSnowToDepthBuffer() { return directives.rainDepth(); }
	@Override public ParticleRenderingSettings getParticleRenderingSettings() { return particles; }
	@Override public boolean allowConcurrentCompute() { return false; }
	/** Pack-selected features, not a claim that every backend feature is implemented. */
	@Override public boolean hasFeature(FeatureFlags flag) { return pack.hasFeature(flag); }
	@Override public float getSunPathRotation() { return directives.getSunPathRotation(); }
	@Override public DHCompat getDHCompat() { return null; }
	@Override public boolean supportsEndFlash() { return directives.supportsEndFlash(); }

	@Override
	public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
		var shadows = directives.getShadowDirectives();
		if (!usesShadowMaps() || !shadows.isDistanceRenderMulExplicit()) return OptionalInt.empty();
		return OptionalInt.of(shadows.getDistanceRenderMul() < 0 ? -1
			: ((int) (shadows.getDistance() * shadows.getDistanceRenderMul()) + 15) / 16);
	}

	@Override
	public void addDebugText(DebugScreenDisplayer messages) {
		messages.addLine("[Iris] Native Vulkan world pipeline");
		messages.addLine("[Iris] Shadow maps: " + (!usesShadowMaps() ? "not used" : shadowsRendered ? "rendered" : "pending"));
	}

	/** All callbacks execute on the render thread and must perform native GPU work. */
	public interface FramePasses extends AutoCloseable {
		void beginFrame();
		default void afterShadows() { }
		void beforeHand();
		default void renderSolidHand() { }
		void beforeTranslucents();
		void finishWorld();
		void finishGame();
		@Override void close();
	}

	/** The implementation must render real geometry and restore camera/target state. */
	public interface NativeShadowPass extends AutoCloseable {
		void render(LevelRendererAccessor worldRenderer, Camera playerCamera, CameraRenderState renderState);
		@Override void close();
	}
}
