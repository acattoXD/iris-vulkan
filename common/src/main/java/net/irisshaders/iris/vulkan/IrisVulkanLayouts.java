package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.ArrayList;
import java.util.List;

/** RenderPearl puts sampled textures and buffers in the same descriptor list. */
public final class IrisVulkanLayouts {
    private static final IrisVulkanIdentityCache<List<BindGroupLayout>, Split> CACHE = new IrisVulkanIdentityCache<>();
    private IrisVulkanLayouts() { }
    public static List<String> samplers(List<BindGroupLayout> layouts) {
        return split(layouts).samplers();
    }
    public static List<BindGroupLayout.UniformDescription> buffers(List<BindGroupLayout> layouts) {
        return split(layouts).buffers();
    }

    private static Split split(List<BindGroupLayout> layouts) {
        // RenderPipeline owns an immutable List.copyOf result, so normal draws
        // keep its identity. Mutable callers get a fresh immutable snapshot.
        List<BindGroupLayout> key = List.copyOf(layouts);
        Split cached = CACHE.get(key);
        if (cached != null) return cached;
        List<String> samplers = new ArrayList<>();
        List<BindGroupLayout.UniformDescription> buffers = new ArrayList<>();
        for (BindGroupLayout layout : key) {
            for (BindGroupLayout.UniformDescription uniform : layout.uniforms()) {
                if (uniform.type() == UniformType.COMBINED_IMAGE_SAMPLER) samplers.add(uniform.name());
                else buffers.add(uniform);
            }
        }
        Split result = new Split(List.copyOf(samplers), List.copyOf(buffers));
        CACHE.put(key, result);
        return result;
    }

    private record Split(List<String> samplers, List<BindGroupLayout.UniformDescription> buffers) { }
}
