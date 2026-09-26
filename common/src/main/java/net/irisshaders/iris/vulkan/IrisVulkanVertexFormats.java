package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.vertices.BufferBuilderPolygonView;
import net.irisshaders.iris.vertices.NormalHelper;
import net.irisshaders.iris.vertices.ImmediateState;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Stable native world layouts shared by staged producers and compiled overrides. */
public final class IrisVulkanVertexFormats {

	public static final VertexFormat BLOCK_WITH_NORMAL = appendNormal(DefaultVertexFormat.BLOCK);
	public static final VertexFormat GLYPH_WITH_NORMAL = appendNormal(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR);
	// See-through text has no lightmap: preserve that absence and all original offsets.
	public static final VertexFormat GLYPH_SEE_THROUGH_WITH_NORMAL = appendNormal(DefaultVertexFormat.POSITION_TEX_COLOR);
	private static final Layout BLOCK = new Layout(DefaultVertexFormat.BLOCK, BLOCK_WITH_NORMAL, false);
	private static final Layout GLYPH = new Layout(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR, GLYPH_WITH_NORMAL, true);
	private static final Layout GLYPH_SEE_THROUGH = new Layout(DefaultVertexFormat.POSITION_TEX_COLOR, GLYPH_SEE_THROUGH_WITH_NORMAL, true);

	private IrisVulkanVertexFormats() { }

	public static boolean worldProducerActive() {
		if (ImmediateState.bypass) return false;
		var manager = Iris.getPipelineManager();
		return manager != null && manager.getPipelineNullable() instanceof NativeVulkanWorldRenderingPipeline pipeline
			&& (pipeline.shouldOverrideShaders() || IrisVulkanShadowRenderer.active());
	}

	/** Called only when creating a native shader override, including early warmup. */
	public static VertexFormat forShader(VertexFormat original, PrimitiveTopology topology, ShaderKey key) {
		if (original == null || key == null || key.patch == Patch.SODIUM || !surface(topology)) return original;
		if (original.equals(DefaultVertexFormat.BLOCK)) return BLOCK_WITH_NORMAL;
		if (!glyphShader(key)) return original;
		if (original.equals(DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)) return GLYPH_WITH_NORMAL;
		if (original.equals(DefaultVertexFormat.POSITION_TEX_COLOR)) return GLYPH_SEE_THROUGH_WITH_NORMAL;
		return original;
	}

	private static boolean glyphShader(ShaderKey key) {
		// ShaderKey.isText() also matches TEXTURED and SKY_TEXTURED. Those use
		// direct vanilla vertex buffers and must keep their original layout.
		return switch (key) {
			case TEXT, TEXT_INTENSITY, TEXT_BE, TEXT_INTENSITY_BE, HAND_TEXT,
				HAND_TEXT_TRANSLUCENT, HAND_TEXT_INTENSITY, SHADOW_TEXT, SHADOW_TEXT_INTENSITY -> true;
			default -> false;
		};
	}

	private static boolean surface(PrimitiveTopology topology) {
		return topology == PrimitiveTopology.QUADS || topology == PrimitiveTopology.TRIANGLES;
	}

	public static boolean managed(VertexFormat format) { return layout(format) != null; }

	public static NormalWriter writer(VertexFormat format, PrimitiveTopology topology) {
		Layout layout = layout(format);
		return layout == null || !surface(topology) ? null : new NormalWriter(layout, topology == PrimitiveTopology.QUADS ? 4 : 3);
	}

	/** Null means the ordinary Sodium serializer must handle this source instead. */
	public static CopyPlan copyPlan(VertexFormat source, VertexFormat destination) {
		Layout layout = layout(destination);
		if (layout == null) return null;
		CopyPlan result = layout.copies.computeIfAbsent(source, format -> makeCopyPlan(format, layout));
		return result == CopyPlan.UNSUPPORTED ? null : result;
	}

	private static Layout layout(VertexFormat format) {
		if (format == BLOCK_WITH_NORMAL) return BLOCK;
		if (format == GLYPH_WITH_NORMAL) return GLYPH;
		if (format == GLYPH_SEE_THROUGH_WITH_NORMAL) return GLYPH_SEE_THROUGH;
		return null;
	}

	private static VertexFormat appendNormal(VertexFormat original) {
		var builder = VertexFormat.builder(original.getStepRate());
		var elements = original.getElements();
		for (int index = 0; index < elements.size(); index++) {
			var element = elements.get(index);
			int next = index + 1 < elements.size() ? elements.get(index + 1).offset() : original.getVertexSize();
			builder.addAttribute(element.name(), next - element.offset(), element.format());
		}
		return builder.addAttribute("Normal", 4, GpuFormat.RGB8_SNORM).build();
	}

	private static CopyPlan makeCopyPlan(VertexFormat source, Layout destination) {
		List<Transfer> transfers = new ArrayList<>();
		for (var target : destination.base.getElements()) {
			var from = source.getElement(target.name());
			if (from == null || from.format() != target.format()) return CopyPlan.UNSUPPORTED;
			int size = target.format().blockSize();
			if (!transfers.isEmpty()) {
				Transfer previous = transfers.getLast();
				if (previous.source + previous.size == from.offset() && previous.destination + previous.size == target.offset()) {
					transfers.set(transfers.size() - 1, new Transfer(previous.source, previous.destination, previous.size + size));
					continue;
				}
			}
			transfers.add(new Transfer(from.offset(), target.offset(), size));
		}
		var normal = source.getElement("Normal");
		if (normal != null && normal.format() != GpuFormat.RGB8_SNORM && normal.format() != GpuFormat.RGBA8_SNORM) return CopyPlan.UNSUPPORTED;
		return new CopyPlan(source.getVertexSize(), destination.format.getVertexSize(), destination.normalOffset,
			normal == null ? -1 : normal.offset(), transfers.toArray(Transfer[]::new));
	}

	private static final class Layout {
		private final VertexFormat base;
		private final VertexFormat format;
		private final boolean glyph;
		private final int normalOffset;
		private final Map<VertexFormat, CopyPlan> copies = new ConcurrentHashMap<>();
		private Layout(VertexFormat base, VertexFormat format, boolean glyph) {
			this.base = base; this.format = format; this.glyph = glyph; this.normalOffset = format.getElement("Normal").offset();
		}
	}
	private record Transfer(int source, int destination, int size) { }

	/** Copies only named attributes; glyph28 -> see-through28 deliberately drops UV2. */
	public static final class CopyPlan {
		private static final CopyPlan UNSUPPORTED = new CopyPlan(0, 0, 0, -1, new Transfer[0]);
		private final int sourceStride, destinationStride, normalOffset, sourceNormalOffset;
		private final Transfer[] transfers;
		private CopyPlan(int sourceStride, int destinationStride, int normalOffset, int sourceNormalOffset, Transfer[] transfers) {
			this.sourceStride = sourceStride; this.destinationStride = destinationStride; this.normalOffset = normalOffset;
			this.sourceNormalOffset = sourceNormalOffset; this.transfers = transfers;
		}
		public boolean hasSourceNormal() { return sourceNormalOffset >= 0; }
		public void copy(long source, long destination, int count) {
			for (int vertex = 0; vertex < count; vertex++) {
				long from = source + (long) vertex * sourceStride, to = destination + (long) vertex * destinationStride;
				for (Transfer transfer : transfers) MemoryUtil.memCopy(from + transfer.source, to + transfer.destination, transfer.size);
				// A temporary unit normal also covers zero-area/incomplete primitives,
				// which have no defined geometric normal and rasterize no surface.
				writeNormal(to + normalOffset, 0, 0, 127);
				if (sourceNormalOffset >= 0) MemoryUtil.memCopy(from + sourceNormalOffset, to + normalOffset, 3);
			}
		}
	}

	/** Records buffer-relative offsets so reserve/reallocation cannot invalidate a partial quad. */
	public static final class NormalWriter {
		private final Layout layout;
		private final int primitiveSize;
		private final long[] offsets = new long[4];
		private final boolean[] supplied = new boolean[4];
		private final BufferBuilderPolygonView polygon = new BufferBuilderPolygonView();
		private final Vector3f normal = new Vector3f();
		private int pending;
		private int completedVertices;
		private NormalWriter(Layout layout, int primitiveSize) { this.layout = layout; this.primitiveSize = primitiveSize; }
		public int completedVertices() { return completedVertices; }
		public int stride() { return layout.format.getVertexSize(); }
		public void record(long bufferBase, long vertex, boolean sourceSuppliedNormal) {
			offsets[pending] = vertex - bufferBase;
			long location = vertex + layout.normalOffset;
			supplied[pending] = sourceSuppliedNormal && (MemoryUtil.memGetByte(location) != 0
				|| MemoryUtil.memGetByte(location + 1) != 0 || MemoryUtil.memGetByte(location + 2) != 0);
			pending++; completedVertices++;
			if (pending != primitiveSize) return;
			boolean needsNormal = layout.glyph;
			for (int index = 0; index < primitiveSize; index++) needsNormal |= !supplied[index];
			if (needsNormal) {
				if (primitiveSize == 3) offsets[3] = offsets[0];
				polygon.setup(bufferBase, offsets, layout.format.getElement("Position").offset(), layout.format.getElement("UV0").offset());
				NormalHelper.computeFaceNormal(normal, polygon);
				if (!normal.isFinite()) normal.set(0, 0, 1); // Only degenerate geometry has no face normal.
				int x = (int) (normal.x * 127), y = (int) (normal.y * 127), z = (int) (normal.z * 127);
				for (int index = 0; index < primitiveSize; index++) {
					if (layout.glyph || !supplied[index]) writeNormal(bufferBase + offsets[index] + layout.normalOffset, x, y, z);
				}
			}
			pending = 0;
		}
	}

	private static void writeNormal(long address, int x, int y, int z) {
		MemoryUtil.memPutByte(address, (byte) x); MemoryUtil.memPutByte(address + 1, (byte) y);
		MemoryUtil.memPutByte(address + 2, (byte) z); MemoryUtil.memPutByte(address + 3, (byte) 0);
	}
}
