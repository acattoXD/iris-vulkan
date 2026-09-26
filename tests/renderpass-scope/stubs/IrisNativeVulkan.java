package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

/** No pack/world is needed to test ownership and the normal missing-Globals contract. */
public final class IrisNativeVulkan {
    public static ShaderKey worldShaderKey(RenderPipeline pipeline) { return null; }
    public static String screenPassDummySamplers() { return ""; }
}
