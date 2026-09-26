package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanComputeExecutor;
import net.irisshaders.iris.vulkan.IrisVulkanStoragePipeline;
import net.irisshaders.iris.vulkan.IrisVulkanStorageResources;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Per-capture resource evidence for any current pack/profile; no 5.8.1 allocation assumptions. */
public final class NativePackResourceEvidence {
    private NativePackResourceEvidence() { }

    public static void write(Minecraft client, Path run, String label) throws Exception {
        String expectedProfile = System.getProperty("iris.vulkan.probe.expectedProfile");
        if (expectedProfile == null) return;
        var pack = Iris.getCurrentPack().orElseThrow();
        Path packPath = Iris.getShaderpacksDirectory().resolve(Iris.getCurrentPackName());
        String properties;
        if (Files.isDirectory(packPath)) properties = Files.readString(packPath.resolve("shaders/shaders.properties"));
        else try (var zip = new ZipFile(packPath.toFile())) {
            properties = new String(zip.getInputStream(zip.getEntry("shaders/shaders.properties")).readAllBytes(), StandardCharsets.UTF_8);
        }
        var profile = Pattern.compile("(?m)^\\s*profile\\." + Pattern.quote(expectedProfile) + "\\s*=\\s*(.+)$").matcher(properties.replace("\\\n", ""));
        require(profile.find(), "Pack has no requested profile " + expectedProfile);
        Map<String, String> selected = new TreeMap<>();
        var values = pack.getShaderPackOptions().getOptionValues();
        for (String token : profile.group(1).trim().split("\\s+")) {
            require(!token.startsWith("profile."), "Inherited profile requires expansion before this resource probe");
            String[] entry = token.split("=", 2);
            String name = entry[0].startsWith("!") ? entry[0].substring(1) : entry[0];
            String expected = entry.length == 2 ? entry[1] : Boolean.toString(!entry[0].startsWith("!"));
            String actual = entry.length == 2 ? values.getStringValueOrDefault(name) : Boolean.toString(values.getBooleanValueOrDefault(name));
            require(expected.equals(actual), "Profile " + expectedProfile + " mismatch: " + name + "=" + actual + " expected " + expected);
            selected.put(name, actual);
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("pack", Iris.getCurrentPackName());
        report.put("profile", expectedProfile);
        report.put("profileInfo", pack.getProfileInfo());
        report.put("selectedProfileOptions", selected);
        report.put("label", label);
        report.put("dimension", String.valueOf(Iris.getCurrentDimension()));
        report.put("rain", client.level == null ? 0 : client.level.getRainLevel(1));
        var images = new ArrayList<Map<String, Object>>();
        for (var information : pack.getIrisCustomImages()) {
            var image = IrisVulkanStorageResources.image(information.name());
            require(image != null && !image.isClosed() && image.vkImage() != 0 && image.vkImageView() != 0, "Missing live image " + information.name());
            var spec = IrisVulkanStorageResources.describe(information, client.gameRenderer.mainRenderTarget().width, client.gameRenderer.mainRenderTarget().height);
            require(image.width() == spec.width() && image.height() == spec.height() && image.depth() == spec.depth() && image.vkFormat() == spec.vkFormat(), "Image metadata mismatch " + information.name());
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("image", information.name()); state.put("sampler", information.samplerName()); state.put("spec", spec);
            state.put("imageHandle", image.vkImage()); state.put("viewHandle", image.vkImageView()); state.put("samplerHandle", image.vkSampler());
            if (information.samplerName() != null) {
                require(IrisVulkanStorageResources.sampledImage(information.samplerName()) == image && image.vkSampler() != 0, "Sampled storage association lost " + information.name());
                require(IrisVulkanStoragePipeline.isStorageSampler(information.samplerName()), "Custom sampler not routed as advanced resource");
            }
            images.add(state);
        }
        report.put("images", images);
        var dispatch = IrisVulkanComputeExecutor.lastDispatch();
        if (!pack.getIrisCustomImages().isEmpty()) require(dispatch != null && dispatch.sequence() > 0, "No actual compute dispatch observed for custom-image pack");
        report.put("lastComputeDispatch", dispatch);
        report.put("verificationLimit", "Live allocations, sampler associations, exact selected profile and compute sequence; this is not a readback of puddle texel values or a visual verdict");
        Files.createDirectories(run.resolve("evidence"));
        Files.writeString(run.resolve("evidence").resolve(label.replaceFirst("\\.png$", "") + "-pack-resources.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
