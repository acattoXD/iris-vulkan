package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.MixinModelViewBobbing;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sun.misc.Unsafe;

/** Runs the real camera gate and API predicate without constructing GPU resources. */
public final class IrisVulkanCameraGateTest {
    private static final class CameraHook extends MixinModelViewBobbing { }

    public static void main(String[] args) throws Exception {
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        // Only runtime types are needed by the real API predicate. Constructors
        // would initialize material registries and GPU resources in this CPU test.
        Iris.pipelineManager = (PipelineManager) unsafe.allocateInstance(PipelineManager.class);
        var pipelineField = PipelineManager.class.getDeclaredField("pipeline");
        pipelineField.setAccessible(true);
        var gate = MixinModelViewBobbing.class.getDeclaredMethod("iris$saveShadersOn", DeltaTracker.class, CallbackInfo.class);
        gate.setAccessible(true);
        var enabled = MixinModelViewBobbing.class.getDeclaredField("areShadersOn");
        enabled.setAccessible(true);
        CameraHook camera = new CameraHook();
        Class<?>[] pipelineTypes = {null, VanillaRenderingPipeline.class,
                IrisRenderingPipeline.class, NativeVulkanWorldRenderingPipeline.class};
        for (Class<?> type : pipelineTypes) {
            pipelineField.set(Iris.pipelineManager, type == null ? null : unsafe.allocateInstance(type));
            gate.invoke(camera, null, null);
            boolean expected = type == IrisRenderingPipeline.class || type == NativeVulkanWorldRenderingPipeline.class;
            if (enabled.getBoolean(camera) != expected) {
                throw new AssertionError("Camera effects gate for " + (type == null ? "null" : type.getSimpleName()) + " must be " + expected);
            }
        }
        System.out.println("IRIS_VULKAN_CAMERA_GATE_PASS: actual camera hook/API select GL and native packs, exclude vanilla/null");
    }
}
