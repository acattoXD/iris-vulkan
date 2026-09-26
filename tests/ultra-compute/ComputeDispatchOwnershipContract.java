package net.irisshaders.iris.vulkan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;

/** Checks the compiled executor uses the proven engine ownership boundary, not an untested numeric helper alone. */
public final class ComputeDispatchOwnershipContract {
	private static int checks;

	public static void main(String[] args) throws IOException {
		var program = read("net/irisshaders/iris/vulkan/IrisVulkanComputeExecutor$Program.class");
		var dispatch = method(program, "dispatch", "(II)V");
		var constructor = program.methods.stream().filter(value -> value.name.equals("<init>")).findFirst().orElseThrow();
		var writes = program.methods.stream().filter(value -> value.name.equals("descriptorWrites")).findFirst().orElseThrow();
		require(calls(dispatch, "IrisVulkanUniformSnapshot", "uploadTransient") == 1, "Actual dispatch uses fresh engine-owned upload");
		require(calls(dispatch, "IrisVulkanUniformSnapshot", "capture") == 0, "Actual dispatch cannot allocate legacy dedicated snapshot");
		require(calls(dispatch, "GpuBuffer", "close") == 0, "Actual dispatch never closes borrowed transient GPU buffer");
		require(calls(dispatch, "GpuBufferSlice", "slice") == 0 && calls(dispatch, "GpuBuffer", "slice") == 0,
			"Actual dispatch does not re-slice through engine transient buffer");
		require(calls(writes, "GpuBufferSlice", "offset") == 1 && calls(writes, "GpuBufferSlice", "length") == 1,
			"Actual UBO descriptor reads exact current slice offset and length");
		require(calls(writes, "IrisVulkanComputeBindings", "uniformSlice") == 1, "Actual descriptor uses GPU-proven numeric writer");
		require(calls(constructor, "IrisVulkanComputeBindings", "queryLimits") == 1
			&& calls(dispatch, "", "vkGetPhysicalDeviceProperties") == 0
			&& calls(dispatch, "", "vkGetPhysicalDeviceProperties2") == 0
			&& calls(dispatch, "IrisVulkanComputeBindings", "queryLimits") == 0, "Driver limits queried only during program construction");
		require(calls(constructor, "IrisVulkanComputeBindings", "poolCounts") == 1
			&& calls(dispatch, "IrisVulkanComputeBindings", "poolCounts") == 0, "Descriptor counts computed only during program construction");
		require(calls(dispatch, "IrisVulkanStorageResources", "barrier") == 2, "Both existing storage barriers retained");
		require(calls(dispatch, "VulkanCommandEncoder", "execute") == 1, "Compute remains one ordered engine submission append");
		require(calls(dispatch, "", "vkCmdDispatch") == 1 && calls(dispatch, "", "vkCmdDispatchIndirect") == 1,
			"Direct and indirect dispatch paths retained");
		require(calls(dispatch, "", "vkCmdPushDescriptorSetKHR") == 1
			&& calls(dispatch, "", "vkCmdBindDescriptorSets") == 1 && calls(dispatch, "", "vkAllocateDescriptorSets") == 1,
			"Actual dispatch includes push and allocated fallback paths");
		// javac duplicates the finally body on normal and exceptional exits.
		require(calls(dispatch, "VulkanCommandEncoder", "queueForDestroy") >= 1,
			"Submitted fallback pools use engine deferred retirement");
		System.out.println("PASS: " + checks + " compiled compute ownership/integration invariants");
	}

	private static ClassNode read(String resource) throws IOException {
		try (var input = ComputeDispatchOwnershipContract.class.getClassLoader().getResourceAsStream(resource)) {
			if (input == null) throw new AssertionError("Missing compiled class " + resource);
			var node = new ClassNode();
			new ClassReader(input).accept(node, 0);
			return node;
		}
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		return owner.methods.stream().filter(value -> value.name.equals(name) && value.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static long calls(MethodNode method, String ownerSuffix, String name) {
		long count = 0;
		for (var instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.endsWith(ownerSuffix) && call.name.equals(name)) count++;
		}
		return count;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
