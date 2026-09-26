package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Material IDs are captured at submission and restored for each native draw batch. */
public final class IrisVulkanEntityContext {
	public static final Material EMPTY = new Material(0, 0, 0, false);
	private static final NamespacedId ENTITY_FLAME = new NamespacedId("minecraft", "entity_flame");
	private static final NamespacedId NAME_TAG = new NamespacedId("minecraft", "name_tag");
	private static final ThreadLocal<Material> CURRENT = ThreadLocal.withInitial(() -> EMPTY);
	private static final ThreadLocal<PreparedTransform> PREPARED_TRANSFORM = new ThreadLocal<>();
	private static final ThreadLocal<Matrix4fc> DRAW_MODEL_VIEW = new ThreadLocal<>();
	private IrisVulkanEntityContext() { }
	public static Material current() { return CURRENT.get(); }
	public static Scope enter(Material material) { return new Scope(material, false); }
	public static Scope enterDraw(Material material) { return new Scope(material, true); }
	public static Matrix4fc currentDrawModelView() { return DRAW_MODEL_VIEW.get(); }
	public static ModelViewScope enterModelView(Matrix4fc modelView) { return new ModelViewScope(modelView); }
	public static void capturePreparedModelView(GpuBufferSlice buffer, Matrix4fc modelView) {
		PREPARED_TRANSFORM.set(new PreparedTransform(buffer, new Matrix4f(modelView)));
	}
	public static Matrix4fc takePreparedModelView(GpuBufferSlice buffer) {
		PreparedTransform prepared = PREPARED_TRANSFORM.get();
		PREPARED_TRANSFORM.remove();
		return prepared != null && prepared.buffer().equals(buffer) ? prepared.modelView() : null;
	}
	public static Material fromSubmit(Object submit) {
		NamespacedId pseudoEntity = submit instanceof FlameFeatureRenderer.Submit ? ENTITY_FLAME : null;
		if (pseudoEntity != null) {
			// Resolve feature materials before deferred grouping, independently of
			// the entity that submitted the flame or name tag.
			return pseudoEntity(pseudoEntity);
		}
		return submit instanceof MaterialSubmit stored ? stored.iris$material() : EMPTY;
	}

	/** 26.3 builds name tags as ordinary text submissions; capture their ID at creation. */
	public static Material nameTagMaterial() { return pseudoEntity(NAME_TAG); }
	private static Material pseudoEntity(NamespacedId pseudoEntity) {
		var ids = WorldRenderingSettings.INSTANCE.getEntityIds();
		int id = ids == null || !ids.containsKey(pseudoEntity) ? 0 : ids.getInt(pseudoEntity);
		return new Material(id, 0, 0, false);
	}

	public record Material(int entityId, int blockEntityId, int itemId, boolean blockEntity) { }
	public interface MaterialSubmit { Material iris$material(); }
	public interface MaterialDraw { Material iris$material(); }
	private record PreparedTransform(GpuBufferSlice buffer, Matrix4fc modelView) { }

	public static final class ModelViewScope implements AutoCloseable {
		private final Matrix4fc previous = DRAW_MODEL_VIEW.get();
		private boolean closed;
		private ModelViewScope(Matrix4fc modelView) { DRAW_MODEL_VIEW.set(modelView); }
		@Override public void close() {
			if (closed) return;
			closed = true;
			if (previous == null) DRAW_MODEL_VIEW.remove(); else DRAW_MODEL_VIEW.set(previous);
		}
	}

	public static final class Scope implements AutoCloseable {
		private final Material previous;
		private final int entityId;
		private final int blockEntityId;
		private final int itemId;
		private final IrisVulkanPhaseContext.Scope phase;
		private boolean closed;
		private Scope(Material material, boolean drawing) {
			previous = CURRENT.get();
			CURRENT.set(material);
			var state = CapturedRenderingState.INSTANCE;
			entityId = state.getCurrentRenderedEntity();
			blockEntityId = state.getCurrentRenderedBlockEntity();
			itemId = state.getCurrentRenderedItem();
			state.setCurrentEntity(material.entityId());
			state.setCurrentBlockEntity(material.blockEntityId());
			state.setCurrentRenderedItem(material.itemId());
			var pipeline = drawing ? IrisVulkanPhaseContext.pipeline() : null;
			WorldRenderingPhase current = pipeline == null ? WorldRenderingPhase.NONE : pipeline.getPhase();
			phase = pipeline != null && material.blockEntity() && current != WorldRenderingPhase.HAND_SOLID && current != WorldRenderingPhase.HAND_TRANSLUCENT
				? IrisVulkanPhaseContext.enter(WorldRenderingPhase.BLOCK_ENTITIES) : null;
		}
		@Override public void close() {
			if (closed) return;
			closed = true;
			if (phase != null) phase.close();
			CURRENT.set(previous);
			var state = CapturedRenderingState.INSTANCE;
			state.setCurrentEntity(entityId);
			state.setCurrentBlockEntity(blockEntityId);
			state.setCurrentRenderedItem(itemId);
		}
	}
}
