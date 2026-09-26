package net.irisshaders.iris.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.irisshaders.iris.mixin.vulkan.VKOnly_MixinVulkanRenderPipeline_PushConstants;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL;

/** Executes both real mixin handlers against actual VkPipelineLayoutCreateInfo structs, without a GPU. */
public final class IrisVulkanPushConstantLayoutTest {
    public static void main(String[] args) throws Exception {
        Class<?> sodiumMixin = Class.forName("net.caffeinemc.mods.sodium.mixin.core.VulkanPipelineMixin");
        var sodium = sodiumMixin.getDeclaredMethod("sodium$fixPipelineLayout", VkPipelineLayoutCreateInfo.class,
                LongBuffer.class, Operation.class, RenderPipeline.class, MemoryStack.class);
        sodium.setAccessible(true);
        var iris = VKOnly_MixinVulkanRenderPipeline_PushConstants.class.getDeclaredMethod("iris$preservePreparedPushConstants",
                VkDevice.class, VkPipelineLayoutCreateInfo.class, VkAllocationCallbacks.class, LongBuffer.class,
                Operation.class, RenderPipeline.class, MemoryStack.class);
        iris.setAccessible(true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            RenderPipeline original = pipeline("sodium:original_terrain");
            RenderPipeline shadow = pipeline("iris:native_shadow/shadow_sodium_terrain_solid/sodium/pipeline/solid_terrain");
            RenderPipeline unrelated = pipeline("iris:unrelated_screen_pass");
            Operation<VkPipelineLayoutCreateInfo> setLayouts = parameters -> ((VkPipelineLayoutCreateInfo) parameters[0]).pSetLayouts((LongBuffer) parameters[1]);
            var originalInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            sodium.invoke(null, originalInfo, stack.longs(17), setLayouts, original, stack);
            range(originalInfo);
            var shadowInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            sodium.invoke(null, shadowInfo, stack.longs(17), setLayouts, shadow, stack);
            require(shadowInfo.pushConstantRangeCount() == 0, "Regression must reproduce Sodium skipping Iris's namespace");
            IrisVulkanPipelineLayout.registerPreparedPipeline(shadow, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID);
            Operation<Integer> createLayout = parameters -> {
                require(parameters[1] == shadowInfo, "Hook preserves the existing layout create-info");
                range((VkPipelineLayoutCreateInfo) parameters[1]);
                return 37;
            };
            int result = (int) iris.invoke(null, null, shadowInfo, null, stack.longs(0), createLayout, shadow, stack);
            require(result == 37, "Hook forwards native return value");
            long pointer = shadowInfo.pPushConstantRanges().address();
            IrisVulkanPipelineLayout.preservePushConstants(shadow, shadowInfo, stack);
            require(shadowInfo.pPushConstantRanges().address() == pointer, "A valid range is not duplicated or replaced");
            IrisVulkanPipelineLayout.registerPreparedPipeline(original, ShaderKey.SODIUM_TERRAIN_SOLID);
            long originalPointer = originalInfo.pPushConstantRanges().address();
            IrisVulkanPipelineLayout.preservePushConstants(original, originalInfo, stack);
            require(originalInfo.pPushConstantRanges().address() == originalPointer, "Sodium's existing range remains untouched");
            var unrelatedInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            IrisVulkanPipelineLayout.registerPreparedPipeline(unrelated, ShaderKey.BASIC);
            IrisVulkanPipelineLayout.preservePushConstants(unrelated, unrelatedInfo, stack);
            require(unrelatedInfo.pushConstantRangeCount() == 0, "Non-Sodium shaders do not acquire unrelated push constants");
        }
        System.out.println("IRIS_PUSH_CONSTANT_LAYOUT_PASS: actual Sodium namespace miss, real Iris hook, 20-byte ALL-stage range, existing/unrelated preservation; Sodium="
                + sodiumMixin.getProtectionDomain().getCodeSource().getLocation());
    }

    private static RenderPipeline pipeline(String id) {
        return RenderPipeline.builder().withLocation(Identifier.parse(id)).withVertexShader("core/position")
                .withFragmentShader("core/position").withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES).build();
    }
    private static void range(VkPipelineLayoutCreateInfo info) {
        require(info.pushConstantRangeCount() == 1, "One push constant range required");
        var range = info.pPushConstantRanges().get(0);
        require(range.offset() == 0 && range.size() == 20 && range.stageFlags() == VK_SHADER_STAGE_ALL, "Range must match Sodium's actual push command ABI");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
