package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.util.spvc.Spv.SpvExecutionModelVertex;

/** Actual MakeUp medium shadow plus Metal resource assignment; no Apple compiler/device is claimed. */
public final class MakeUpPushConstantMslTest {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]); Files.createDirectories(output);
        String vertex;
        try (var archive = FileSystems.newFileSystem(Path.of(args[0]))) {
            Path root = archive.getPath("/shaders");
            String properties = Files.readString(root.resolve("shaders.properties"));
            var medium = Pattern.compile("(?m)^\\s*profile\\.medium\\s*=([^\\r\\n]+)").matcher(properties);
            require(medium.find(), "Actual lowercase medium profile missing");
            Map<String, String> overrides = new TreeMap<>();
            for (String token : medium.group(1).trim().split("\\s+")) {
                String[] entry = token.split("=", 2);
                overrides.put(entry[0].replaceFirst("^!", ""), entry.length == 2 ? entry[1] : Boolean.toString(!entry[0].startsWith("!")));
            }
            var starts = ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/shadow.vsh"), AbsolutePackPath.fromAbsolutePath("/shadow.fsh"));
            var graph = new IncludeGraph(root, starts, true);
            require(graph.getFailures().isEmpty(), "MakeUp includes failed: " + graph.getFailures());
            var options = new ShaderPackOptions(graph, overrides);
            var includes = new IncludeProcessor(options.getIncludes());
            var defines = ImmutableList.of(new StringPair("IS_IRIS", ""), new StringPair("MC_OS_MAC", ""),
                    new StringPair("MC_VERSION", "260200"), new StringPair("MC_GL_VERSION", "460"),
                    new StringPair("MC_GLSL_VERSION", "460"), new StringPair("IRIS_VERSION", "11102"));
            String rawVertex = JcppProcessor.glslPreprocessSource(String.join("\n", includes.getIncludedFile(starts.get(0))) + "\n", defines);
            String rawFragment = JcppProcessor.glslPreprocessSource(String.join("\n", includes.getIncludedFile(starts.get(1))) + "\n", defines);
            var patched = TransformPatcher.patchSodium("makeup-medium-shadow", rawVertex, null, null, null, rawFragment,
                    ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID.getAlphaTest(), new Object2ObjectOpenHashMap<>(), true);
            vertex = IrisVulkanShaderResources.normalizeNativeVersion(patched.get(PatchShaderType.VERTEX));
            vertex = IrisVulkanShaderPruning.removeUnusedUniforms(vertex);
        }
        var sodiumPatch = IrisVulkanShaderResources.class.getDeclaredMethod("patchSodiumNativeVulkanUniforms", String.class, ShaderKey.class);
        sodiumPatch.setAccessible(true);
        vertex = (String) sodiumPatch.invoke(null, vertex, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID);
        var loosePatch = IrisVulkanShaderResources.class.getDeclaredMethod("patchLooseUniforms", String.class);
        loosePatch.setAccessible(true);
        Object loose = loosePatch.invoke(null, vertex);
        var source = loose.getClass().getDeclaredMethod("source"); source.setAccessible(true);
        var fields = loose.getClass().getDeclaredMethod("fields"); fields.setAccessible(true);
        var inject = IrisVulkanShaderResources.class.getDeclaredMethod("injectUniformBlock", String.class, List.class);
        inject.setAccessible(true);
        vertex = (String) inject.invoke(null, source.invoke(loose), fields.invoke(loose));
        vertex = IrisVulkanShadowMath.patchVertexDepth(vertex);
        vertex = vertex.replace("layout(std140) uniform IrisUniforms", "layout(std140, set=0, binding=0) uniform IrisUniforms")
                .replace("layout(std140) uniform u_Globals", "layout(std140, set=0, binding=1) uniform u_Globals");
        require(vertex.contains("uniform PC") && vertex.contains("uniform IrisUniforms") && vertex.contains("uniform u_Globals"), "Actual transformed MakeUp shader must retain both UBOs and Sodium PC");
        Files.writeString(output.resolve("makeup-medium-shadow.vert.glsl"), vertex);
        try (var compiler = new GlslCompiler(); var module = compiler.createIntermediary("makeup-medium-shadow", vertex, ShaderType.VERTEX)) {
            String missing = msl(module.spirv(), false);
            String mapped = msl(module.spirv(), true);
            Files.writeString(output.resolve("makeup-medium-missing-range.metal"), missing);
            Files.writeString(output.resolve("makeup-medium-with-range.metal"), mapped);
            int duplicate = count(missing, "[[buffer(0)]]");
            require(duplicate == 2, "Missing push range should reproduce IrisUniforms/PC buffer(0) collision; found " + duplicate);
            require(count(mapped, "[[buffer(0)]]") == 1 && count(mapped, "[[buffer(1)]]") == 1 && count(mapped, "[[buffer(2)]]") == 1,
                    "A declared push range must reserve a distinct Metal PC slot");
            require(Pattern.compile("constant PC& \\w+ \\[\\[buffer\\(0\\)\\]\\]").matcher(mapped).find(), "MoltenVK reserves PC before descriptor buffers");
            require(Pattern.compile("constant IrisUniforms& \\w+ \\[\\[buffer\\(1\\)\\]\\]").matcher(mapped).find(), "First UBO follows declared PC in Metal's buffer namespace");
        }
        System.out.println("MAKEUP_MSL_BINDING_PASS: actual medium shadow reproduces missing-range buffer(0) collision; declared PC range reserves Metal slot0, unchanged Vulkan UBO bindings map to Metal slots1/2");
        System.out.println("LIMIT: SPIRV-Cross resource mapping regression, not Apple's Metal compiler or a Mac runtime validation.");
    }

    private static String msl(ByteBuffer spirv, boolean declaredPushRange) {
        try (var stack = MemoryStack.stackPush()) {
            var pointer = stack.mallocPointer(1);
            check(0, spvc_context_create(pointer)); long context = pointer.get(0);
            try {
                var words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
                check(context, spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, pointer.get(0), SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long compiler = pointer.get(0);
                check(context, spvc_compiler_create_compiler_options(compiler, pointer)); long options = pointer.get(0);
                check(context, spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_VERSION, 20300));
                check(context, spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS));
                check(context, spvc_compiler_install_compiler_options(compiler, options));
                // MoltenVK 1.4.2 MVKPipelineLayout::Create reserves push constants
                // before descriptor content (MVKPipeline.mm lines 329-357). These
                // are Metal-only resource indexes; SPIR-V/Vulkan bindings stay 0/1.
                int firstUniform = declaredPushRange ? 1 : 0;
                addBinding(context, compiler, stack, 0, 0, firstUniform);
                addBinding(context, compiler, stack, 0, 1, firstUniform + 1);
                if (declaredPushRange) addBinding(context, compiler, stack, SPVC_MSL_PUSH_CONSTANT_DESC_SET, SPVC_MSL_PUSH_CONSTANT_BINDING, 0);
                check(context, spvc_compiler_compile(compiler, pointer));
                return pointer.getStringUTF8(0);
            } finally { spvc_context_destroy(context); }
        }
    }
    private static void addBinding(long context, long compiler, MemoryStack stack, int set, int binding, int metal) {
        var resource = SpvcMslResourceBinding.calloc(stack);
        spvc_msl_resource_binding_init(resource);
        resource.stage(SpvExecutionModelVertex).desc_set(set).binding(binding).msl_buffer(metal);
        check(context, spvc_compiler_msl_add_resource_binding(compiler, resource));
    }
    private static int count(String text, String token) { return (text.length() - text.replace(token, "").length()) / token.length(); }
    private static void check(long context, int result) { if (result != SPVC_SUCCESS) throw new IllegalStateException(spvc_context_get_last_error_string(context)); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
