package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import static org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceFeatures;

/** Optional features requested before logical-device creation, and the features actually enabled. */
public final class IrisVulkanDeviceFeatures {
    private static final Logger LOGGER = LoggerFactory.getLogger("Iris/VulkanStorage");
    public static final VulkanFeature VERTEX_STORES = new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
        "vertexPipelineStoresAndAtomics");
    public static final VulkanFeature FRAGMENT_STORES = new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
        "fragmentStoresAndAtomics");
    public static final VulkanFeature EXTENDED_STORAGE_FORMATS = new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
        "shaderStorageImageExtendedFormats");
    public static final VulkanFeature ROBUST_BUFFER_ACCESS = new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,
        "robustBufferAccess");
    private static final Enabled UNRECORDED = new Enabled(false, false, false, false, false);
    // VkDevice is retained by VulkanDevice while alive; abandoned/failed devices cannot leak a strong map key.
    private static final Map<VkDevice, Enabled> ENABLED = new WeakHashMap<>();

    private IrisVulkanDeviceFeatures() { }

    /** Missing optional bits must not disqualify a device that can run ordinary shader profiles. */
    public static Set<VulkanFeature> withSupportedStorageFeatures(VkPhysicalDevice physicalDevice, Set<VulkanFeature> requested) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, supported);
            return selectSupported(requested, supported.vertexPipelineStoresAndAtomics(), supported.fragmentStoresAndAtomics(),
                supported.shaderStorageImageExtendedFormats(), supported.robustBufferAccess());
        }
    }

    /** The returned set is a per-device copy; Mojang's global required-feature set stays unchanged. */
    public static Set<VulkanFeature> selectSupported(Set<VulkanFeature> requested, boolean vertexStores,
                                                     boolean fragmentStores, boolean extendedFormats, boolean robustBuffers) {
        Set<VulkanFeature> selected = new LinkedHashSet<>(requested);
        if (vertexStores) selected.add(VERTEX_STORES);
        if (fragmentStores) selected.add(FRAGMENT_STORES);
        if (extendedFormats) selected.add(EXTENDED_STORAGE_FORMATS);
        if (robustBuffers) selected.add(ROBUST_BUFFER_ACCESS);
        return selected;
    }

    /** Call only after vkCreateDevice succeeded with this set of requested features. */
    public static void recordCreatedDevice(VkDevice device, Set<VulkanFeature> requested) {
        Enabled enabled = acceptedFeatures(requested);
        synchronized (ENABLED) {
            ENABLED.put(device, enabled);
        }
        LOGGER.info("Native Vulkan storage device features: vertexStores={}, fragmentStores={}, extendedImageFormats={}, robustBuffers={}.",
            enabled.vertexPipelineStoresAndAtomics(), enabled.fragmentStoresAndAtomics(), enabled.shaderStorageImageExtendedFormats(),
            enabled.robustBufferAccess());
    }

    public static Enabled acceptedFeatures(Set<VulkanFeature> requested) {
        return new Enabled(true, requested.contains(VERTEX_STORES), requested.contains(FRAGMENT_STORES),
            requested.contains(EXTENDED_STORAGE_FORMATS), requested.contains(ROBUST_BUFFER_ACCESS));
    }

    public static Enabled enabled(VulkanDevice device) {
        return device == null ? UNRECORDED : enabled(device.vkDevice());
    }

    public static Enabled enabled(VkDevice device) {
        synchronized (ENABLED) {
            return ENABLED.getOrDefault(device, UNRECORDED);
        }
    }

    public record Enabled(boolean recorded, boolean vertexPipelineStoresAndAtomics, boolean fragmentStoresAndAtomics,
                          boolean shaderStorageImageExtendedFormats, boolean robustBufferAccess) {
        public List<String> missing(boolean vertexWrites, boolean fragmentWrites, boolean extendedFormats) {
            List<String> missing = new ArrayList<>();
            if (vertexWrites && !vertexPipelineStoresAndAtomics) missing.add("vertexPipelineStoresAndAtomics");
            if (fragmentWrites && !fragmentStoresAndAtomics) missing.add("fragmentStoresAndAtomics");
            if (extendedFormats && !shaderStorageImageExtendedFormats) missing.add("shaderStorageImageExtendedFormats");
            return List.copyOf(missing);
        }

        public List<String> missingForStorage() { return missing(true, true, true); }

        /** Pack history lookups can address the previous volume outside an SSBO after camera movement. */
        public List<String> missingForStorage(boolean hasStorageBuffers) {
            List<String> missing = new ArrayList<>(missingForStorage());
            if (hasStorageBuffers && !robustBufferAccess) missing.add("robustBufferAccess");
            return List.copyOf(missing);
        }

        public void require(boolean vertexWrites, boolean fragmentWrites, boolean extendedFormats) {
            requireFeatures(missing(vertexWrites, fragmentWrites, extendedFormats));
        }

        private void requireFeatures(List<String> missing) {
            if (!missing.isEmpty()) {
                throw new UnsupportedOperationException("This shader configuration requires Vulkan device features that were not enabled: "
                    + String.join(", ", missing) + "."
                    + (recorded ? " The graphics device did not advertise support for these optional features."
                        : " The Iris storage-feature device creation hook has not run; the game must start with that hook installed."));
            }
        }

        public void requireStorage() { require(true, true, true); }
        public void requireStorage(boolean hasStorageBuffers) { requireFeatures(missingForStorage(hasStorageBuffers)); }
    }
}
