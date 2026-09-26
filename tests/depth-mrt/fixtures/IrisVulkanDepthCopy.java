package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** Supplies legacy forward depth as data, including when packs pass samplers to functions. */
public final class IrisVulkanDepthCopy {
    private static RenderPipeline pipeline;
    private static RenderPipeline opaquePipeline;
    private static RenderPipeline handMergePipeline;
    private static final String VERTEX = """
        #version 450 core
        layout(location=0) in vec3 Position;
        layout(location=1) in vec2 UV0;
        void main() { gl_Position=vec4(Position.xy*2.0-1.0,0.0,1.0); }
        """;
    private static final String FRAGMENT = """
        #version 450 core
        uniform sampler2D iris_NativeDepth;
        layout(location=0) out float iris_ForwardDepth;
        void main() {
            iris_ForwardDepth=1.0-texelFetch(iris_NativeDepth,ivec2(gl_FragCoord.xy),0).r;
        }
        """;
    private static final String OPAQUE_FRAGMENT = """
        #version 450 core
        uniform sampler2D iris_NativeDepth;
        layout(location=0) out float iris_ForwardDepth0;
        layout(location=1) out float iris_ForwardDepth1;
        layout(location=2) out float iris_ForwardDepth2;
        void main() {
            float depth=1.0-texelFetch(iris_NativeDepth,ivec2(gl_FragCoord.xy),0).r;
            iris_ForwardDepth0=depth;
            iris_ForwardDepth1=depth;
            iris_ForwardDepth2=depth;
        }
        """;
    private IrisVulkanDepthCopy() {}

    public static void copy(CommandEncoder encoder, GpuTextureView source, GpuTextureView destination) {
        if (source.getWidth(0) != destination.getWidth(0) || source.getHeight(0) != destination.getHeight(0))
            throw new IllegalArgumentException("Depth snapshot dimensions differ");
        if (pipeline == null) {
            pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/depth/forward_snapshot"))
                .withVertexShader("core/screenquad").withFragmentShader("core/blit_screen")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .build();
            IrisNativeVulkan.registerCustomPipelineSource(pipeline, "depth/forward_snapshot", VERTEX, FRAGMENT, false);
        }
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        try (var pass = encoder.createRenderPass(() -> "Iris forward depth snapshot", destination, Optional.empty())) {
            pass.setPipeline(IrisNativeVulkan.compiledFor(pipeline));
            pass.setUniform("iris_NativeDepth", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, false));
            pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    /** The three legacy depths are equal before translucency, but retain separate later lifetimes. */
    public static void copyOpaque(CommandEncoder encoder, GpuTextureView source, GpuTextureView depth0,
                                  GpuTextureView depth1, GpuTextureView depth2) {
        validateOpaqueTargets(source, depth0, depth1, depth2);
        if (opaquePipeline == null) {
            ColorTargetState state = new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL);
            opaquePipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/depth/opaque_snapshots"))
                .withVertexShader("core/screenquad").withFragmentShader("core/blit_screen")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(0, state).withColorTargetState(1, state).withColorTargetState(2, state)
                .build();
            IrisNativeVulkan.registerCustomPipelineSource(opaquePipeline, "depth/opaque_snapshots", VERTEX, OPAQUE_FRAGMENT, false);
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Iris opaque depth snapshots")
            .withColorAttachment(depth0).withColorAttachment(depth1).withColorAttachment(depth2)
            .withRenderArea(new RenderPass.RenderArea(0, 0, source.getWidth(0), source.getHeight(0))).build();
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        try (var pass = encoder.createRenderPass(descriptor)) {
            pass.setPipeline(IrisNativeVulkan.compiledFor(opaquePipeline));
            pass.setUniform("iris_NativeDepth", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, false));
            pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    private static void validateOpaqueTargets(GpuTextureView source, GpuTextureView depth0,
                                              GpuTextureView depth1, GpuTextureView depth2) {
        if (source == null || source.isClosed()) throw new IllegalArgumentException("Missing native depth source");
        validateOpaqueTarget(source, depth0);
        validateOpaqueTarget(source, depth1);
        validateOpaqueTarget(source, depth2);
        if (depth0.texture() == depth1.texture() || depth0.texture() == depth2.texture() || depth1.texture() == depth2.texture())
            throw new IllegalArgumentException("Opaque depth outputs must have separate image lifetimes");
    }

    private static void validateOpaqueTarget(GpuTextureView source, GpuTextureView target) {
        if (target == null || target.isClosed() || target.texture().getFormat() != GpuFormat.R32_FLOAT)
            throw new IllegalArgumentException("Opaque depth output must be an open R32_FLOAT image");
        if (source.getWidth(0) != target.getWidth(0) || source.getHeight(0) != target.getHeight(0))
            throw new IllegalArgumentException("Opaque depth snapshot dimensions differ");
        if (target.texture() == source.texture()) throw new IllegalArgumentException("Opaque depth source aliases an output");
    }

    public static void close() {
        if (pipeline != null) IrisNativeVulkan.unregisterCustomPipelineSource(pipeline);
        if (opaquePipeline != null) IrisNativeVulkan.unregisterCustomPipelineSource(opaquePipeline);
        if (handMergePipeline != null) IrisNativeVulkan.unregisterCustomPipelineSource(handMergePipeline);
        pipeline = null;
        opaquePipeline = null;
        handMergePipeline = null;
    }

    /** Add only newly drawn solid-hand pixels; keep the opaque depth behind world translucency. */
    public static void mergeSolidHand(CommandEncoder encoder, GpuTextureView nativeDepth,
                                      GpuTextureView beforeHand, GpuTextureView opaque, GpuTextureView output) {
        if (handMergePipeline == null) {
            handMergePipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("iris", "vulkan/depth/solid_hand"))
                .withVertexShader("core/screenquad").withFragmentShader("core/blit_screen")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_ALL))
                .build();
            IrisNativeVulkan.registerCustomPipelineSource(handMergePipeline, "depth/solid_hand", VERTEX, """
                #version 450 core
                uniform sampler2D iris_NativeDepth;
                uniform sampler2D iris_BeforeHand;
                uniform sampler2D iris_OpaqueDepth;
                layout(location=0) out float iris_ForwardDepth;
                void main() {
                    ivec2 pixel=ivec2(gl_FragCoord.xy);
                    float depth=1.0-texelFetch(iris_NativeDepth,pixel,0).r;
                    float before=texelFetch(iris_BeforeHand,pixel,0).r;
                    iris_ForwardDepth=depth < before ? depth : texelFetch(iris_OpaqueDepth,pixel,0).r;
                }
                """, false);
        }
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, false);
        try (var pass = encoder.createRenderPass(() -> "Iris solid hand depth merge", output, Optional.empty())) {
            pass.setPipeline(IrisNativeVulkan.compiledFor(handMergePipeline));
            pass.setUniform("iris_NativeDepth", nativeDepth, nearest);
            pass.setUniform("iris_BeforeHand", beforeHand, nearest);
            pass.setUniform("iris_OpaqueDepth", opaque, nearest);
            pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }
}
