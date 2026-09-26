package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.vulkan.IrisVulkanVertexFormats;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BufferBuilder.class)
public abstract class VKOnly_MixinBufferBuilder_Normals {
	@Shadow @Final private ByteBufferBuilder buffer;
	@Shadow @Final private VertexFormat format;
	@Shadow private int vertices;
	@Shadow private long vertexPointer;
	@Shadow private int elementsToFill;
	@Shadow private void endLastVertex() { throw new AssertionError(); }
	@Shadow private void ensureBuilding() { throw new AssertionError(); }
	@Unique private IrisVulkanVertexFormats.NormalWriter iris$normals;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void iris$trackNormalLayout(ByteBufferBuilder buffer, PrimitiveTopology topology, VertexFormat format, CallbackInfo ci) {
		// StagedVertexBuffer already captured this exact format. Never alter it here.
		iris$normals = IrisVulkanVertexFormats.writer(this.format, topology);
	}

	@Inject(method = "endLastVertex", at = @At("HEAD"))
	private void iris$finishNormalVertex(CallbackInfo ci) {
		if (iris$normals == null || vertexPointer == -1 || vertices <= iris$normals.completedVertices()) return;
		// 26.3 inserts UV3 before Normal in BufferBuilder's element-name table.
		boolean supplied = (elementsToFill & (1 << 6)) == 0;
		if (!supplied) ((VertexConsumer) this).setNormal(0, 0, 1);
		long base = ((VKOnly_ByteBufferBuilderNormalAccess) buffer).iris$normalBufferBase();
		iris$normals.record(base, vertexPointer, supplied);
	}

	/** Sodium's serializer cannot invent a missing Normal; handle only our managed layouts. */
	@Inject(method = "push", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void iris$copyNormalVertices(MemoryStack stack, long source, int count, VertexFormat sourceFormat, CallbackInfo ci) {
		if (iris$normals == null) return;
		var copy = IrisVulkanVertexFormats.copyPlan(sourceFormat, format);
		if (copy == null) return;
		ensureBuilding();
		if (count < 0) throw new IllegalArgumentException("Negative vertex count");
		if (count == 0) { ci.cancel(); return; }
		if (vertices > iris$normals.completedVertices()) endLastVertex();
		int stride = iris$normals.stride();
		int byteSize = Math.multiplyExact(count, stride);
		long destination = buffer.reserve(byteSize);
		long base = ((VKOnly_ByteBufferBuilderNormalAccess) buffer).iris$normalBufferBase();
		copy.copy(source, destination, count);
		for (int index = 0; index < count; index++) iris$normals.record(base, destination + (long) index * stride, copy.hasSourceNormal());
		vertices = Math.addExact(vertices, count);
		vertexPointer = destination + byteSize - stride;
		elementsToFill = 0;
		ci.cancel();
	}
}
