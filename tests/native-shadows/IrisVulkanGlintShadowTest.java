package net.irisshaders.iris.vulkan;

import net.minecraft.client.renderer.RenderPipelines;

/** Runs the production policy against the real 26.3 render pipelines without a GPU. */
public final class IrisVulkanGlintShadowTest {
    public static void main(String[] args) {
        require(IrisVulkanShadowDrawPolicy.shouldSkip(true, RenderPipelines.GLINT),
            "The colorless enchantment overlay must not enter native shadow shader compilation");
        require(!IrisVulkanShadowDrawPolicy.shouldSkip(false, RenderPipelines.GLINT),
            "Visible enchantment glint must remain in the ordinary world/hand pass");
        for (var physical : new com.mojang.renderpearl.api.pipeline.RenderPipeline[]{RenderPipelines.ITEM_CUTOUT,
                RenderPipelines.ITEM_TRANSLUCENT, RenderPipelines.ENTITY_CUTOUT, RenderPipelines.ARMOR_CUTOUT_NO_CULL, RenderPipelines.ENTITY_SOLID_GLINT, RenderPipelines.ITEM_CUTOUT_GLINT,
                RenderPipelines.ITEM_TRANSLUCENT_GLINT, RenderPipelines.ITEM_CUTOUT_GLINT_SPECIAL,
                RenderPipelines.ITEM_TRANSLUCENT_GLINT_SPECIAL, RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT}) {
            require(!IrisVulkanShadowDrawPolicy.shouldSkip(true, physical),
                "Base held/dropped item, entity and armor geometry must keep casting: " + physical.getLocation());
        }
        require(!IrisVulkanShadowDrawPolicy.shouldSkip(false, RenderPipelines.TRANSLUCENT_PARTICLE),
            "Wind-burst particles in the main view are unaffected");
        System.out.println("IRIS_VULKAN_GLINT_SHADOW_PASS: overlay excluded only from native shadows; item/armor bases and main-view glint retained");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
