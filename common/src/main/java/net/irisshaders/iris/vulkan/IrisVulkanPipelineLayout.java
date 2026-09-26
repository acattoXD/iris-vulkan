package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL;

/** Preserve Sodium's push-constant ABI after Iris copies or renames a prepared pipeline. */
public final class IrisVulkanPipelineLayout {
    public static final int SODIUM_PUSH_CONSTANT_BYTES = 20;
    private static final Map<RenderPipeline, Boolean> SODIUM_PIPELINES = Collections.synchronizedMap(new WeakHashMap<>());

    private IrisVulkanPipelineLayout() { }

    public static void registerPreparedPipeline(RenderPipeline pipeline, ShaderKey key) {
        if (key != null && key.patch == Patch.SODIUM) SODIUM_PIPELINES.put(pipeline, Boolean.TRUE);
    }

    /** Called immediately before vkCreatePipelineLayout, after Sodium's own layout hook. */
    public static void preservePushConstants(RenderPipeline pipeline, VkPipelineLayoutCreateInfo layout, MemoryStack stack) {
        if (!SODIUM_PIPELINES.containsKey(pipeline)) return;
        var ranges = layout.pPushConstantRanges();
        if (ranges != null && ranges.hasRemaining()) {
            for (int index = ranges.position(); index < ranges.limit(); index++) {
                var range = ranges.get(index);
                if (range.offset() == 0 && range.size() >= SODIUM_PUSH_CONSTANT_BYTES
                        && (range.stageFlags() & VK_SHADER_STAGE_ALL) == VK_SHADER_STAGE_ALL) return;
            }
            throw new IllegalStateException("Prepared Sodium pipeline has an incompatible push-constant layout: " + pipeline.getLocation());
        }
        // Sodium's namespace-based hook misses iris:native_shadow/... aliases.
        // Match both its 20-byte struct and the ALL stages used by vkCmdPushConstants.
        layout.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                .offset(0).size(SODIUM_PUSH_CONSTANT_BYTES).stageFlags(VK_SHADER_STAGE_ALL));
    }
}
