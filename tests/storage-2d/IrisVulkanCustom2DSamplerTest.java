package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import sun.misc.Unsafe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Reads the actual 5.9.1 puddle definition, then exercises production routing and SPIR-V reflection. */
public final class IrisVulkanCustom2DSamplerTest {
    public static void main(String[] args) throws Exception {
        String definition;
        try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
            String properties = new String(zip.getInputStream(zip.getEntry("shaders/shaders.properties")).readAllBytes(), StandardCharsets.UTF_8);
            definition = properties.lines().map(String::trim).filter(line -> line.startsWith("image.puddle_img")).findFirst().orElseThrow();
        }
        var options = new ShaderPackOptions(new IncludeGraph(Path.of("."), ImmutableList.of(), false), Map.of());
        var parsed = new ShaderProperties(definition + "\n", options, List.of());
        var puddle = parsed.getIrisCustomImages().stream().filter(image -> image.name().equals("puddle_img")).findFirst().orElseThrow();
        require(puddle.samplerName().equals("puddle_sampler") && puddle.target() == TextureType.TEXTURE_2D, "Actual puddle image is sampled 2D storage");
        require(puddle.internalTextureFormat() == InternalTextureFormat.R8UI && puddle.width() == 128 && puddle.height() == 128, "Actual puddle format/extent");
        var allocation = IrisVulkanStorageResources.describe(puddle, 1280, 720);
        require(allocation.integer() && allocation.depth() == 1 && allocation.byteSize() == 16384, "Real storage image sizing preserves 2D unsigned integer data");

        // Construct only the real ShaderPack metadata needed for pre-allocation routing.
        // Its normal constructor would initialize Minecraft-dependent pack features.
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (Unsafe) unsafeField.get(null);
        ShaderPack fixture = (ShaderPack) unsafe.allocateInstance(ShaderPack.class);
        var images = ShaderPack.class.getDeclaredField("irisCustomImages");
        images.setAccessible(true);
        images.set(fixture, parsed.getIrisCustomImages());
        Iris.currentPack = fixture;
        try {
            require(IrisNativeVulkan.storageDevelopmentEnabled(), "Test needs the normal enabled native storage path");
            require(IrisVulkanStorageResources.sampledImage("puddle_sampler") == null, "Regression must cover routing before allocation");
            String fragment = """
                    #version 450 core
                    uniform usampler2D puddle_sampler;
                    uniform sampler2D albedo;
                    layout(location=0) out vec4 color;
                    void main() { color = texture(albedo, vec2(0.5)) + vec4(texture(puddle_sampler, vec2(0.5))); }
                    """;
            var resources = IrisVulkanShaderResources.ResourceSet.collect("#version 450 core\nvoid main() { gl_Position = vec4(0); }\n", fragment, List.of(), new LinkedHashSet<>());
            require(resources.samplers().equals(Set.of("albedo")), "Custom 2D sampler must not demand a vanilla bind group: " + resources.samplers());
            require(resources.samplerRequirements().stream().anyMatch(value -> value.name().equals("puddle_sampler") && value.type().equals("usampler2D")), "Type requirement must be retained for compatibility checks");
            require(resources.unsupported().isEmpty(), "Supported mixed samplers were rejected: " + resources.unsupported());
            require(IrisVulkanStoragePipeline.hasAdvancedResources(fragment), "A sampled custom 2D image alone must select the advanced graphics bridge");
            try (var compiler = new GlslCompiler(); var module = compiler.createIntermediary("puddle-2d-regression", fragment, ShaderType.FRAGMENT)) {
                var reflected = IrisVulkanStorageReflection.reflect(module.spirv(), Set.of("puddle_sampler"));
                require(reflected.sampledNames().equals(Set.of("puddle_sampler")), "2D custom sampler must remain in the advanced descriptor reflection");
                require(reflected.resources().size() == 1 && reflected.resources().getFirst().dimension() == org.lwjgl.util.spvc.Spv.SpvDim2D,
                        "Puddle advanced descriptor must retain 2D dimensionality");
                var bindings = IrisVulkanStorageReflection.bindings(1, reflected);
                require(bindings.size() == 1 && bindings.getFirst().kind() == IrisVulkanStorageReflection.Kind.SAMPLED_IMAGE && bindings.getFirst().binding() == 1,
                        "Puddle uses a separate combined image sampler descriptor after ordinary albedo");
            }
            Iris.currentPack = null;
            var ordinary = IrisVulkanShaderResources.ResourceSet.collect("", fragment, List.of(), new LinkedHashSet<>());
            require(ordinary.samplers().equals(Set.of("albedo", "puddle_sampler")), "Unregistered ordinary 2D samplers must not be globally removed by name");
        } finally { Iris.currentPack = null; }
        System.out.println("IRIS_CUSTOM_2D_SAMPLER_PASS: real puddle definition, ResourceSet exclusion, albedo retention, advanced SPIR-V descriptor");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
