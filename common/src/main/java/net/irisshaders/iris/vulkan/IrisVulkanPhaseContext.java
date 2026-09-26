package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Objects;

/** Scoped phase tracking around actual command submission, including nested hand draws. */
public final class IrisVulkanPhaseContext {
	private static Matrix4fc handProjection;
	private static Matrix4fc handModelView;
	private IrisVulkanPhaseContext() { }

	/** The native reverse-Z projection for the hand, before shader-pack clip conversion. */
	public static Matrix4fc handProjection() { return handProjection; }
	public static Matrix4fc handModelView() { return handModelView; }
	public static void captureHandMatrices(Matrix4fc projection, Matrix4fc modelView) {
		handProjection = new Matrix4f(projection);
		handModelView = new Matrix4f(modelView);
	}
	public static void clearHandMatrices() {
		handProjection = null;
		handModelView = null;
	}

	public static ShaderKey mapPipeline(RenderPipeline renderPipeline) {
		ShaderKey base = IrisPipelines.getPipeline(null, renderPipeline);
		NativeVulkanWorldRenderingPipeline pipeline = pipeline();
		return mapShaderKey(base, pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase());
	}

	/** Preserve each shader's vertex layout and lighting inputs while changing its pack program family. */
	public static ShaderKey mapShaderKey(ShaderKey base, WorldRenderingPhase phase) {
		if (base == null || base.isShadow()) return base;
		boolean hand = phase == WorldRenderingPhase.HAND_SOLID || phase == WorldRenderingPhase.HAND_TRANSLUCENT;
		boolean translucentHand = phase == WorldRenderingPhase.HAND_TRANSLUCENT;
		if (hand) {
			return switch (base) {
				case ENTITIES_SOLID_GLINT -> translucentHand ? ShaderKey.HAND_TRANSLUCENT_GLINT : ShaderKey.HAND_CUTOUT_GLINT;
				case ENTITIES_CUTOUT_GLINT, ENTITIES_TRANSLUCENT_GLINT -> translucentHand ? ShaderKey.HAND_TRANSLUCENT_GLINT_DIFFUSE : ShaderKey.HAND_CUTOUT_GLINT_DIFFUSE;
				case ENTITIES_CUTOUT_GLINT_SPECIAL, ENTITIES_TRANSLUCENT_GLINT_SPECIAL -> translucentHand ? ShaderKey.HAND_TRANSLUCENT_GLINT_SPECIAL : ShaderKey.HAND_CUTOUT_GLINT_SPECIAL;
				case ENTITIES_CUTOUT_GLINT_ARMOR -> translucentHand ? ShaderKey.HAND_TRANSLUCENT_GLINT_ARMOR : ShaderKey.HAND_CUTOUT_GLINT_ARMOR;
				case ENTITIES_SOLID, ENTITIES_ALPHA, ENTITIES_CUTOUT, BLOCK_ENTITY -> translucentHand ? ShaderKey.HAND_TRANSLUCENT : ShaderKey.HAND_CUTOUT;
				case ENTITIES_SOLID_DIFFUSE, ENTITIES_CUTOUT_DIFFUSE, ENTITIES_TRANSLUCENT, BLOCK_ENTITY_DIFFUSE, BE_TRANSLUCENT -> translucentHand ? ShaderKey.HAND_WATER_DIFFUSE : ShaderKey.HAND_CUTOUT_DIFFUSE;
				case ENTITIES_SOLID_BRIGHT, BLOCK_ENTITY_BRIGHT -> translucentHand ? ShaderKey.HAND_WATER_BRIGHT : ShaderKey.HAND_CUTOUT_BRIGHT;
				case TEXT, TEXT_BE -> translucentHand ? ShaderKey.HAND_TEXT_TRANSLUCENT : ShaderKey.HAND_TEXT;
				case TEXT_INTENSITY, TEXT_INTENSITY_BE -> ShaderKey.HAND_TEXT_INTENSITY;
				default -> base;
			};
		}
		if (phase == WorldRenderingPhase.BLOCK_ENTITIES) {
			return switch (base) {
				case ENTITIES_SOLID, ENTITIES_ALPHA, ENTITIES_CUTOUT -> ShaderKey.BLOCK_ENTITY;
				case ENTITIES_SOLID_DIFFUSE, ENTITIES_CUTOUT_DIFFUSE -> ShaderKey.BLOCK_ENTITY_DIFFUSE;
				case ENTITIES_SOLID_BRIGHT -> ShaderKey.BLOCK_ENTITY_BRIGHT;
				case ENTITIES_TRANSLUCENT -> ShaderKey.BE_TRANSLUCENT;
				case TEXT -> ShaderKey.TEXT_BE;
				case TEXT_INTENSITY -> ShaderKey.TEXT_INTENSITY_BE;
				default -> base;
			};
		}
		return base;
	}

	public static NativeVulkanWorldRenderingPipeline pipeline() {
		return Iris.getPipelineManager().getPipelineNullable() instanceof NativeVulkanWorldRenderingPipeline pipeline ? pipeline : null;
	}

	public static Scope enter(WorldRenderingPhase phase) {
		NativeVulkanWorldRenderingPipeline pipeline = pipeline();
		return pipeline == null || !pipeline.isRenderingWorld() ? Scope.INACTIVE : pipeline.phaseState().enter(phase);
	}

	public static WorldRenderingPhase featurePhase(boolean translucent) {
		NativeVulkanWorldRenderingPipeline pipeline = pipeline();
		return featurePhase(pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase(), translucent);
	}

	public static WorldRenderingPhase featurePhase(WorldRenderingPhase current, boolean translucent) {
		return current == WorldRenderingPhase.HAND_SOLID || current == WorldRenderingPhase.HAND_TRANSLUCENT
			? (translucent ? WorldRenderingPhase.HAND_TRANSLUCENT : WorldRenderingPhase.HAND_SOLID)
			: WorldRenderingPhase.ENTITIES;
	}

	public static final class State {
		private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
		private WorldRenderingPhase override;
		private long generation;

		public WorldRenderingPhase getPhase() { return override == null ? phase : override; }
		public void setPhase(WorldRenderingPhase phase) { this.phase = Objects.requireNonNull(phase, "phase"); }
		public void setOverridePhase(WorldRenderingPhase phase) { override = phase; }
		public void reset() {
			phase = WorldRenderingPhase.NONE;
			override = null;
			generation++;
		}
		public Scope enter(WorldRenderingPhase next) {
			Scope scope = new Scope(this, phase, generation);
			setPhase(next);
			return scope;
		}
	}

	public static final class Scope implements AutoCloseable {
		private static final Scope INACTIVE = new Scope(null, WorldRenderingPhase.NONE, 0);
		private final State state;
		private final WorldRenderingPhase previous;
		private final long generation;
		private boolean closed;
		private Scope(State state, WorldRenderingPhase previous, long generation) {
			this.state = state;
			this.previous = previous;
			this.generation = generation;
		}
		@Override public void close() {
			if (!closed && state != null && generation == state.generation) state.phase = previous;
			closed = true;
		}
	}
}
