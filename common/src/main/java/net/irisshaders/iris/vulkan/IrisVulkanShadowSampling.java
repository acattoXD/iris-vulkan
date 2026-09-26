package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Software depth comparison for native Vulkan's non-comparison texture API. */
public final class IrisVulkanShadowSampling {
    private static final Pattern DECLARATION = Pattern.compile(
            "(?m)^(\\h*(?:layout\\h*\\([^)]*\\)\\h*)?uniform\\h+(?:(?:lowp|mediump|highp)\\h+)?)sampler2DShadow(\\h+(shadowtex[01](?:HW)?|shadow|watershadow)\\h*;)"
    );
    private static final Pattern IDENTITY_TEXTURE2D_SHADOW = Pattern.compile(
            "(?s)\\bfloat\\s+texture2DShadow\\s*\\(\\s*sampler2DShadow\\s+([A-Za-z_]\\w*)\\s*,\\s*vec3\\s+([A-Za-z_]\\w*)\\s*\\)\\s*\\{\\s*return\\s+(?:"
                    + "vec4\\s*\\(\\s*texture\\s*\\(\\s*\\1\\s*,\\s*\\2\\s*\\)\\s*\\)\\s*\\.x"
                    + "|texture\\s*\\(\\s*\\1\\s*,\\s*\\2\\s*\\))\\s*;\\s*\\}"
    );
    private static final Map<String, String> FUNCTIONS = Map.ofEntries(
            Map.entry("texture", ""), Map.entry("textureLod", "_lod"),
            Map.entry("textureOffset", "_offset"), Map.entry("textureLodOffset", "_lod_offset"),
            Map.entry("textureProj", "_proj"), Map.entry("textureProjLod", "_proj_lod"),
            Map.entry("textureGrad", "_grad"), Map.entry("textureGradOffset", "_grad_offset"),
            Map.entry("textureGather", "_gather"), Map.entry("textureGatherOffset", "_gather_offset")
    );

    private IrisVulkanShadowSampling() { }

    public static String patch(ProgramSet programs, String source, boolean fragmentShader) {
        return patch(programs.getPackDirectives().getShadowDirectives(), source, fragmentShader);
    }

    public static String patch(PackShadowDirectives directives, String source, boolean fragmentShader) {
        if (source == null || !source.contains("sampler2DShadow")) return source;
        Map<String, Sampling> samplers = new LinkedHashMap<>();
        Matcher declarations = DECLARATION.matcher(source);
        while (declarations.find()) {
            String sampler = declarations.group(3);
            int index = sampler.startsWith("shadowtex1") || sampler.equals("watershadow") ? 1 : 0;
            var settings = directives.getDepthSamplingSettings().get(index);
            samplers.put(sampler, new Sampling(settings.getNearest(), settings.getMipmap()));
        }
        return patch(source, fragmentShader, samplers);
    }

    record Sampling(boolean nearest, boolean mipmap) { }

    static String patch(String source, boolean fragmentShader, Map<String, Sampling> samplers) {
        if (samplers.isEmpty()) return source;

        String patched = source;
        boolean identityTexture2DShadow = IDENTITY_TEXTURE2D_SHADOW
                .matcher(IrisVulkanShaderPruning.maskComments(source)).find();
        for (String sampler : samplers.keySet()) {
            for (var function : FUNCTIONS.entrySet()) {
                patched = Pattern.compile("\\b" + function.getKey() + "\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,")
                        .matcher(patched).replaceAll(Matcher.quoteReplacement(prefix(sampler) + function.getValue() + "("));
            }
            // Shader packs commonly wrap comparison sampling in a helper named
            // texture2DShadow(sampler2DShadow, vec3). The uniform declaration
            // is lowered to sampler2D above, so calls into that helper would
            // otherwise pass a sampler2D to its sampler2DShadow parameter and
            // fail GLSL overload resolution. Route calls for each known shadow
            // texture directly to the lowered software-comparison helper. The
            // helper declaration remains valid, while the native path retains
            // the pack's comparison semantics instead of disabling shadows.
            if (identityTexture2DShadow) {
                patched = Pattern.compile("\\btexture2DShadow\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,")
                        .matcher(patched).replaceAll(Matcher.quoteReplacement(prefix(sampler) + "("));
            }
        }

        Matcher matcher = DECLARATION.matcher(patched);
        StringBuffer result = new StringBuffer(patched.length());
        while (matcher.find()) {
            String sampler = matcher.group(3);
            String replacement = matcher.group(1) + "sampler2D" + matcher.group(2) + "\n"
                    + helpers(sampler, samplers.get(sampler), fragmentShader);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String prefix(String sampler) {
        return "iris_vulkan_shadow_compare_" + sampler;
    }

    private static String helpers(String sampler, Sampling settings, boolean fragmentShader) {
        String name = prefix(sampler);
        StringBuilder glsl = new StringBuilder();
        glsl.append("float ").append(name).append("_fetch(ivec2 pixel, int level, float reference) {\n")
                .append("    ivec2 size = textureSize(").append(sampler).append(", level);\n")
                .append("    float depth = texelFetch(").append(sampler)
                .append(", clamp(pixel, ivec2(0), size - ivec2(1)), level).r;\n")
                .append("    return step(reference, depth);\n}\n");

        glsl.append("float ").append(name).append("_level(vec3 coord, int level, ivec2 offset) {\n")
                .append("    vec2 size = vec2(textureSize(").append(sampler).append(", level));\n");
        if (settings.nearest()) {
            glsl.append("    return ").append(name).append("_fetch(ivec2(floor(coord.xy * size)) + offset, level, coord.z);\n");
        } else {
            // Compare each neighboring depth before filtering. Comparing an interpolated
            // depth creates hard, shifted shadow edges and is not hardware PCF semantics.
            glsl.append("    vec2 position = coord.xy * size - vec2(0.5);\n")
                    .append("    ivec2 pixel = ivec2(floor(position)) + offset;\n")
                    .append("    vec2 weight = fract(position);\n")
                    .append("    float lower = mix(").append(name).append("_fetch(pixel, level, coord.z), ")
                    .append(name).append("_fetch(pixel + ivec2(1, 0), level, coord.z), weight.x);\n")
                    .append("    float upper = mix(").append(name).append("_fetch(pixel + ivec2(0, 1), level, coord.z), ")
                    .append(name).append("_fetch(pixel + ivec2(1, 1), level, coord.z), weight.x);\n")
                    .append("    return mix(lower, upper, weight.y);\n");
        }
        glsl.append("}\n");

        glsl.append("float ").append(name).append("_lod_offset(vec3 coord, float lod, ivec2 offset) {\n");
        if (!settings.mipmap()) {
            glsl.append("    return ").append(name).append("_level(coord, 0, offset);\n");
        } else {
            glsl.append("    float level = clamp(lod, 0.0, float(textureQueryLevels(").append(sampler).append(") - 1));\n");
            if (settings.nearest()) {
                glsl.append("    return ").append(name).append("_level(coord, int(floor(level + 0.5)), offset);\n");
            } else {
                glsl.append("    int lower = int(floor(level));\n")
                        .append("    int upper = min(lower + 1, textureQueryLevels(").append(sampler).append(") - 1);\n")
                        .append("    return mix(").append(name).append("_level(coord, lower, offset), ")
                        .append(name).append("_level(coord, upper, offset), fract(level));\n");
            }
        }
        glsl.append("}\n")
                .append("float ").append(name).append("_lod(vec3 coord, float lod) { return ")
                .append(name).append("_lod_offset(coord, lod, ivec2(0)); }\n")
                .append("float ").append(name).append("_implicit_lod(vec2 coord) { return ");
        if (settings.mipmap() && fragmentShader) {
            glsl.append("textureQueryLod(").append(sampler).append(", coord).x");
        } else glsl.append("0.0");
        glsl.append("; }\n")
                .append("float ").append(name).append("_offset(vec3 coord, ivec2 offset, float bias) { return ")
                .append(name).append("_lod_offset(coord, ").append(name).append("_implicit_lod(coord.xy) + bias, offset); }\n")
                .append("float ").append(name).append("_offset(vec3 coord, ivec2 offset) { return ")
                .append(name).append("_offset(coord, offset, 0.0); }\n")
                .append("float ").append(name).append("(vec3 coord, float bias) { return ")
                .append(name).append("_offset(coord, ivec2(0), bias); }\n")
                .append("float ").append(name).append("(vec3 coord) { return ")
                .append(name).append("(coord, 0.0); }\n")
                .append("float ").append(name).append("_proj(vec4 coord) { return ")
                .append(name).append("(coord.xyz / coord.w); }\n")
                .append("float ").append(name).append("_proj(vec4 coord, float bias) { return ")
                .append(name).append("(coord.xyz / coord.w, bias); }\n")
                .append("float ").append(name).append("_proj_lod(vec4 coord, float lod) { return ")
                .append(name).append("_lod(coord.xyz / coord.w, lod); }\n")
                .append("float ").append(name).append("_grad_offset(vec3 coord, vec2 dx, vec2 dy, ivec2 offset) {\n")
                .append("    vec2 size = vec2(textureSize(").append(sampler).append(", 0));\n")
                .append("    vec2 sx = dx * size; vec2 sy = dy * size;\n")
                .append("    float lod = 0.5 * log2(max(max(dot(sx, sx), dot(sy, sy)), 1.0e-20));\n")
                .append("    return ").append(name).append("_lod_offset(coord, lod, offset);\n}\n")
                .append("float ").append(name).append("_grad(vec3 coord, vec2 dx, vec2 dy) { return ")
                .append(name).append("_grad_offset(coord, dx, dy, ivec2(0)); }\n")
                .append("vec4 ").append(name).append("_gather_offset(vec2 coord, float reference, ivec2 offset) {\n")
                .append("    ivec2 pixel = ivec2(floor(coord * vec2(textureSize(").append(sampler).append(", 0)) - vec2(0.5))) + offset;\n")
                .append("    return vec4(").append(name).append("_fetch(pixel + ivec2(0, 1), 0, reference), ")
                .append(name).append("_fetch(pixel + ivec2(1, 1), 0, reference), ")
                .append(name).append("_fetch(pixel + ivec2(1, 0), 0, reference), ")
                .append(name).append("_fetch(pixel, 0, reference));\n}\n")
                .append("vec4 ").append(name).append("_gather(vec2 coord, float reference) { return ")
                .append(name).append("_gather_offset(coord, reference, ivec2(0)); }\n");
        return glsl.toString();
    }
}
