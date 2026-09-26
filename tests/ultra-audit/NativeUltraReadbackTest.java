package net.irisshaders.iris.probe;

import net.irisshaders.iris.vulkan.IrisVulkanComputeExecutor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure checks for interpreting real GPU evidence; no Minecraft or graphics context is initialized. */
public final class NativeUltraReadbackTest {
    public static void main(String[] args) throws Exception {
        NativeUltraReadback.requireOptions(NativeUltraReadback.ULTRA_OPTIONS);
        var wrong = new HashMap<>(NativeUltraReadback.ULTRA_OPTIONS);
        wrong.put("COLORED_LIGHTING", "0");
        reject(() -> NativeUltraReadback.requireOptions(wrong), "High must not pass Ultra options");
        NativeUltraReadback.requireDispatch(new IrisVulkanComputeExecutor.DispatchRecord("shadowcomp", 8, 8, 8, 64, 32, 64, false, 1));
        reject(() -> NativeUltraReadback.requireDispatch(null), "missing dispatch");
        reject(() -> NativeUltraReadback.requireDispatch(new IrisVulkanComputeExecutor.DispatchRecord("shadowcomp", 8, 8, 8, 32, 32, 32, false, 1)), "wrong volume dispatch");

        var voxelCrop = new NativeUltraReadback.Crop("voxel_img", "r16ui", 0, 0, 0, 2, 1, 1, 2, 0);
        ByteBuffer voxels = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder());
        voxels.putShort(0, (short) 8).putShort(2, (short) (32768 | 9));
        var voxel = NativeUltraReadback.analyze(voxelCrop, voxels);
        require(voxel.nonzeroTexels() == 2 && voxel.voxelIds().equals(Map.of(8, 1L, 9, 1L)), "unsigned voxel ID and colorwheel flag decoding");
        var floodCrop = new NativeUltraReadback.Crop("floodfill_img", "rgba16f", 0, 0, 0, 2, 1, 1, 8, 0);
        ByteBuffer flood = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        flood.putShort(0, Float.floatToFloat16(1.0f)).putShort(2, Float.floatToFloat16(2.0f)).putShort(4, Float.floatToFloat16(3.0f));
        var light = NativeUltraReadback.analyze(floodCrop, flood);
        require(light.nonzeroTexels() == 1 && light.coloredTexels() == 1 && light.maxRGB().equals(List.of(1f, 2f, 3f)), "RGBA16F channels and zero detection");
        flood.putShort(8, (short) 0x7e00);
        var invalid = NativeUltraReadback.analyze(floodCrop, flood);
        require(invalid.nonFiniteComponents() == 1, "NaN must be reported");
        var copy = new NativeUltraReadback.ImageStats(new NativeUltraReadback.Crop("floodfill_img_copy", "rgba16f", 0, 0, 0, 2, 1, 1, 8, 0),
            light.nonzeroTexels(), light.coloredTexels(), 0, light.maxRGB(), Map.of(), light.sha256());
        var wsr = new NativeUltraReadback.ImageStats(new NativeUltraReadback.Crop("wsr_img", "r16ui", 0, 0, 0, 1, 1, 1, 2, 0), 1, 0, 0, List.of(0f, 0f, 0f), Map.of(), "example");
        var lod = new NativeUltraReadback.ImageStats(new NativeUltraReadback.Crop("wsr_lod_img", "r8ui", 0, 0, 0, 1, 1, 1, 1, 0), 1, 0, 0, List.of(0f, 0f, 0f), Map.of(), "example");
        var buffer = new NativeUltraReadback.BufferStats(0, 810_549_248L, 480, 1, List.of(), "example");
        var activity = new ArrayList<>(List.of(voxel, light, copy, wsr, lod));
        require(NativeUltraReadback.activityPassed(activity, buffer), "all expected storage activity");
        activity.set(1, invalid);
        require(!NativeUltraReadback.activityPassed(activity, buffer), "non-finite floodfill must fail");
        activity.set(1, light);
        require(!NativeUltraReadback.activityPassed(activity, new NativeUltraReadback.BufferStats(0, 810_549_248L, 480, 0, List.of(), "zero")), "zero SSBO must fail");
        require(NativeUltraReadback.faceIndex(0, 0, 0, 0, false) == 0, "first face index");
        require((NativeUltraReadback.faceIndex(511, 63, 511, 2, true) + 1) * 16 == 810_549_248L, "last face exactly fits real SSBO");
        reject(() -> NativeUltraReadback.faceIndex(512, 0, 0, 0, false), "out-of-volume face sample");
        System.out.println("NATIVE_ULTRA_READBACK_CPU_PASS: exact options/dispatch, unsigned voxel IDs, RGBA16F/NaN, real activity criteria, SSBO bounds");
    }

    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void reject(Runnable action, String reason) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError(reason);
    }
}
