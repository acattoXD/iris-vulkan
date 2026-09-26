package net.irisshaders.iris.vulkan;

import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;

import java.util.LinkedHashMap;
import java.util.List;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/** Immutable compute limits and descriptor metadata; no per-dispatch driver queries or owned buffers. */
final class IrisVulkanComputeBindings {
	private IrisVulkanComputeBindings() { }

	static DeviceLimits queryLimits(VkDevice device, MemoryStack stack) {
		boolean pushSupported = device.getCapabilities().VK_KHR_push_descriptor;
		var properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
		var push = pushSupported ? VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default() : null;
		if (push != null) properties.pNext(push.address());
		vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), properties);
		var limits = properties.properties().limits();
		return new DeviceLimits(limits.maxComputeWorkGroupSize(0), limits.maxComputeWorkGroupSize(1),
			limits.maxComputeWorkGroupSize(2), Integer.toUnsignedLong(limits.maxComputeWorkGroupInvocations()),
			Integer.toUnsignedLong(limits.maxComputeWorkGroupCount(0)), Integer.toUnsignedLong(limits.maxComputeWorkGroupCount(1)),
			Integer.toUnsignedLong(limits.maxComputeWorkGroupCount(2)), limits.minUniformBufferOffsetAlignment(),
			Integer.toUnsignedLong(limits.maxUniformBufferRange()), pushSupported,
			push == null ? 0 : Integer.toUnsignedLong(push.maxPushDescriptors()));
	}

	/** All current compute descriptors have descriptorCount=1. Preserve deterministic type order. */
	static List<DescriptorCount> poolCounts(int[] descriptorTypes) {
		var counts = new LinkedHashMap<Integer, Integer>();
		for (int type : descriptorTypes) counts.merge(type, 1, Integer::sum);
		return counts.entrySet().stream().map(entry -> new DescriptorCount(entry.getKey(), entry.getValue())).toList();
	}

	/** Bind only the live uniform block, not the surrounding transient arena or a previous allocation. */
	static VkDescriptorBufferInfo.Buffer uniformSlice(long buffer, long bufferSize, long offset, long length,
												  DeviceLimits limits, MemoryStack stack) {
		if (buffer == 0 || offset < 0 || length <= 0 || length > bufferSize || offset > bufferSize - length
			|| offset % limits.minUniformAlignment() != 0 || length > limits.maxUniformRange()) {
			throw new IllegalArgumentException("Invalid compute uniform slice: offset=" + offset + ", length=" + length
				+ ", bufferSize=" + bufferSize);
		}
		return VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer).offset(offset).range(length);
	}

	record DescriptorCount(int type, int count) { }

	record DeviceLimits(int maxLocalX, int maxLocalY, int maxLocalZ, long maxInvocations,
						long maxGroupsX, long maxGroupsY, long maxGroupsZ, long minUniformAlignment,
						long maxUniformRange, boolean pushSupported, long maxPushDescriptors) {
		boolean usePushDescriptors(int descriptorCount) {
			return descriptorCount > 0 && pushSupported && descriptorCount <= maxPushDescriptors;
		}

		void validateLocalSize(Vector3i size, String name) {
			if (size.x <= 0 || size.y <= 0 || size.z <= 0 || size.x > maxLocalX || size.y > maxLocalY
				|| size.z > maxLocalZ || (long) size.x * size.y * size.z > maxInvocations) {
				throw new UnsupportedOperationException("Compute local size " + size + " exceeds device limits for " + name);
			}
		}

		void validateWorkGroups(Vector3i groups, String name) {
			if (groups.x < 0 || groups.y < 0 || groups.z < 0
				|| groups.x > maxGroupsX || groups.y > maxGroupsY || groups.z > maxGroupsZ) {
				throw new UnsupportedOperationException("Compute work groups " + groups + " exceed device limits for " + name);
			}
		}
	}
}
