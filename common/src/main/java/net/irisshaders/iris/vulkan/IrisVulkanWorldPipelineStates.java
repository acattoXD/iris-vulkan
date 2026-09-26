package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps pack blending to each physical MRT attachment without modifying GL state. */
public final class IrisVulkanWorldPipelineStates {
    private static final Map<Key, RenderPipeline> CACHE = new HashMap<>();
    private static final Map<RenderPipeline, ShaderKey> SHADER_KEYS = new IdentityHashMap<>();
    private IrisVulkanWorldPipelineStates() {}

    public static RenderPipeline adapt(RenderPipeline original, ShaderKey shader, ProgramDirectives directives,
                                       List<GpuFormat> formats, int[] logicalTargets) {
        if (shader == null || formats.isEmpty()) return original;
        ColorTargetState[] states = colorStates(original, shader, directives, formats, logicalTargets);
        return CACHE.computeIfAbsent(new Key(original, shader, List.of(states)), ignored -> {
            DepthStencilState depth = original.getDepthStencilState();
            var world = IrisVulkanPhaseContext.pipeline();
            if (shader == ShaderKey.WEATHER && depth != null && world != null && world.shouldWriteRainAndSnowToDepthBuffer()) {
                depth = new DepthStencilState(depth.depthTest(), true, depth.depthBiasScaleFactor(), depth.depthBiasConstant());
            }
            RenderPipeline pipeline = VKOnly_RenderPipelineAccessor.iris$create(original.getLocation(),
                original.getShaders(), original.getShaderDefines(),
                original.getBindGroupLayouts(), states, depth, original.getPolygonMode(),
                original.isCull(), original.getVertexFormatBindings().toArray(VertexFormat[]::new), original.getPrimitiveTopology(), original.pushConstantSize(), original.getSortKey());
            IrisPipelines.copyPipeline(original, pipeline);
            // Pack blend overrides change attachment state, not the selected material.
            // In particular, Sodium's translucent descriptor may become blend-off.
            SHADER_KEYS.put(pipeline, shader);
            return pipeline;
        });
    }

    public static ColorTargetState[] colorStates(RenderPipeline original, ShaderKey shader, ProgramDirectives directives,
                                                 List<GpuFormat> formats, int[] logicalTargets) {
        ColorTargetState base = original.getColorTargetStates().size() == 0 ? ColorTargetState.DEFAULT : original.getColorTargetStates().get(0);
        BlendModeOverride override = directives == null ? shader.getProgram().getBlendModeOverride()
            : directives.getBlendModeOverride().orElse(shader.getProgram().getBlendModeOverride());
        Optional<BlendFunction> blend = override == null ? base.blendFunction() : Optional.ofNullable(convert(override.blendMode()));
        ColorTargetState[] result = new ColorTargetState[formats.size()];
        for (int i=0; i<result.length; ++i) {
            Optional<BlendFunction> attachmentBlend = blend;
            if (directives != null) for (var buffer : directives.getBufferBlendOverrides()) {
                if (buffer.index() == logicalTargets[i]) attachmentBlend = Optional.ofNullable(convert(buffer.blendMode()));
            }
            result[i] = new ColorTargetState(attachmentBlend, formats.get(i), base.writeMask());
        }
        return result;
    }

    public static BlendFunction convert(BlendMode mode) {
        return mode == null ? null : new BlendFunction(factor(mode.srcRgb()),factor(mode.dstRgb()),factor(mode.srcAlpha()),factor(mode.dstAlpha()));
    }

    private static BlendFactor factor(int value) {
        return switch (value) {
            case 0 -> BlendFactor.ZERO;
            case 1 -> BlendFactor.ONE;
            case 0x0300 -> BlendFactor.SRC_COLOR;
            case 0x0301 -> BlendFactor.ONE_MINUS_SRC_COLOR;
            case 0x0302 -> BlendFactor.SRC_ALPHA;
            case 0x0303 -> BlendFactor.ONE_MINUS_SRC_ALPHA;
            case 0x0304 -> BlendFactor.DST_ALPHA;
            case 0x0305 -> BlendFactor.ONE_MINUS_DST_ALPHA;
            case 0x0306 -> BlendFactor.DST_COLOR;
            case 0x0307 -> BlendFactor.ONE_MINUS_DST_COLOR;
            case 0x0308 -> BlendFactor.SRC_ALPHA_SATURATE;
            case 0x8001 -> BlendFactor.CONSTANT_COLOR;
            case 0x8002 -> BlendFactor.ONE_MINUS_CONSTANT_COLOR;
            case 0x8003 -> BlendFactor.CONSTANT_ALPHA;
            case 0x8004 -> BlendFactor.ONE_MINUS_CONSTANT_ALPHA;
            default -> throw new UnsupportedOperationException("Unknown pack blend factor 0x"+Integer.toHexString(value));
        };
    }
    static ShaderKey shaderKey(RenderPipeline pipeline) { return SHADER_KEYS.get(pipeline); }
    public static void clear() {
        CACHE.clear();
        SHADER_KEYS.clear();
    }
    private record Key(RenderPipeline pipeline, ShaderKey shader, List<ColorTargetState> states) {}
}
