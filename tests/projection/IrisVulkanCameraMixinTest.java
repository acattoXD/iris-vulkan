package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.mixin.IrisMixinPlugin;

import java.nio.file.Files;

/** Exercises the production plugin's actual filter with an isolated platform stub. */
public final class IrisVulkanCameraMixinTest {
    public static void main(String[] args) throws Exception {
        var gameDir = Files.createTempDirectory("iris-camera-mixin-");
        System.setProperty("iris.test.gameDir", gameDir.toString());
        try {
            IrisMixinPlugin plugin = new IrisMixinPlugin();
            for (boolean vulkan : new boolean[] {false, true}) {
                IrisMixinPlugin.usingVulkan = vulkan;
                require(plugin.shouldApplyMixin("net.minecraft.client.renderer.GameRenderer",
                        "net.irisshaders.iris.mixin.MixinModelViewBobbing"),
                        "Camera effects must enter model-view with Vulkan=" + vulkan);
                require(plugin.shouldApplyMixin("net.minecraft.client.renderer.LevelRenderer",
                        "net.irisshaders.iris.mixin.MixinLevelRenderer") == !vulkan,
                        "OpenGL-only renderer stays filtered with Vulkan=" + vulkan);
                require(plugin.shouldApplyMixin("net.minecraft.client.renderer.GameRenderer",
                        "net.irisshaders.iris.mixin.vulkan.VKOnly_MixinGameRenderer_WorldPasses") == vulkan,
                        "Native-only renderer stays filtered with Vulkan=" + vulkan);
            }
        } finally {
            Files.delete(gameDir);
            System.clearProperty("iris.test.gameDir");
        }
        System.out.println("IRIS_VULKAN_CAMERA_MIXIN_PASS: production filter selects camera effects on both backends");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
