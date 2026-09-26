package net.irisshaders.iris.probe;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import net.irisshaders.iris.vulkan.IrisVulkanStorageResources;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Real profile reloads for the explicitly enabled disposable Ultra scenario run. */
final class NativeQualityCycle {
    private static final Map<String, String> HIGH_OPTIONS = Map.of(
        "ANISOTROPIC_FILTER", "0", "CLOUD_QUALITY", "2", "COLORED_LIGHTING", "0", "DETAIL_QUALITY", "2",
        "LIGHTSHAFT_QUALI_DEFINE", "2", "SHADOW_QUALITY", "2", "WORLD_SPACE_REFLECTIONS", "-1", "shadowDistance", "192.0");
    private static final Set<String> ULTRA_IMAGES = Set.of(
        "voxel_img", "floodfill_img", "floodfill_img_copy", "wsr_img", "wsr_lod_img");

    private NativeQualityCycle() { }

    static void reload(boolean ultra) throws IOException {
        requireEnabled();
        Properties properties = new Properties();
        properties.putAll(ultra ? NativeUltraReadback.ULTRA_OPTIONS : HIGH_OPTIONS);
        // This is the same option queue and reload path used by the profile UI.
        Iris.queueShaderPackOptionsFromProperties(properties);
        Iris.reload();
    }

    static void requireSettled(boolean ultra, Object previousPack) {
        requireEnabled();
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        String expectedProfile = ultra ? "ULTRA" : "HIGH";
        if (pack == previousPack) throw new IllegalStateException("Quality " + expectedProfile + " did not replace the pack; "
            + Iris.getNativeSettingsRejection().orElse("no rejection message"));
        if (!pack.getProfileInfo().startsWith("Profile: " + expectedProfile + " ("))
            throw new IllegalStateException("Expected profile " + expectedProfile + ", got " + pack.getProfileInfo());
        Map<String, String> expectedOptions = ultra ? NativeUltraReadback.ULTRA_OPTIONS : HIGH_OPTIONS;
        if (!selectedOptions(pack).equals(expectedOptions))
            throw new IllegalStateException("Wrong options after quality " + expectedProfile + ": " + selectedOptions(pack));
        if (!Iris.getShaderPackOptionQueue().isEmpty())
            throw new IllegalStateException("Quality reload left options queued");
        if (ultra) {
            NativeUltraReadback.requireUltraOptions(pack);
            if (!IrisVulkanStorageResources.active() || pack.getIrisCustomImages().size() != 5 || pack.getBufferObjects().size() != 1)
                throw new IllegalStateException("Ultra quality reload did not create five storage images and one buffer");
            for (String name : ULTRA_IMAGES) {
                var image = IrisVulkanStorageResources.image(name);
                if (image == null || image.isClosed() || image.vkImage() == 0 || image.vkImageView() == 0 || image.vkSampler() == 0)
                    throw new IllegalStateException("Ultra quality reload is missing a live native image: " + name);
            }
            var buffer = IrisVulkanStorageResources.buffer(0);
            if (buffer == null || buffer.isClosed() || buffer.vkBuffer() == 0 || buffer.size() != 810_549_248L)
                throw new IllegalStateException("Ultra quality reload is missing the live SSBO 0 allocation");
            if (IrisNativeVulkan.computeDispatchCount() <= 0)
                throw new IllegalStateException("The reloaded Ultra pipeline has not dispatched compute");
        } else if (IrisVulkanStorageResources.active() || !pack.getIrisCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty()
                || IrisNativeVulkan.computeDispatchCount() != 0) {
            throw new IllegalStateException("High quality reload retained Ultra storage or compute state");
        }
    }

    static Map<String, String> selectedOptions(ShaderPack pack) {
        var options = pack.getShaderPackOptions().getOptionValues();
        Map<String, String> result = new TreeMap<>();
        for (String name : HIGH_OPTIONS.keySet()) result.put(name, options.getStringValueOrDefault(name));
        return result;
    }

    private static void requireEnabled() {
        if (!Boolean.getBoolean("iris.vulkan.probe.ultra"))
            throw new IllegalStateException("Quality cycling requires an explicitly enabled isolated Ultra probe");
    }
}
