import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import net.irisshaders.iris.vulkan.IrisVulkanDeviceFeatures;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Exercises the real feature/pNext structs and validates injection targets without creating any Vulkan device. */
public final class IrisVulkanDeviceFeaturesTest {
    public static void main(String[] args) throws Exception {
        Set<VulkanFeature> original = Set.copyOf(VulkanBackend.REQUIRED_DEVICE_FEATURES);
        for (int mask = 0; mask < 16; mask++) {
            boolean vertex = (mask & 1) != 0, fragment = (mask & 2) != 0, extended = (mask & 4) != 0;
            boolean robustBuffers = (mask & 8) != 0;
            Set<VulkanFeature> selected = IrisVulkanDeviceFeatures.selectSupported(VulkanBackend.REQUIRED_DEVICE_FEATURES,
                vertex, fragment, extended, robustBuffers);
            check(selected.containsAll(original), "All existing Minecraft required features must survive");
            check(VulkanBackend.REQUIRED_DEVICE_FEATURES.equals(original), "Optional requests must not change the global required features");
            if (mask == 0) check(selected.equals(original), "Devices without optional features preserve their High request set");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceFeatures2 enabled = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                for (VulkanFeature feature : selected) feature.set(enabled, true, stack);
                check(enabled.features().vertexPipelineStoresAndAtomics() == vertex, "Vertex writes enable only when advertised");
                check(enabled.features().fragmentStoresAndAtomics() == fragment, "Fragment writes enable only when advertised");
                check(enabled.features().shaderStorageImageExtendedFormats() == extended, "Extended storage formats enable only when advertised");
                check(enabled.features().robustBufferAccess() == robustBuffers, "Buffer robustness enables only when advertised");
            }
            var accepted = IrisVulkanDeviceFeatures.acceptedFeatures(selected);
            check(accepted.recorded(), "Successful creation marks the selected features recorded");
            check(accepted.vertexPipelineStoresAndAtomics() == vertex && accepted.fragmentStoresAndAtomics() == fragment
                && accepted.shaderStorageImageExtendedFormats() == extended && accepted.robustBufferAccess() == robustBuffers,
                "Recorded capabilities equal the accepted device request");
            check(accepted.missingForStorage().size() == 3 - Integer.bitCount(mask & 7), "Image capability check reports exactly missing bits");
            check(accepted.missingForStorage(true).size() == 4 - Integer.bitCount(mask), "SSBO capability check also requires robust access");
            check(accepted.missingForStorage(false).equals(accepted.missingForStorage()), "Image-only packs do not require buffer robustness");
            accepted.require(false, false, false);
            if ((mask & 7) == 7) accepted.requireStorage();
            else {
                try {
                    accepted.requireStorage();
                    throw new AssertionError("ULTRA must fail when required storage bits were not enabled");
                } catch (UnsupportedOperationException expected) {
                    check(expected.getMessage().contains(accepted.missingForStorage().get(0)), "Failure identifies the missing Vulkan feature");
                }
            }
            if (mask == 15) accepted.requireStorage(true);
            else {
                try {
                    accepted.requireStorage(true);
                    throw new AssertionError("An SSBO pack must reject missing native robustness/storage features");
                } catch (UnsupportedOperationException expected) {
                    check(expected.getMessage().contains(accepted.missingForStorage(true).get(0)), "SSBO failure identifies the missing feature");
                }
            }
        }
        var unknown = IrisVulkanDeviceFeatures.enabled((org.lwjgl.vulkan.VkDevice) null);
        check(!unknown.recorded() && unknown.missingForStorage().size() == 3, "An unhooked device cannot masquerade as storage capable");
        verifyCreationHook();
        System.out.println("Native Vulkan device feature checks passed: sixteen support combinations, SSBO robustness requirement, actual pNext/core bits, and exact 26.2 creation hook.");
    }

    private static void verifyCreationHook() throws IOException {
        ClassNode backend = read("com/mojang/blaze3d/vulkan/VulkanBackend");
        ClassNode mixin = read("net/irisshaders/iris/mixin/vulkan/VKOnly_MixinVulkanBackend_StorageFeatures");
        MethodNode handler = mixin.methods.stream().filter(method -> method.name.equals("iris$enableStorageDeviceFeatures")).findFirst().orElseThrow();
        AnnotationNode wrapper = annotations(handler).stream().filter(annotation -> annotation.desc.endsWith("/WrapOperation;")).findFirst().orElseThrow();
        String selector = ((List<?>) value(wrapper, "method")).get(0).toString();
        Object atValue = value(wrapper, "at");
        AnnotationNode at = (AnnotationNode) (atValue instanceof List<?> list ? list.get(0) : atValue);
        String target = value(at, "target").toString();
        check(Integer.valueOf(1).equals(value(wrapper, "require")), "Feature hook is mandatory exactly once");
        MethodNode outer = backend.methods.stream().filter(method -> (method.name + method.desc).equals(selector)).findFirst().orElseThrow();
        MethodInsnNode wrapped = null;
        int matches = 0;
        for (var instruction : outer.instructions) {
            if (instruction instanceof MethodInsnNode call && ("L" + call.owner + ";" + call.name + call.desc).equals(target)) {
                check(call.getOpcode() == Opcodes.INVOKESTATIC, "Device factory call remains static");
                wrapped = call;
                matches++;
            }
        }
        check(matches == 1, "Exactly one private native device factory is wrapped in the real backend");
        String factoryDescriptor = wrapped.desc;
        MethodNode factory = backend.methods.stream().filter(method -> method.name.equals("createDevice") && method.desc.equals(factoryDescriptor)).findFirst().orElseThrow();
        int featureSet = -1, corePointer = -1, nativeCreate = -1, wrapperCreate = -1;
        for (var instruction : factory.instructions) if (instruction instanceof MethodInsnNode call) {
            int position = factory.instructions.indexOf(instruction);
            if (call.owner.equals("com/mojang/blaze3d/vulkan/init/VulkanFeature") && call.name.equals("set")) featureSet = position;
            if (call.owner.equals("org/lwjgl/vulkan/VkDeviceCreateInfo") && call.name.equals("pEnabledFeatures")) corePointer = position;
            if (call.owner.equals("org/lwjgl/vulkan/VK12") && call.name.equals("vkCreateDevice")) nativeCreate = position;
            if (call.owner.equals("org/lwjgl/vulkan/VkDevice") && call.name.equals("<init>")) wrapperCreate = position;
        }
        check(featureSet >= 0 && featureSet < corePointer && corePointer < nativeCreate && nativeCreate < wrapperCreate,
            "Requested VulkanFeature bits are materialized before vkCreateDevice and the returned VkDevice");
        int select = -1, create = -1, record = -1;
        for (var instruction : handler.instructions) if (instruction instanceof MethodInsnNode call) {
            int position = handler.instructions.indexOf(instruction);
            if (call.name.equals("withSupportedStorageFeatures")) select = position;
            if (call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation") && call.name.equals("call")) create = position;
            if (call.name.equals("recordCreatedDevice")) record = position;
        }
        check(select >= 0 && select < create && create < record, "Feature query precedes device creation; accepted-bit recording follows success");
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = IrisVulkanDeviceFeaturesTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations;
    }

    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int index = 0; index < node.values.size(); index += 2) {
            if (node.values.get(index).equals(key)) return node.values.get(index + 1);
        }
        return null;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
