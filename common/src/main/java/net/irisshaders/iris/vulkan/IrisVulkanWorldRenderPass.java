package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPassAccessor;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.lwjgl.PointerBuffer;

import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Splits a vanilla world pass when a material changes its shader-pack outputs.
 * Reopening through CommandEncoder preserves the backend's image transitions,
 * attachment validation, and pass lifetime instead of issuing raw Vulkan calls.
 */
public final class IrisVulkanWorldRenderPass extends FrontendRenderPass {
    private static int reopening;
    private final CommandEncoder encoder;
    private final RenderPassDescriptor original;
    private RenderPass delegate;
    private int[] outputs = new int[0];
    private boolean closed;
    private final Map<String, GpuBufferSlice> uniforms = new LinkedHashMap<>();
    private final Map<String, Texture> textures = new LinkedHashMap<>();
    private final Map<Integer, GpuBufferSlice> vertices = new LinkedHashMap<>();
    private final List<Supplier<String>> debugGroups = new ArrayList<>();
    private GpuBuffer indices;
    private IndexType indexType;
    private int[] scissor;
    private CompiledRenderPipeline iris$boundPipeline;
    private ByteBuffer iris$constants;

    private IrisVulkanWorldRenderPass(CommandEncoder encoder, RenderPassDescriptor original, RenderPass delegate) {
        super(((VKOnly_RenderPassAccessor) delegate).iris$getBackend(),
                ((VKOnly_RenderPassAccessor) delegate).iris$getDevice(), original.colorAttachments(), original.depthAttachment() != null,
                () -> {}, ((VKOnly_RenderPassAccessor) delegate).iris$getRenderArea());
        this.encoder = encoder;
        this.original = copyDescriptor(original);
        this.delegate = delegate;
    }

    public static boolean isReopening() { return reopening != 0; }

    /** Stage copies and fullscreen work cannot execute inside a live backend pass. */
    public static void runOutsidePass(RenderPass pass, Runnable action) {
        if (!(pass instanceof IrisVulkanWorldRenderPass world)) {
            throw new IllegalStateException("Native shader stage boundary requires the routed world render pass");
        }
        world.ensureOpen();
        for (int i = world.debugGroups.size() - 1; i >= 0; --i) world.delegate.popDebugGroup();
        world.delegate.close();
        try {
            action.run();
            world.reopen();
            if (world.iris$boundPipeline != null) world.delegate.setPipeline(world.iris$boundPipeline);
            if (world.iris$constants != null) world.delegate.pushConstants(world.iris$constants.duplicate());
        } catch (RuntimeException | Error failure) {
            world.closed = true;
            throw failure;
        }
    }

    private void reopen() {
        RenderPassDescriptor descriptor = copyDescriptor(original);
        if (outputs.length != 0) descriptor = IrisVulkanGbufferTargets.routeWorldPass(descriptor, outputs);
        reopening++;
        try { delegate = encoder.createRenderPass(descriptor); }
        finally { reopening--; }
        ((VKOnly_RenderPassAccessor)(Object)this).iris$setBackend(((VKOnly_RenderPassAccessor)delegate).iris$getBackend());
        for (Supplier<String> group : debugGroups) delegate.pushDebugGroup(group);
        for (var entry : uniforms.entrySet()) delegate.setUniform(entry.getKey(), entry.getValue());
        for (var entry : textures.entrySet()) delegate.setUniform(entry.getKey(), entry.getValue().view(), entry.getValue().sampler());
        for (var entry : vertices.entrySet()) delegate.setVertexBuffer(entry.getKey(), entry.getValue());
        if (indices != null) delegate.setIndexBuffer(indices, indexType);
        if (scissor != null) delegate.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
    }

    public static RenderPass wrap(CommandEncoder encoder, RenderPassDescriptor descriptor, RenderPass pass) {
        if (reopening != 0 || !IrisVulkanGbufferTargets.isWorldPass(descriptor)) return pass;
        return new IrisVulkanWorldRenderPass(encoder, descriptor, pass);
    }

    private static RenderPassDescriptor copyDescriptor(RenderPassDescriptor source) {
        RenderPassDescriptor.Builder copy = RenderPassDescriptor.builder(source.label());
        for (var attachment : source.colorAttachments()) {
            if (attachment.textureView() == null) copy.withUnusedColorAttachment();
            else copy.withColorAttachment(attachment.textureView());
        }
        if (source.depthAttachment() != null) copy.withDepthAttachment(source.depthAttachment().textureView());
        if (source.renderArea() != null) copy.withRenderArea(source.renderArea());
        return copy.build();
    }

    @Override public void setPipeline(CompiledRenderPipeline pipeline) {
        ensureOpen();
        RenderPipeline descriptorPipeline = IrisNativeVulkan.descriptorFor(pipeline);
        ShaderKey key = descriptorPipeline == null ? null : IrisNativeVulkan.worldShaderKey(descriptorPipeline);
        int[] next = key == null ? new int[0] : IrisNativeVulkan.getDrawBuffers(key);
        if (!Arrays.equals(next, outputs)) {
            // The first real pass has already executed all original attachment
            // clears. Every replacement must LOAD, including a return to vanilla.
            for (int i = debugGroups.size() - 1; i >= 0; --i) delegate.popDebugGroup();
            delegate.close();
            RenderPassDescriptor descriptor = copyDescriptor(original);
            if (next.length != 0) descriptor = IrisVulkanGbufferTargets.routeWorldPass(descriptor, next);
            reopening++;
            try { delegate = encoder.createRenderPass(descriptor); }
            finally { reopening--; }
            // Sodium's native draw context reads RenderPass.backend directly.
            // Keep that facade pointing at the live pass after changing MRTs.
            ((VKOnly_RenderPassAccessor)(Object)this).iris$setBackend(((VKOnly_RenderPassAccessor)delegate).iris$getBackend());
            outputs = next.clone();
            for (Supplier<String> group : debugGroups) delegate.pushDebugGroup(group);
            for (var entry : uniforms.entrySet()) delegate.setUniform(entry.getKey(), entry.getValue());
            for (var entry : textures.entrySet()) delegate.setUniform(entry.getKey(), entry.getValue().view(), entry.getValue().sampler());
            for (var entry : vertices.entrySet()) delegate.setVertexBuffer(entry.getKey(), entry.getValue());
            if (indices != null) delegate.setIndexBuffer(indices, indexType);
            if (scissor != null) delegate.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        }
        delegate.setPipeline(pipeline);
        iris$boundPipeline = pipeline;
        iris$constants = null;
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Native world render pass is closed"); }
    @Override public void pushDebugGroup(Supplier<String> name) { ensureOpen(); debugGroups.add(name); delegate.pushDebugGroup(name); }
    @Override public void popDebugGroup() { ensureOpen(); if (debugGroups.isEmpty()) throw new IllegalStateException("Unbalanced render debug group"); debugGroups.removeLast(); delegate.popDebugGroup(); }
    @Override public void writeTimestamp(GpuQueryPool pool, int index) { delegate.writeTimestamp(pool, index); }
    @Override public void setUniform(String name, GpuTextureView texture, GpuSampler sampler) { textures.put(name, new Texture(texture, sampler)); delegate.setUniform(name, texture, sampler); }
    @Override public void pushConstants(ByteBuffer value) {
        // Push constants often originate on MemoryStack; retain bytes across stage suspension.
        if (iris$constants == null || iris$constants.capacity() < value.remaining()) iris$constants = ByteBuffer.allocateDirect(value.remaining());
        iris$constants.clear().put(value.duplicate()).flip();
        delegate.pushConstants(value);
    }
    @Override public void setUniform(String name, GpuBuffer buffer) { setUniform(name, buffer.slice()); }
    @Override public void setUniform(String name, GpuBufferSlice buffer) { uniforms.put(name, buffer); delegate.setUniform(name, buffer); }
    @Override public void enableScissor(int x, int y, int width, int height) { scissor = new int[]{x,y,width,height}; delegate.enableScissor(x,y,width,height); }
    @Override public void disableScissor() { scissor = null; delegate.disableScissor(); }
    @Override public void setVertexBuffer(int slot, GpuBufferSlice buffer) { vertices.put(slot, buffer); delegate.setVertexBuffer(slot, buffer); }
    @Override public void setIndexBuffer(GpuBuffer buffer, IndexType type) { indices=buffer; indexType=type; delegate.setIndexBuffer(buffer,type); }
    @Override public void drawIndexed(int count, int instances, int first, int offset, int firstInstance) { delegate.drawIndexed(count,instances,first,offset,firstInstance); }
    @Override public void multiDrawIndexed(IntBuffer parameters, int first, int offset, int count) { delegate.multiDrawIndexed(parameters,first,offset,count); }
    @Override public void multiDrawIndexed(PointerBuffer first, IntBuffer counts, IntBuffer offsets, int count) { delegate.multiDrawIndexed(first,counts,offsets,count); }
    @Override public void drawIndexedIndirect(GpuBufferSlice commands, int count) { delegate.drawIndexedIndirect(commands,count); }
    @Override public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, GpuBuffer indexBuffer, IndexType type, Collection<String> dynamicUniforms, T data) { delegate.drawMultipleIndexed(draws,indexBuffer,type,dynamicUniforms,data); }
    @Override public void draw(int first, int count, int instances, int firstInstance) { delegate.draw(first,count,instances,firstInstance); }
    @Override public void multiDraw(IntBuffer parameters, int first, int firstInstance, int count) { delegate.multiDraw(parameters,first,firstInstance,count); }
    @Override public void multiDraw(IntBuffer first, IntBuffer counts, int count) { delegate.multiDraw(first,counts,count); }
    @Override public void drawIndirect(GpuBufferSlice commands, int count) { delegate.drawIndirect(commands,count); }
    @Override public void close() { if (!closed) { closed=true; delegate.close(); } }
    private record Texture(GpuTextureView view, GpuSampler sampler) {}
}
