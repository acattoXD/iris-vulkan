package net.irisshaders.iris.vulkan;

import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/** CPU descriptor ABI and dispatch-limit boundaries; the separate headless proof executes these on a GPU. */
public final class ComputeBindingContract {
	private static int checks;

	public static void main(String[] args) {
		var limits = limits(true, 7);
		require(limits.usePushDescriptors(7), "Exact push descriptor limit is supported");
		require(!limits.usePushDescriptors(8), "Above push limit uses allocated descriptors");
		require(!limits(false, 100).usePushDescriptors(7), "Disabled extension overrides nonzero queried limit");
		require(!limits(true, 0).usePushDescriptors(1), "Zero limit falls back");
		require(!limits.usePushDescriptors(0), "Descriptor-free shader does not issue empty push command");
		require(!limits.usePushDescriptors(-1), "Invalid negative descriptor count never selects push");
		int[] types = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
			VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
		var counts = IrisVulkanComputeBindings.poolCounts(types);
		require(counts.equals(List.of(new IrisVulkanComputeBindings.DescriptorCount(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1),
			new IrisVulkanComputeBindings.DescriptorCount(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1),
			new IrisVulkanComputeBindings.DescriptorCount(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 2),
			new IrisVulkanComputeBindings.DescriptorCount(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3))), "Seven Ultra-style bindings count correctly");
		types[0] = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
		require(counts.getFirst().type() == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, "Counts detached from input array");
		expect(UnsupportedOperationException.class, () -> counts.clear(), "Cached counts immutable");
		require(IrisVulkanComputeBindings.poolCounts(new int[0]).isEmpty(), "Descriptor-free metadata empty");
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var descriptor = IrisVulkanComputeBindings.uniformSlice(0x1234, 524288, 768, 144, limits, stack);
			require(descriptor.get(0).buffer() == 0x1234 && descriptor.get(0).offset() == 768
				&& descriptor.get(0).range() == 144, "Descriptor preserves exact nonzero aligned slice, not arena size");
			var end = IrisVulkanComputeBindings.uniformSlice(0x5678, 1040, 1024, 16, limits, stack);
			require(end.get(0).offset() + end.get(0).range() == 1040, "Exact buffer-end slice valid");
			var first = IrisVulkanComputeBindings.uniformSlice(0x1234, 524288, 0, 65536, limits, stack);
			require(first.get(0).range() == 65536, "Maximum UBO range valid");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(0, 1024, 0, 16, limits, stack), "Null VkBuffer rejected");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, 1024, -256, 16, limits, stack), "Negative offset rejected");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, 1024, 16, 16, limits, stack), "Misaligned offset rejected");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, 1024, 0, 0, limits, stack), "Zero UBO range rejected");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, 524288, 0, 65537, limits, stack), "Device UBO range enforced");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, 1024, 1024, 16, limits, stack), "Out of buffer slice rejected");
			expect(IllegalArgumentException.class, () -> IrisVulkanComputeBindings.uniformSlice(1, Long.MAX_VALUE, Long.MAX_VALUE - 255, 512, limits, stack), "Offset plus length overflow rejected");
		}
		limits.validateLocalSize(new Vector3i(8, 8, 8), "valid");
		limits.validateLocalSize(new Vector3i(1024, 1, 1), "local-limit");
		checks += 2;
		expect(UnsupportedOperationException.class, () -> limits.validateLocalSize(new Vector3i(0, 1, 1), "zero"), "Zero local size rejected");
		expect(UnsupportedOperationException.class, () -> limits.validateLocalSize(new Vector3i(8, 8, 17), "product"), "Max invocation count enforced");
		expect(UnsupportedOperationException.class, () -> limits.validateLocalSize(new Vector3i(1, 1, 65), "z"), "Local dimension limit enforced");
		limits.validateWorkGroups(new Vector3i(0, 0, 0), "zero-work");
		limits.validateWorkGroups(new Vector3i(65535, 32768, 1024), "exact-work-limits");
		checks += 2;
		expect(UnsupportedOperationException.class, () -> limits.validateWorkGroups(new Vector3i(-1, 1, 1), "negative"), "Negative group rejected");
		expect(UnsupportedOperationException.class, () -> limits.validateWorkGroups(new Vector3i(65536, 1, 1), "x"), "Group x limit enforced");
		expect(UnsupportedOperationException.class, () -> limits.validateWorkGroups(new Vector3i(1, 32769, 1), "y"), "Group y limit enforced");
		expect(UnsupportedOperationException.class, () -> limits.validateWorkGroups(new Vector3i(1, 1, 1025), "z"), "Group z limit enforced");
		System.out.println("PASS: " + checks + " compute slice/descriptor/fallback/dispatch-boundary checks");
	}

	private static IrisVulkanComputeBindings.DeviceLimits limits(boolean pushSupported, long maxPush) {
		return new IrisVulkanComputeBindings.DeviceLimits(1024, 1024, 64, 1024, 65535, 32768, 1024, 256, 65536, pushSupported, maxPush);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}

	private static void expect(Class<? extends Throwable> expected, Runnable action, String message) {
		try { action.run(); }
		catch (Throwable actual) {
			if (!expected.isInstance(actual)) throw new AssertionError(message, actual);
			checks++;
			return;
		}
		throw new AssertionError(message);
	}
}
