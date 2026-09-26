package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import net.irisshaders.iris.vulkan.IrisVulkanDeviceFeatures;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;

/** Extend the selected device's aggregate, preserving all required/optional feature groups. */
@Mixin(VulkanBackend.class)
public class VKOnly_MixinVulkanBackend_StorageFeatures {
    @WrapOperation(
        method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
        at = @At(value = "NEW", target = "(Ljava/lang/String;Ljava/util/Collection;)Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;"),
        require = 1
    )
    private FeatureSet iris$selectStorageDeviceFeatures(String name, Collection<FeatureSet> requested,
            Operation<FeatureSet> original, @Local VulkanPhysicalDevice physicalDevice) {
        FeatureSet selected = original.call(name, requested);
        var features = IrisVulkanDeviceFeatures.withSupportedStorageFeatures(physicalDevice.vkPhysicalDevice(), selected.features());
        // This aggregate is passed both to vkCreateDevice and the VulkanDevice constructor.
        return new FeatureSet(selected.name(), selected.extensions(), features, selected.condition());
    }

    @WrapOperation(
        method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"),
        require = 1
    )
    private VkDevice iris$recordStorageDeviceFeatures(FeatureSet selected, VulkanPhysicalDevice physicalDevice,
            Operation<VkDevice> original) {
        VkDevice device = original.call(selected, physicalDevice);
        IrisVulkanDeviceFeatures.recordCreatedDevice(device, selected.features());
        return device;
    }
}
