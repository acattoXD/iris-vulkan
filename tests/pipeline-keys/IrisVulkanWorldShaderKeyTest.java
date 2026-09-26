package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPipelineAccessor;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.ProgramDirectives;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;
import java.util.Optional;

/** Real immutable descriptors and production key routing, without a graphics device. */
public final class IrisVulkanWorldShaderKeyTest {
    private static final List<GpuFormat> FORMATS = List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT);
    private static final int[] TARGETS = {0, 1};

    public static void main(String[] args) throws Exception {
        boolean previousShadow = ShadowRenderer.ACTIVE;
        try {
            ShadowRenderer.ACTIVE = false;
            IrisVulkanWorldPipelineStates.clear();
            RenderPipeline translucent = sodium("translucent_terrain", true);
            RenderPipeline cutout = sodium("cutout_terrain", false);
            ProgramDirectives off = directives(BlendModeOverride.OFF);
            ProgramDirectives on = directives(new BlendModeOverride(new BlendMode(0x0302, 0x0303, 1, 0x0303)));
            check(IrisNativeVulkan.worldShaderKey(translucent) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, "Original water route");
            check(IrisNativeVulkan.worldShaderKey(cutout) == ShaderKey.SODIUM_TERRAIN_CUTOUT, "Original cutout route");

            RenderPipeline water = adapt(translucent, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, off);
            check(water != translucent && water.getColorTargetStates().stream().noneMatch(state -> state.blendFunction().isPresent()),
                    "Pack blend-off state must apply to the new water descriptor");
            check(IrisPipelines.getPipeline(null, water) == ShaderKey.SODIUM_TERRAIN_CUTOUT,
                    "Fixture must reproduce Sodium's blend-state misclassification");
            check(IrisNativeVulkan.worldShaderKey(water) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Blend-off adaptation changed water into cutout");
            check(adapt(translucent, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, off) == water, "Repeated adaptation missed variant cache");

            RenderPipeline blendedCutout = adapt(cutout, ShaderKey.SODIUM_TERRAIN_CUTOUT, on);
            check(blendedCutout.getColorTargetStates().getFirst().blendFunction().isPresent(), "Pack cutout blend override lost");
            check(IrisPipelines.getPipeline(null, blendedCutout) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Fixture must reproduce inverse blend-state misclassification");
            check(IrisNativeVulkan.worldShaderKey(blendedCutout) == ShaderKey.SODIUM_TERRAIN_CUTOUT,
                    "Blend-enabled adaptation changed cutout into water");

            List<GpuFormat> resizedFormats = List.of(GpuFormat.RGBA16_FLOAT);
            RenderPipeline compatibleWater = IrisNativeVulkan.compatiblePipelineForGbufferPass(water, resizedFormats);
            check(compatibleWater != water && IrisNativeVulkan.worldShaderKey(compatibleWater) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Format copy lost selected water key");
            check(IrisNativeVulkan.compatiblePipelineForGbufferPass(water, resizedFormats) == compatibleWater,
                    "Repeated format copy missed compatible cache");
            RenderPipeline twiceCopied = IrisNativeVulkan.compatiblePipelineForGbufferPass(compatibleWater, FORMATS);
            check(IrisNativeVulkan.worldShaderKey(twiceCopied) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Second format copy lost prepared-key metadata");
            RenderPipeline compatibleOriginal = IrisNativeVulkan.compatiblePipelineForGbufferPass(translucent, resizedFormats);

            ShadowRenderer.ACTIVE = true;
            check(IrisNativeVulkan.worldShaderKey(translucent) == ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                    "Original Sodium descriptor was pinned to its world phase");
            check(IrisNativeVulkan.worldShaderKey(compatibleOriginal) == ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                    "Shared format-only descriptor was pinned to its creation phase");
            check(IrisNativeVulkan.worldShaderKey(cutout) == ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT, "Original shadow cutout route");
            RenderPipeline shadowWater = adapt(translucent, ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT, off);
            check(shadowWater != water && IrisNativeVulkan.worldShaderKey(shadowWater) == ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                    "World and shadow variants with identical attachment state collided");
            check(IrisNativeVulkan.worldShaderKey(water) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "An immutable world variant changed key with the ambient shadow phase");
            ShadowRenderer.ACTIVE = false;
            check(IrisNativeVulkan.worldShaderKey(translucent) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Original Sodium descriptor failed shadow-to-world transition");
            check(IrisNativeVulkan.worldShaderKey(compatibleOriginal) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Shared format-only descriptor failed shadow-to-world transition");
            check(IrisNativeVulkan.worldShaderKey(shadowWater) == ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                    "An immutable shadow variant changed key with the ambient world phase");

            IrisVulkanWorldPipelineStates.clear();
            check(IrisVulkanWorldPipelineStates.shaderKey(water) == null, "Variant clear retained old key metadata");
            RenderPipeline freshWater = adapt(translucent, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, off);
            check(freshWater != water && IrisNativeVulkan.worldShaderKey(freshWater) == ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    "Cleared variant cache reused an untracked descriptor");
            checkReloadClearsAllCaches();
            System.out.println("PASS: blend-off water, blended cutout, compatible copies, cached identities, world/shadow transitions, variant-key teardown");
        } finally {
            ShadowRenderer.ACTIVE = previousShadow;
            IrisVulkanWorldPipelineStates.clear();
        }
    }

    private static RenderPipeline adapt(RenderPipeline pipeline, ShaderKey key, ProgramDirectives directives) {
        return IrisVulkanWorldPipelineStates.adapt(pipeline, key, directives, FORMATS, TARGETS);
    }

    private static ProgramDirectives directives(BlendModeOverride blend) {
        return new ProgramSource("gbuffers_water", "void main() {}", null, null, null,
                "/* DRAWBUFFERS:01 */ void main() {}", null, null, blend).getDirectives();
    }

    private static RenderPipeline sodium(String name, boolean translucent) {
        RenderPipeline base = RenderPipelines.CUTOUT_TERRAIN;
        return VKOnly_RenderPipelineAccessor.iris$create(Identifier.fromNamespaceAndPath("sodium", "pipeline/" + name),
                base.getShaders(), ShaderDefines.builder().define("CUTOUT").build(), base.getBindGroupLayouts(),
                new ColorTargetState[]{new ColorTargetState(translucent ? Optional.of(BlendFunction.TRANSLUCENT) : Optional.empty(),
                        GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)}, base.getDepthStencilState(), base.getPolygonMode(), base.isCull(),
                base.getVertexFormatBindings().toArray(VertexFormat[]::new), base.getPrimitiveTopology(), base.pushConstantSize(), base.getSortKey());
    }

    private static void checkReloadClearsAllCaches() throws Exception {
        ClassNode type = new ClassNode();
        try (var input = IrisVulkanWorldShaderKeyTest.class.getResourceAsStream("IrisNativeVulkan.class")) {
            new ClassReader(input).accept(type, 0);
        }
        var destroy = type.methods.stream().filter(method -> method.name.equals("destroyFinalPassRenderer")).findFirst().orElseThrow();
        boolean variants = false, compatible = false, prepared = false;
        for (var instruction : destroy.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/IrisVulkanWorldPipelineStates") && call.name.equals("clear")) variants = true;
            if (instruction instanceof FieldInsnNode field && (field.name.equals("compatiblePipelines") || field.name.equals("preparedWorldShaderKeys"))) {
                var next = instruction.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (next instanceof MethodInsnNode call && call.name.equals("clear")) {
                    compatible |= field.name.equals("compatiblePipelines");
                    prepared |= field.name.equals("preparedWorldShaderKeys");
                }
            }
        }
        check(variants && compatible && prepared, "Pack teardown must clear variant identities, compatible copies, and retained prepared keys together");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
