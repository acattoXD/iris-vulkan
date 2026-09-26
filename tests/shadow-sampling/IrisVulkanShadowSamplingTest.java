package net.irisshaders.iris.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Compiles the generated real helpers to Vulkan SPIR-V without starting Minecraft. */
public final class IrisVulkanShadowSamplingTest {
    public static void main(String[] args) {
        String source = """
                #version 450 core
                layout(binding = 0) uniform sampler2DShadow shadowtex0;
                layout(binding = 1) uniform sampler2DShadow shadowtex1;
                layout(location = 0) out vec4 outputColor;
                // BSL and other packs wrap comparison sampling in a helper
                // whose parameter remains sampler2DShadow after uniforms are
                // lowered to ordinary sampler2D resources for Vulkan.
                float texture2DShadow(sampler2DShadow shadowtex, vec3 shadowPos) {
                    return vec4(texture(shadowtex, shadowPos)).x;
                }
                void main() {
                    vec3 coord = vec3(0.43, 0.51, 0.37);
                    float result = texture2DShadow(shadowtex0, coord);
                    result += texture(shadowtex1, coord, 0.0);
                    result += textureLod(shadowtex0, coord, 1.5);
                    result += textureOffset(shadowtex0, coord, ivec2(1));
                    result += textureLodOffset(shadowtex1, coord, 0.0, ivec2(-1));
                    result += textureProj(shadowtex0, vec4(coord, 1.0));
                    result += textureGrad(shadowtex1, coord, vec2(0.01, 0.0), vec2(0.0, 0.01));
                    vec4 gathered = textureGather(shadowtex0, coord.xy, coord.z);
                    result += texture2DShadow(shadowtex1, coord);
                    outputColor = vec4(result) + gathered;
                }
                """;
        int count = 0;
        for (boolean nearest : new boolean[] {false, true}) {
            for (boolean mipmap : new boolean[] {false, true}) {
                Map<String, IrisVulkanShadowSampling.Sampling> settings = Map.of(
                        "shadowtex0", new IrisVulkanShadowSampling.Sampling(nearest, mipmap),
                        "shadowtex1", new IrisVulkanShadowSampling.Sampling(!nearest, mipmap));
                String fragment = IrisVulkanShadowSampling.patch(source, true, settings);
                if (fragment.contains("uniform sampler2DShadow")) throw new AssertionError("Unlowered shadow declaration");
                if (!fragment.contains("float iris_vulkan_shadow_compare_shadowtex0(vec3 coord)")) {
                    throw new AssertionError("A modern shadow texture call must return float");
                }
                if (fragment.contains("texture2DShadow(shadowtex0,") || fragment.contains("texture2DShadow(shadowtex1,")) {
                    throw new AssertionError("Legacy comparison helper calls were not lowered");
                }
                compile(fragment, Shaderc.shaderc_fragment_shader);
                // Generate again with vertex semantics: implicit derivatives are forbidden.
                String vertex = IrisVulkanShadowSampling.patch(source.replace("layout(location = 0) out vec4 outputColor;", "")
                        .replace("outputColor = vec4(result) + gathered;", "gl_Position = vec4(result) + gathered;"), false, settings);
                compile(vertex, Shaderc.shaderc_vertex_shader);
                count += 2;
            }
        }
        String customWrapper = source.replace("return vec4(texture(shadowtex, shadowPos)).x;",
                "return vec4(texture(shadowtex, shadowPos)).x * 0.5;");
        String unchanged = IrisVulkanShadowSampling.patch(customWrapper, true, Map.of(
                "shadowtex0", new IrisVulkanShadowSampling.Sampling(false, false),
                "shadowtex1", new IrisVulkanShadowSampling.Sampling(false, false)));
        if (!unchanged.contains("texture2DShadow(shadowtex0, coord)")) {
            throw new AssertionError("Non-identity texture2DShadow helper was rewritten");
        }
        if (args.length > 0) {
            verifyActualDump(Path.of(args[0]), args.length > 1 ? Path.of(args[1]) : null);
        }
        System.out.println("IRIS_VULKAN_SHADOW_SAMPLING_PASS: " + count + " native Vulkan SPIR-V compilations");
    }

    private static void compile(String source, int kind) {
        String error = compileError(source, kind);
        if (error != null) throw new AssertionError(error);
    }

    private static String compileError(String source, int kind) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        long result = 0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            Shaderc.shaderc_compile_options_add_macro_definition(options, "gl_VertexID", "gl_VertexIndex");
            Shaderc.shaderc_compile_options_add_macro_definition(options, "gl_InstanceID", "gl_InstanceIndex");
            result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "shadow-sampling-check", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                return Shaderc.shaderc_result_get_error_message(result);
            }
            return Shaderc.shaderc_result_get_length(result) == 0 ? "No SPIR-V produced" : null;
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /** Optional real-dump check: pass the failed fragment dump and, optionally, its vertex pair. */
    private static void verifyActualDump(Path fragmentPath, Path vertexPath) {
        try {
            String original = Files.readString(fragmentPath);
            String originalError = compileError(original, Shaderc.shaderc_fragment_shader);
            if (originalError == null || !originalError.contains("texture2DShadow")) {
                throw new AssertionError("Actual dump did not reproduce texture2DShadow failure: " + originalError);
            }
            Map<String, IrisVulkanShadowSampling.Sampling> settings = Map.of(
                    "shadowtex0", new IrisVulkanShadowSampling.Sampling(false, false),
                    "shadowtex1", new IrisVulkanShadowSampling.Sampling(false, false));
            String patched = IrisVulkanShadowSampling.patch(original, true, settings);
            compile(patched, Shaderc.shaderc_fragment_shader);
            if (vertexPath != null) {
                String vertex = Files.readString(vertexPath);
                compile(IrisVulkanShadowSampling.patch(vertex, false, settings), Shaderc.shaderc_vertex_shader);
            }
            System.out.println("IRIS_VULKAN_ACTUAL_SHADOW_DUMP_PASS: original fragment failed as expected; patched fragment"
                    + (vertexPath == null ? "" : "+vertex") + " compiled");
        } catch (IOException e) {
            throw new AssertionError("Could not read actual Vulkan dump", e);
        }
    }
}
