package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Objects;

/** Surface overlays do not add physical geometry to the native shadow map. */
public final class IrisVulkanShadowDrawPolicy {
    private IrisVulkanShadowDrawPolicy() { }

    public static boolean shouldSkip(boolean nativeShadowPass, RenderPipeline pipeline) {
        if (!nativeShadowPass) return false;
        // Only the separate overlay has no physical geometry. The 26.3 GLINT
        // define on ordinary entity/item shaders combines foil with the actual
        // base mesh, which must still cast a shadow.
        return Objects.equals(pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX), RenderPipelines.GLINT.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
            && Objects.equals(pipeline.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT), RenderPipelines.GLINT.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT));
    }
}
