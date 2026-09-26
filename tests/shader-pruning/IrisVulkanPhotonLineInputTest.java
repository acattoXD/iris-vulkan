package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.lwjgl.util.spvc.Spvc.*;

/** Reproduce Photon LINES' leftover vaColor and verify the real producer interface on CPU. */
public final class IrisVulkanPhotonLineInputTest {
    private static int checks;
    private static Object compiler;
    private static Method compile;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static SpvModule shader(String name, String source, ShaderType stage) throws Exception {
        try { return (SpvModule) compile.invoke(compiler, name, source, stage); }
        catch (InvocationTargetException wrapped) { throw (Exception) wrapped.getCause(); }
    }
    private static List<String> inputs(SpvModule module) throws ShaderCompileException {
        return module.reflect().inputs().stream().map(SpvModule.Reflection.InterfaceVariable::name).toList();
    }
    private static IrisVulkanGraphicsCompiler.Prepared prepare(RenderPipeline pipeline, String name, SpvModule v, SpvModule f) throws Exception {
        return IrisVulkanGraphicsCompiler.prepare(pipeline, name, v, f,
            IrisVulkanStorageReflection.reflect(v.spv(), Set.of()), IrisVulkanStorageReflection.reflect(f.spv(), Set.of()));
    }
    public static void main(String[] args) throws Exception {
        Path dumps = Path.of(args[0]), output = Path.of(args[1]); Files.createDirectories(output);
        String stem = "iris_lines_minecraft_pipeline_lines_translucent";
        String vertex = Files.readString(dumps.resolve(stem + ".vert.glsl"));
        String fragment = Files.readString(dumps.resolve(stem + ".frag.glsl"));
        String pruned = IrisVulkanShaderPruning.removeUnusedInputs(vertex);
        check(Pattern.compile("\\bvaColor\\b").matcher(vertex).results().count() == 1, "Original vaColor occurs only in its declaration");
        check(!pruned.contains("vaColor"), "Production pruning removes unused vaColor");
        check(vertex.length() == pruned.length() && vertex.lines().count() == pruned.lines().count(), "Offsets and diagnostic lines retained");
        check(pruned.equals(IrisVulkanShaderPruning.removeUnusedInputs(pruned)), "Production pruning is idempotent");
        check(pruned.contains("tint = iris_Color * iris_transforms.ColorModulator;"), "Actual color computation retained");
        check(pruned.contains("in vec4 iris_Color;") && pruned.contains("in vec3 iris_Position;") && pruned.contains("in vec3 iris_Normal;"), "All live color/position/normal declarations retained");
        check(vertex.equals(IrisVulkanShaderPruning.removeUnusedUniforms(vertex)), "Earlier uniform pruning leaves this captured vertex unchanged");
        Files.writeString(output.resolve("original.vert.glsl"), vertex);
        Files.writeString(output.resolve("pruned.vert.glsl"), pruned);
        Files.writeString(output.resolve("unchanged.frag.glsl"), fragment);

        RenderPipeline real = RenderPipelines.LINES_TRANSLUCENT;
        Method chooseFormats = IrisVulkanShaderResources.class.getDeclaredMethod("chooseVertexFormats", RenderPipeline.class, ShaderKey.class);
        chooseFormats.setAccessible(true);
        VertexFormat[] formats = (VertexFormat[]) chooseFormats.invoke(null, real, ShaderKey.LINES);
        VertexFormat actual = real.getVertexFormatBinding(0), alias = formats[0];
        check(alias.equals(IrisVulkanShaderResources.aliasVanillaVertexFormat(actual)), "Actual LINES format selection aliases the real producer");
        check(alias.getVertexSize() == actual.getVertexSize() && alias.getStepRate() == actual.getStepRate(), "Real producer stride and rate preserved");
        check(alias.getElements().size() == actual.getElements().size(), "Real producer element count preserved");
        for (int i=0; i<actual.getElements().size(); i++) {
            var before = actual.getElements().get(i); var after = alias.getElements().get(i);
            check(before.offset() == after.offset() && before.format() == after.format(), "Real producer offset/format preserved for " + before.name());
        }
        check(alias.getElement("vaColor") == null, "Real producer has no vaColor");
        for (String name : List.of("iris_Color", "iris_Position", "iris_Normal")) check(alias.getElement(name) != null, "Real producer supplies " + name);

        Class<?> compilerType = Class.forName("net.irisshaders.iris.vulkan.IrisVulkanGraphicsCompiler$Compiler");
        Constructor<?> constructor = compilerType.getDeclaredConstructor(); constructor.setAccessible(true); compiler = constructor.newInstance();
        compile = compilerType.getDeclaredMethod("compile", String.class, String.class, ShaderType.class); compile.setAccessible(true);
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("compilerLocation", compilerType.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("producerPipeline", real.getLocation().toString());
        report.put("producerTopology", real.getPrimitiveTopology().toString());
        report.put("producerStride", actual.getVertexSize());
        report.put("producerElements", actual.getElements().stream().map(e -> Map.of("name", e.name(), "offset", e.offset(), "format", e.format().toString())).toList());
        report.put("aliasElements", alias.getElements().stream().map(e -> Map.of("name", e.name(), "offset", e.offset(), "format", e.format().toString())).toList());
        try (var originalV = shader("photon-lines-original", vertex, ShaderType.VERTEX);
             var originalF = shader("photon-lines-original-fragment", fragment, ShaderType.FRAGMENT);
             var fixedV = shader("photon-lines-pruned", pruned, ShaderType.VERTEX);
             var fixedF = shader("photon-lines-pruned-fragment", fragment, ShaderType.FRAGMENT)) {
            check(inputs(originalV).contains("vaColor"), "Actual O0 compiler exposes unused vaColor in original interface");
            check(new HashSet<>(inputs(fixedV)).equals(Set.of("iris_Color", "iris_Position", "iris_Normal")), "Actual pruned SPIR-V retains exactly the three live inputs");
            report.put("originalInputs", inputs(originalV)); report.put("prunedInputs", inputs(fixedV));
            report.put("originalVertexSpirvBytes", originalV.spv().remaining()); report.put("prunedVertexSpirvBytes", fixedV.spv().remaining());
            report.put("fragmentSpirvBytes", fixedF.spv().remaining());
            var declarations = new LinkedHashMap<String,UniformType>();
            for (SpvModule module : List.of(originalV, originalF, fixedV, fixedF)) {
                for (var descriptor : module.reflect().descriptors()) {
                    UniformType kind = switch (descriptor.resourceType()) {
                        case SPVC_RESOURCE_TYPE_UNIFORM_BUFFER -> UniformType.UNIFORM_BUFFER;
                        case SPVC_RESOURCE_TYPE_SAMPLED_IMAGE -> UniformType.COMBINED_IMAGE_SAMPLER;
                        default -> throw new AssertionError("Unexpected resource category " + descriptor.resourceType());
                    };
                    var previous = declarations.putIfAbsent(descriptor.name(), kind);
                    check(previous == null || previous == kind, "Reflected declaration agrees for " + descriptor.name());
                }
            }
            var layout = BindGroupLayout.builder(); declarations.forEach(layout::withUniform);
            var location = net.minecraft.resources.Identifier.fromNamespaceAndPath("iris", "photon-lines-regression");
            var builder = RenderPipeline.builder().withLocation(location)
                .withVertexShader(location).withFragmentShader(location)
                .withPrimitiveTopology(real.getPrimitiveTopology()).withCull(real.isCull()).withPolygonMode(real.getPolygonMode())
                .withDepthStencilState(real.getDepthStencilState()).withPushConstantSize(real.pushConstantSize()).withBindGroupLayout(layout.build());
            for (int i=0; i<formats.length; i++) if (formats[i] != null) builder.withVertexBinding(i, formats[i]);
            for (int i=0; i<3; i++) builder.withColorTargetState(i, i < real.getColorTargetStates().size() && real.getColorTargetStates().get(i) != null ? real.getColorTargetStates().get(i) : ColorTargetState.DEFAULT);
            RenderPipeline descriptor = builder.build();
            try {
                prepare(descriptor, "photon-lines-original", originalV, originalF);
                throw new AssertionError("Original interface unexpectedly accepted missing vaColor producer");
            } catch (ShaderCompileException expected) {
                check(expected.getMessage().equals("Missing producer vertex attribute vaColor"), "Fail control reproduces the live missing producer error");
                report.put("originalPrepareFailure", expected.getMessage());
            }
            var prepared = prepare(descriptor, "photon-lines-pruned", fixedV, fixedF);
            check(prepared.createInfo().attribBindings().size() == 3, "Prepared interface binds exactly the live producer inputs");
            check(prepared.createInfo().vertexBuffers().getFirst().stride() == actual.getVertexSize(), "Prepared interface uses real producer stride");
            for (var input : fixedV.reflect().inputs()) {
                var element = alias.getElement(input.name());
                var binding = prepared.createInfo().attribBindings().stream().filter(b -> b.location() == input.location()).findFirst().orElseThrow();
                check(binding.offset() == element.offset() && binding.format() == element.format(), "Prepared attribute agrees with actual producer for " + input.name());
            }
            check(prepared.storageBindings().isEmpty(), "No unexpected advanced storage bindings");
            check(prepared.createInfo().uniforms().size() == declarations.size(), "All reflected normal descriptors bound");
            report.put("reflectedDeclarations", declarations); report.put("preparedAttributeBindings", prepared.createInfo().attribBindings());
            report.put("prunedPrepareSuccess", true);
        } finally { ((AutoCloseable) compiler).close(); }
        report.put("checks", checks); report.put("gpuInvocations", 0);
        Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("IRIS_VULKAN_PHOTON_LINE_INPUT_PASS: " + checks + " checks; original prepare rejects vaColor; pruned prepare matches actual LINES_TRANSLUCENT producer; 4 real shaderc invocations; no GPU");
    }
}
