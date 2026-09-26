package net.irisshaders.iris.vulkan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** CPU-only resource preflight tests; does not initialize a game or graphics device. */
public final class IrisVulkanPackCapabilitiesTest {
    public static void main(String[] args) throws Exception {
        String high = """
                #version 450 core
                uniform sampler2D colortex0;
                uniform sampler2DShadow shadowtex0;
                void main() { vec4 value = texture(colortex0, vec2(0.5)); float shadow = texture(shadowtex0,vec3(0.5)); }
                """;
        require(IrisVulkanPackCapabilities.sourceRequirements("high", high, name -> false).isEmpty(), "ordinary color/shadow samplers rejected");
        String unused = """
                // buffer Imaginary { float value; };
                uniform sampler3D optionalVolume;
                uniform uimage3D optionalImage;
                void main() { }
                """;
        require(IrisVulkanPackCapabilities.sourceRequirements("unused", unused, name -> false).isEmpty(), "unused declarations or comments rejected");
        String advanced = """
                uniform sampler3D floodfill_sampler;
                uniform usampler3D wsr_sampler;
                layout(r16ui) uniform uimage3D voxel_img;
                layout(std430) buffer blockDataBuffer { uint entries[]; } blockDataSSBO;
                void main() {
                    vec4 flood = texture(floodfill_sampler,vec3(0.5));
                    uvec4 voxel = texture(wsr_sampler,vec3(0.5)) + imageLoad(voxel_img,ivec3(0));
                    uint block = blockDataSSBO.entries[0];
                }
                """;
        var failures = IrisVulkanPackCapabilities.sourceRequirements("ultra", advanced, name -> false);
        for (String resource : List.of("floodfill_sampler", "wsr_sampler", "voxel_img", "blockDataBuffer")) {
            require(failures.stream().anyMatch(failure -> failure.resource().equals(resource)), "missing rejection for " + resource);
        }
        String staticVolume = "uniform sampler3D lut;\nvoid main() { vec4 value = texture(lut,vec3(0.5)); }";
        require(IrisVulkanPackCapabilities.sourceRequirements("lut", staticVolume, "lut"::equals).isEmpty(), "supported static-volume adapter rejected");
        var result = new IrisVulkanPackCapabilities.Result(failures);
        require(!result.supported(), "unsupported configuration accepted");
        try { result.requireSupported(); throw new AssertionError("requireSupported did not reject"); }
        catch (UnsupportedOperationException expected) { require(expected.getMessage().contains("shader storage"), "missing actionable feature detail"); }
        int sources = 0;
        if (args.length > 0) {
            try (var paths = Files.list(Path.of(args[0]))) {
                for (Path path : paths.filter(file -> file.toString().endsWith(".fsh") || file.toString().endsWith(".vsh")).toList()) {
                    var actual = IrisVulkanPackCapabilities.sourceRequirements(path.getFileName().toString(), Files.readString(path), name -> false);
                    require(actual.isEmpty(), "verified HIGH source rejected: " + path + " " + actual);
                    sources++;
                }
            }
        }
        System.out.println("IRIS_VULKAN_PACK_CAPABILITIES_PASS: active/unused resources, static-volume exception, explicit rejection, " + sources + " real HIGH sources");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
