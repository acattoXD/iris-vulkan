package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Actual uploaded gbuffers-only pack, transformed and compiled without a game/GPU or post-pass requirement. */
public final class MineekShaderContractTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]); Files.createDirectories(output);
        Map<String, ShaderKey> families = new LinkedHashMap<>();
        families.put("basic", ShaderKey.BASIC_COLOR);
        families.put("textured", ShaderKey.TEXTURED_COLOR);
        families.put("textured_lit", ShaderKey.PARTICLES);
        families.put("terrain", ShaderKey.SODIUM_TERRAIN_SOLID);
        families.put("water", ShaderKey.SODIUM_TERRAIN_TRANSLUCENT);
        families.put("skybasic", ShaderKey.SKY_BASIC);
        int stages = 0;
        try (var archive = FileSystems.newFileSystem(Path.of(args[0])); var compiler = new GlslCompiler()) {
            Path root = archive.getPath("/shaders");
            ImmutableList.Builder<AbsolutePackPath> starts = ImmutableList.builder();
            families.keySet().forEach(name -> { starts.add(AbsolutePackPath.fromAbsolutePath("/gbuffers_" + name + ".vsh")); starts.add(AbsolutePackPath.fromAbsolutePath("/gbuffers_" + name + ".fsh")); });
            var graph = new IncludeGraph(root, starts.build(), true);
            require(graph.getFailures().isEmpty(), "Actual pack includes must resolve");
            var options = new ShaderPackOptions(graph, Map.of());
            var includes = new IncludeProcessor(options.getIncludes());
            var environment = List.of(new StringPair("IS_IRIS", ""), new StringPair("MC_VERSION", "260200"),
                new StringPair("MC_GL_VERSION", "460"), new StringPair("MC_GLSL_VERSION", "460"));
            require(!Files.exists(root.resolve("shadow.vsh")) && !Files.exists(root.resolve("final.fsh"))
                && !Files.exists(root.resolve("composite.fsh")), "Fixture is intentionally without shadow/final/composite shaders");
            Properties blocks = new Properties(); try (var reader = Files.newBufferedReader(root.resolve("block.properties"))) { blocks.load(reader); }
            require("minecraft:water".equals(blocks.getProperty("block.10001")), "Actual procedural water material remains10001");
            for (var entry : families.entrySet()) {
                String name = "gbuffers_" + entry.getKey(); ShaderKey key = entry.getValue();
                String vertex = JcppProcessor.glslPreprocessSource(String.join("\n", includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath("/" + name + ".vsh"))) + "\n", environment);
                String fragment = JcppProcessor.glslPreprocessSource(String.join("\n", includes.getIncludedFile(AbsolutePackPath.fromAbsolutePath("/" + name + ".fsh"))) + "\n", environment);
                boolean sodium = entry.getKey().equals("terrain") || entry.getKey().equals("water");
                var transformed = sodium ? TransformPatcher.patchSodium(name, vertex, null, null, null, fragment,
                        key.getAlphaTest(), new Object2ObjectOpenHashMap<>(), false)
                    : TransformPatcher.patchVanilla(name, vertex, null, null, null, fragment, key.getAlphaTest(), false, false, false,
                        new ShaderAttributeInputs(key.getVertexFormat(), key.shouldIgnoreLightmap(), false, false, key.isText(), false), new Object2ObjectOpenHashMap<>());
                for (PatchShaderType stage : List.of(PatchShaderType.VERTEX, PatchShaderType.FRAGMENT)) {
                    String source = IrisVulkanShaderResources.normalizeNativeVersion(transformed.get(stage));
                    source = IrisVulkanShaderPruning.removeUnusedUniforms(source);
                    if (stage == PatchShaderType.VERTEX) {
                        source = (String) method("patchSodiumNativeVulkanUniforms", String.class, ShaderKey.class).invoke(null, source, key);
                        source = IrisVulkanShaderResources.patchWorldProjectionUniforms(source, key);
                    } else source = IrisVulkanShaderResources.patchWorldFragmentCoordinates(source, key);
                    Object loose = method("patchLooseUniforms", String.class).invoke(null, source);
                    Method sourceGetter = loose.getClass().getDeclaredMethod("source"); sourceGetter.setAccessible(true);
                    Method fieldsGetter = loose.getClass().getDeclaredMethod("fields"); fieldsGetter.setAccessible(true);
                    source = (String) method("injectUniformBlock", String.class, List.class).invoke(null, sourceGetter.invoke(loose), fieldsGetter.invoke(loose));
                    if (stage == PatchShaderType.VERTEX) source = IrisVulkanShaderResources.patchWorldClipDepth(source, key);
                    require(!source.matches("(?s).*\\b(?:sampler\\w*|[iu]?image\\w*)\\s+shadow\\w*.*"), "No shadow resources should appear in this pack");
                    if (entry.getKey().equals("water") && stage == PatchShaderType.VERTEX)
                        require(source.contains("vec2 iris_Entity") && source.contains("10001"), "Actual vec2 material decode selects procedural water");
                    String label = name + (stage == PatchShaderType.VERTEX ? ".vert" : ".frag");
                    Files.writeString(output.resolve(label + ".glsl"), source);
                    try (var module = compiler.createIntermediary(label, source, stage == PatchShaderType.VERTEX ? ShaderType.VERTEX : ShaderType.FRAGMENT)) {
                        require(module.spirv() != null && module.spirv().remaining() > 0, "Native SPIR-V must be emitted for " + label);
                    }
                    stages++;
                }
                System.out.println("Compiled actual Mineek pair: " + name);
            }
        }
        require(stages == 12, "All six actual shader pairs compiled");
        System.out.println("MINEEK_SHADER_CONTRACT_PASS: 12 actual stages preprocessed/transformed/compiled; water10001 vec2 ABI; no shadow or screen programs required");
    }
    static Method method(String name, Class<?>... types) throws Exception { Method value = IrisVulkanShaderResources.class.getDeclaredMethod(name, types); value.setAccessible(true); return value; }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
