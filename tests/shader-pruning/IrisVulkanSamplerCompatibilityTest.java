package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** CPU-only sampler-parameter compatibility checks and real shaderc regressions. */
public final class IrisVulkanSamplerCompatibilityTest {
    private static final String NAME = "iris_vk_samplerParameter";
    private static final List<String> failures = new ArrayList<>();
    private static final List<CompileResult> compilation = new ArrayList<>();
    private static int checks;
    private static Object compiler;
    private static Method compile;
    private record CompileResult(String variant, String name, String stage, boolean success, int spirvBytes, String error) {}

    private static String patch(String source) { return IrisVulkanShaderCompatibility.renameSamplerParameters(source); }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) { failures.add(message); System.out.println("FAIL: " + message); }
    }
    private static void expected(String label, String source, String expected) {
        String actual = patch(source);
        check(Objects.equals(expected, actual), label + ": exact output");
        check(Objects.equals(actual, patch(actual)), label + ": idempotent");
        if (source != null) check(source.lines().count() == actual.lines().count(), label + ": diagnostic lines retained");
    }
    private static void textRegressions() {
        expected("null", null, null);
        expected("empty", "", "");
        expected("no sampler", "void main() {}", "void main() {}");
        expected("comments only", "// sampler2D sampler; { sampler }\n/* vec4 f(sampler2D sampler) { return sampler; } */",
            "// sampler2D sampler; { sampler }\n/* vec4 f(sampler2D sampler) { return sampler; } */");
        expected("comments and diagnostic strings", """
            // " sampler2D sampler ) { } /*
            #define DIAGNOSTIC "sampler2D sampler ( sampler ) { }"
            #define ESCAPED "quoted \\" sampler"
            vec4 f(sampler2D /* sampler ) { } */ sampler, vec2 coord) {
                // sampler ) } "
                /* sampler and { } " */
                return texture(sampler, coord);
            }
            """, """
            // " sampler2D sampler ) { } /*
            #define DIAGNOSTIC "sampler2D sampler ( sampler ) { }"
            #define ESCAPED "quoted \\" sampler"
            vec4 f(sampler2D /* sampler ) { } */ iris_vk_samplerParameter, vec2 coord) {
                // sampler ) } "
                /* sampler and { } " */
                return texture(iris_vk_samplerParameter, coord);
            }
            """);
        expected("line comment marker in diagnostic string", "#define DIAGNOSTIC \"https://pack/sampler\"\nvec4 f(sampler2D sampler) { return texture(sampler, vec2(0)); }",
            "#define DIAGNOSTIC \"https://pack/sampler\"\nvec4 f(sampler2D " + NAME + ") { return texture(" + NAME + ", vec2(0)); }");
        expected("block comment marker in diagnostic string", "#define DIAGNOSTIC \"/* sampler\"\nvec4 f(sampler2D sampler) { return texture(sampler, vec2(0)); }",
            "#define DIAGNOSTIC \"/* sampler\"\nvec4 f(sampler2D " + NAME + ") { return texture(" + NAME + ", vec2(0)); }");
        expected("global resources interfaces and non-sampler locals", """
            layout(binding=0) uniform sampler2D sampler;
            layout(location=0) in vec4 sampler;
            uniform Data { vec4 sampler; } material;
            struct Other { vec4 sampler; };
            void unrelated() { float sampler = 0.0; }
            vec4 f(sampler2D sampler) { return texture(sampler, vec2(0)); }
            void after() { float sampler = 1.0; }
            """, """
            layout(binding=0) uniform sampler2D sampler;
            layout(location=0) in vec4 sampler;
            uniform Data { vec4 sampler; } material;
            struct Other { vec4 sampler; };
            void unrelated() { float sampler = 0.0; }
            vec4 f(sampler2D iris_vk_samplerParameter) { return texture(iris_vk_samplerParameter, vec2(0)); }
            void after() { float sampler = 1.0; }
            """);
        expected("field access and identifier boundaries", "vec4 f(sampler2D sampler, vec2 samplerCoord) { return texture(sampler, samplerCoord) + material.sampler + material . /* sampler */ sampler + samplerValue; }",
            "vec4 f(sampler2D " + NAME + ", vec2 samplerCoord) { return texture(" + NAME + ", samplerCoord) + material.sampler + material . /* sampler */ sampler + samplerValue; }");
        expected("prototypes second arguments and nested braces", """
            vec4 f(vec2 coord, sampler2D sampler);
            vec4 f(vec2 coord, sampler2D sampler) {
                if (coord.x > 0.0) { for (int i=0; i<2; i++) { coord += texture(sampler, coord).xy; } }
                return texture(sampler, coord);
            }
            """, """
            vec4 f(vec2 coord, sampler2D iris_vk_samplerParameter);
            vec4 f(vec2 coord, sampler2D iris_vk_samplerParameter) {
                if (coord.x > 0.0) { for (int i=0; i<2; i++) { coord += texture(iris_vk_samplerParameter, coord).xy; } }
                return texture(iris_vk_samplerParameter, coord);
            }
            """);
        expected("signed unsigned array and qualifiers", "ivec4 a(highp isampler2D sampler) { return texelFetch(sampler, ivec2(0), 0); }\nuvec4 b(vec2 x, const in usampler2D sampler[2]) { return texture(sampler[0], x); }",
            "ivec4 a(highp isampler2D " + NAME + ") { return texelFetch(" + NAME + ", ivec2(0), 0); }\nuvec4 b(vec2 x, const in usampler2D " + NAME + "[2]) { return texture(" + NAME + "[0], x); }");
        expected("array suffix on sampler type", "vec4 f(sampler2D[2] sampler) { return texture(sampler[0], vec2(0)); }",
            "vec4 f(sampler2D[2] " + NAME + ") { return texture(" + NAME + "[0], vec2(0)); }");
        expected("generated name collisions", "float iris_vk_samplerParameter; float iris_vk_samplerParameter_;\nvec4 f(sampler2D sampler) { return texture(sampler, vec2(iris_vk_samplerParameter + iris_vk_samplerParameter_)); }",
            "float iris_vk_samplerParameter; float iris_vk_samplerParameter_;\nvec4 f(sampler2D iris_vk_samplerParameter__) { return texture(iris_vk_samplerParameter__, vec2(iris_vk_samplerParameter + iris_vk_samplerParameter_)); }");
        expected("comment and string names do not cause collision", "// iris_vk_samplerParameter\n#define NOTE \"iris_vk_samplerParameter\"\nvec4 f(sampler2D sampler) { return texture(sampler, vec2(0)); }",
            "// iris_vk_samplerParameter\n#define NOTE \"iris_vk_samplerParameter\"\nvec4 f(sampler2D " + NAME + ") { return texture(" + NAME + ", vec2(0)); }");
        expected("other parameter names and bare sampler type", "vec4 f(sampler2D my_sampler, sampler2D sampler2, sampler separateSampler) { return texture(my_sampler, vec2(0)); }",
            "vec4 f(sampler2D my_sampler, sampler2D sampler2, sampler separateSampler) { return texture(my_sampler, vec2(0)); }");
        expected("malformed function", "vec4 f(sampler2D sampler) { return texture(sampler, vec2(0));",
            "vec4 f(sampler2D sampler) { return texture(sampler, vec2(0));");
    }

    private static CompileResult compile(String variant, String name, String source, ShaderType stage) {
        CompileResult result;
        try (var module = (SpvModule) compile.invoke(compiler, name, source, stage)) {
            result = new CompileResult(variant, name, stage.name(), true, module.spv().remaining(), null);
        } catch (Throwable failure) {
            if (failure instanceof InvocationTargetException wrapped) failure = wrapped.getCause();
            result = new CompileResult(variant, name, stage.name(), false, 0, failure.toString());
        }
        compilation.add(result);
        return result;
    }
    private static void fixture(String name, String source, Path output) throws Exception {
        var original = compile("fixture-original", name, source, ShaderType.FRAGMENT);
        check(!original.success() && original.error().contains("unexpected SAMPLER"), name + ": original reproduces reserved token");
        String fixed = patch(source);
        var transformed = compile("fixture-actual-helper", name, fixed, ShaderType.FRAGMENT);
        check(transformed.success(), name + ": actual helper compiles" + (transformed.success() ? "" : " -> " + transformed.error()));
        check(fixed.equals(patch(fixed)), name + ": compiled fixture idempotent");
        Files.writeString(output.resolve(name + ".original.glsl"), source);
        Files.writeString(output.resolve(name + ".actual-helper.glsl"), fixed);
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        textRegressions();
        Class<?> type = Class.forName("net.irisshaders.iris.vulkan.IrisVulkanGraphicsCompiler$Compiler");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        compiler = constructor.newInstance();
        compile = type.getDeclaredMethod("compile", String.class, String.class, ShaderType.class); compile.setAccessible(true);
        try {
            String prefix = "#version 450 core\nuniform sampler2D tex;\nlayout(location=0) out vec4 color;\n";
            fixture("minimal-sampler", prefix + "vec4 sampleTexture(sampler2D sampler) { return texture(sampler, vec2(0)); }\nvoid main() { color=sampleTexture(tex); }\n", output);
            fixture("quoted-line-comment-marker", prefix + "#define DIAGNOSTIC \"https://pack/sampler\"\nvec4 sampleTexture(sampler2D sampler) { return texture(sampler, vec2(0)); }\nvoid main() { color=sampleTexture(tex); }\n", output);
            fixture("quoted-block-comment-marker", prefix + "#define DIAGNOSTIC \"/* sampler\"\nvec4 sampleTexture(sampler2D sampler) { return texture(sampler, vec2(0)); }\nvoid main() { color=sampleTexture(tex); }\n", output);
            fixture("prototype-nested-second-argument", prefix + """
                vec4 sampleTexture(vec2 coord, sampler2D sampler);
                vec4 sampleTexture(vec2 coord, sampler2D sampler) {
                    vec4 sum = vec4(0);
                    for (int i=0; i<2; i++) { if (i == 0) { sum += texture(sampler, coord); } }
                    return sum;
                }
                void main() { color=sampleTexture(vec2(0), tex); }
                """, output);
            fixture("collision-sampler", prefix + """
                vec4 sampleTexture(sampler2D sampler) {
                    vec2 iris_vk_samplerParameter = vec2(0.25);
                    vec2 iris_vk_samplerParameter_ = vec2(0.25);
                    return texture(sampler, iris_vk_samplerParameter + iris_vk_samplerParameter_);
                }
                void main() { color=sampleTexture(tex); }
                """, output);
            String arrayPrefix = "#version 450 core\nuniform sampler2D tex[2];\nlayout(location=0) out vec4 color;\n";
            var arrayControl = compile("fixture-valid-syntax-control", "array-type-suffix-control", arrayPrefix + "vec4 sampleTexture(sampler2D[2] textures) { return texture(textures[0], vec2(0)); }\nvoid main() { color=sampleTexture(tex); }\n", ShaderType.FRAGMENT);
            check(arrayControl.success(), "array suffix on sampler type: valid GLSL syntax" + (arrayControl.success() ? "" : " -> " + arrayControl.error()));
            fixture("array-type-suffix-sampler", arrayPrefix + "vec4 sampleTexture(sampler2D[2] sampler) { return texture(sampler[0], vec2(0)); }\nvoid main() { color=sampleTexture(tex); }\n", output);
            if (args.length > 1) {
                Path fixedDirectory = output.resolve("actual-helper-dumps"); Files.createDirectories(fixedDirectory);
                try (var files = Files.list(Path.of(args[1]))) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".glsl")).sorted().toList()) {
                        String name = file.getFileName().toString(), original = Files.readString(file), fixed = patch(original);
                        ShaderType stage = name.endsWith(".vert.glsl") ? ShaderType.VERTEX : ShaderType.FRAGMENT;
                        var before = compile("dump-original", name, original, stage);
                        var after = compile("dump-actual-helper", name, fixed, stage);
                        check(after.success(), name + ": actual helper compiles" + (after.success() ? "" : " -> " + after.error()));
                        check(fixed.equals(patch(fixed)), name + ": idempotent");
                        check(original.lines().count() == fixed.lines().count(), name + ": diagnostic lines retained");
                        if (stage == ShaderType.VERTEX) check(original.equals(fixed), name + ": vertex unchanged");
                        Files.writeString(fixedDirectory.resolve(name), fixed);
                        System.out.printf("DUMP %s original=%s actual-helper=%s bytes=%d%n", name, before.success(), after.success(), after.spirvBytes());
                    }
                }
            }
        } finally { ((AutoCloseable) compiler).close(); }
        var report = new LinkedHashMap<String,Object>();
        report.put("compilerLocation", type.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("helperLocation", IrisVulkanShaderCompatibility.class.getProtectionDomain().getCodeSource().getLocation().toString());
        report.put("gpuInvocations", 0); report.put("checks", checks); report.put("failures", failures); report.put("compilation", compilation);
        Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.printf("IRIS_VULKAN_SAMPLER_COMPATIBILITY_%s: %d checks; %d failures; %d real shaderc invocations; no GPU%n", failures.isEmpty() ? "PASS" : "FAIL", checks, failures.size(), compilation.size());
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }
}
