package net.irisshaders.iris.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;

/** CPU-only declaration-pruning checks, including the large blank-line regression. */
public final class IrisVulkanShaderPruningTest {
    public static void main(String[] args) throws Exception {
        String source = """
                #version 450 core
                // unusedTexture is deliberately documented, not referenced.
                uniform sampler2D unusedTexture;
                uniform sampler2D textureInUse;
                uniform float unusedValue;
                uniform float usedValue;
                uniform vec3 retainedArray[3];
                uniform float sharedA, sharedB;
                layout(std140) uniform Block { float blockMember; };
                /* usedValue and unusedTexture in comments must not affect counts. */
                void main() {
                    vec4 color = texture(textureInUse, vec2(usedValue));
                    color.rgb += retainedArray[0];
                }
                """;
        String pruned = IrisVulkanShaderPruning.removeUnusedUniforms(source);
        missing(pruned, "uniform sampler2D unusedTexture;");
        missing(pruned, "uniform float unusedValue;");
        retained(pruned, "uniform sampler2D textureInUse;");
        retained(pruned, "uniform float usedValue;");
        retained(pruned, "uniform vec3 retainedArray[3];");
        retained(pruned, "uniform float sharedA, sharedB;");
        retained(pruned, "layout(std140) uniform Block { float blockMember; };");
        if (source.lines().count() != pruned.lines().count()) throw new AssertionError("Source line numbers changed");

        String large = "\n".repeat(100_000) + source;
        long before = System.nanoTime();
        String largePruned = IrisVulkanShaderPruning.removeUnusedUniforms(large);
        long elapsed = System.nanoTime() - before;
        missing(largePruned, "uniform sampler2D unusedTexture;");
        if (elapsed > 5_000_000_000L) throw new AssertionError("Blank-line scan took " + elapsed / 1_000_000L + " ms");
        System.out.println("IRIS_VULKAN_SHADER_PRUNING_PASS: comments, live references, arrays, blocks, line numbers, 100000 blank lines in "
                + elapsed / 1_000_000L + " ms");
        testInputs();
        if (args.length > 0) {
            String dump = Files.readString(Path.of(args[0]));
            String prunedDump = IrisVulkanShaderPruning.removeUnusedInputs(dump);
            missing(prunedDump, "in mat3 tbnMatrix;");
            missing(prunedDump, "in vec3 viewVector;");
            retained(prunedDump, "in vec4 texcoord;");
            if (dump.length() != prunedDump.length()) throw new AssertionError("Dump character offsets changed");
            System.out.println("IRIS_VULKAN_SILDUR_INPUT_PRUNING_PASS: removed absent unused varyings from actual terrain dump");
        }
    }

    private static void testInputs() {
        String source = """
                #version 450 core
                // tbnMatrix and viewVector are declared by Sildur's but unused.
                in mat3 tbnMatrix;
                in vec3 viewVector;
                in vec4 texcoord;
                in vec3 unreferencedArray[3];
                in vec3 firstShared, secondShared;
                flat in vec3 retainedQualifier;
                in highp vec3 retainedPrecision;
                layout(location = 3) in vec3 retainedLayout;
                in InterfaceBlock { vec3 member; } retainedBlock;
                in CustomType retainedType;
                in vec3 macroInput;
                #define READ_INPUT macroInput
                #define DECLARE_INPUT \\
                    in vec3 macroDeclaration;
                /* texcoord in this comment is irrelevant, but its real use matters. */
                void main() { vec4 color = texcoord; }
                """;
        String pruned = IrisVulkanShaderPruning.removeUnusedInputs(source);
        missing(pruned, "in mat3 tbnMatrix;");
        missing(pruned, "in vec3 viewVector;");
        for (String declaration : new String[]{
                "in vec4 texcoord;", "in vec3 unreferencedArray[3];", "in vec3 firstShared, secondShared;",
                "flat in vec3 retainedQualifier;", "in highp vec3 retainedPrecision;",
                "layout(location = 3) in vec3 retainedLayout;", "in InterfaceBlock { vec3 member; } retainedBlock;",
                "in CustomType retainedType;", "in vec3 macroInput;", "in vec3 macroDeclaration;"}) {
            retained(pruned, declaration);
        }
        if (source.length() != pruned.length() || source.lines().count() != pruned.lines().count()) {
            throw new AssertionError("Input pruning changed source locations");
        }
        String tokenPaste = "#define READ(name) name ## Input\nin vec3 manufacturedInput;\n";
        if (!tokenPaste.equals(IrisVulkanShaderPruning.removeUnusedInputs(tokenPaste))) {
            throw new AssertionError("Cannot prove inputs unused in the presence of token-pasting macros");
        }
        String large = "\r\n".repeat(100_000) + source.replace("\n", "\r\n");
        long before = System.nanoTime();
        String largePruned = IrisVulkanShaderPruning.removeUnusedInputs(large);
        long elapsed = System.nanoTime() - before;
        missing(largePruned, "in mat3 tbnMatrix;");
        if (large.length() != largePruned.length()) throw new AssertionError("CRLF character offsets changed");
        if (elapsed > 5_000_000_000L) throw new AssertionError("Input blank-line scan took " + elapsed / 1_000_000L + " ms");
        System.out.println("IRIS_VULKAN_INPUT_PRUNING_PASS: comments, references, conservative declarations, macros, CRLF, 100000 blank lines in "
                + elapsed / 1_000_000L + " ms");
    }

    private static void retained(String source, String declaration) {
        if (!source.contains(declaration)) throw new AssertionError("Removed live/unsupported declaration: " + declaration);
    }

    private static void missing(String source, String declaration) {
        if (source.contains(declaration)) throw new AssertionError("Unused declaration retained: " + declaration);
    }
}
