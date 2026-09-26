package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import net.irisshaders.iris.pipeline.programs.IrisShaderSource;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Map;

/** Replays actual cache-hit methods and verifies the new event without creating a GPU device. */
public final class IrisVulkanCompileTimingTest {
    public static void main(String[] args) throws Exception {
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        // Cache hits only return this identity; constructors would create native resources.
        VulkanRenderPipeline cached = (VulkanRenderPipeline) unsafe.allocateInstance(VulkanRenderPipeline.class);
        IrisShaderSource source = new IrisShaderSource("cached", "vertex", null, null, null, "fragment", null, null, false, false);
        var sourceMap = (IrisVulkanShaderSourceMap) unsafe.allocateInstance(IrisVulkanShaderSourceMap.class);
        var sources = IrisVulkanShaderSourceMap.class.getDeclaredField("sources");
        sources.setAccessible(true);
        IrisShaderSource[] values = new IrisShaderSource[ShaderKey.values().length];
        values[ShaderKey.ENTITIES_CUTOUT.ordinal()] = source;
        sources.set(sourceMap, values);

        Class<?> customType = Class.forName(IrisNativeVulkan.class.getName() + "$CustomPipelineSource");
        Constructor<?> customConstructor = customType.getDeclaredConstructor(String.class, String.class, String.class, boolean.class);
        customConstructor.setAccessible(true);
        Object custom = customConstructor.newInstance("cached-screen", "vertex", "fragment", false);
        Class<?> keyType = Class.forName(IrisNativeVulkan.class.getName() + "$CacheKey");
        Constructor<?> keyConstructor = keyType.getDeclaredConstructor(VulkanDevice.class, RenderPipeline.class, Object.class);
        keyConstructor.setAccessible(true);
        Object worldKey = keyConstructor.newInstance(null, null, source);
        Object screenKey = keyConstructor.newInstance(null, null, custom);
        var cacheField = IrisNativeVulkan.class.getDeclaredField("compiledPipelines");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Object, VulkanRenderPipeline> cache = (Map<Object, VulkanRenderPipeline>) cacheField.get(null);
        cache.put(worldKey, cached);
        cache.put(screenKey, cached);
        var world = IrisNativeVulkan.class.getDeclaredMethod("compileOverride", VulkanDevice.class, RenderPipeline.class,
                ShaderKey.class, IrisShaderSource.class);
        var screen = IrisNativeVulkan.class.getDeclaredMethod("compileCustomOverride", VulkanDevice.class, RenderPipeline.class, customType);
        world.setAccessible(true);
        screen.setAccessible(true);

        var output = Files.createTempFile("iris-compile-timing-", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("iris.VulkanShaderCompile").withThreshold(java.time.Duration.ZERO);
            recording.start();
            for (int i = 0; i < 10_000; i++) {
                check(sourceMap.getSource(ShaderKey.ENTITIES_CUTOUT) == source, "Source cache identity changed");
                check(((java.util.Optional<?>) world.invoke(null, null, null, null, source)).orElseThrow() == cached, "World cache identity changed");
                check(((java.util.Optional<?>) screen.invoke(null, null, null, custom)).orElseThrow() == cached, "Screen cache identity changed");
            }
            try (var event = IrisVulkanCompileEvent.start("test-success", "ENTITIES_CUTOUT", "test/entity")) { event.succeeded(); }
            try (var event = IrisVulkanCompileEvent.start("test-failure", "PARTICLES_TRANS", "test/particle")) { }
            recording.stop();
            recording.dump(output);
            var events = RecordingFile.readAllEvents(output).stream()
                    .filter(event -> event.getEventType().getName().equals("iris.VulkanShaderCompile")).toList();
            check(events.size() == 2, "30,000 cache hits must not emit compile events: " + events.size());
            check(events.stream().anyMatch(event -> event.getString("phase").equals("test-success") && event.getBoolean("succeeded")), "Missing successful event");
            check(events.stream().anyMatch(event -> event.getString("phase").equals("test-failure") && !event.getBoolean("succeeded")), "Missing failure event");
            check(events.stream().allMatch(event -> !event.getDuration().isNegative()), "Invalid event duration");
        } finally {
            cache.remove(worldKey);
            cache.remove(screenKey);
            Files.deleteIfExists(output);
        }
        System.out.println("PASS: 10,000 actual source/world/screen cache hits each emit no timing events; success/failure metadata and JFR durations are recorded on explicit cold-work events");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
