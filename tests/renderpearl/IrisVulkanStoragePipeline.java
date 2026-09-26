package net.irisshaders.iris.vulkan;
import java.util.*;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.*;
/** CPU contract boundary: no test ever invokes this GPU method. */
public final class IrisVulkanStoragePipeline {
 public static Set<String> storageSamplerNames(){return Set.of();}
 public static VulkanRenderPipeline compile(VulkanDevice device, BackendRenderPipeline.CreateInfo info, List<IrisVulkanStorageReflection.Binding> bindings){throw new AssertionError("Unexpected GPU call in CPU reflection contract");}
}
