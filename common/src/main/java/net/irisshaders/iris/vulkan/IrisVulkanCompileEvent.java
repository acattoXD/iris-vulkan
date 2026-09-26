package net.irisshaders.iris.vulkan;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import net.irisshaders.iris.Iris;

/** Cold shader work only: cache hits never construct a timing event. */
@Name("iris.VulkanShaderCompile")
@Label("Iris Vulkan Shader Compilation")
@Category({"Iris", "Vulkan"})
@Description("Synchronous source transformation or native pipeline compilation on a cache miss")
@StackTrace(false)
public final class IrisVulkanCompileEvent extends Event implements AutoCloseable {
    @Label("Phase") public String phase;
    @Label("Shader Key") public String shaderKey;
    @Label("Pipeline or Program") public String pipeline;
    @Label("Succeeded") public boolean succeeded;
    private final long startedNanos;

    private IrisVulkanCompileEvent(String phase, String shaderKey, String pipeline) {
        this.phase = phase;
        this.shaderKey = shaderKey;
        this.pipeline = pipeline;
        startedNanos = System.nanoTime();
        begin();
    }

    public static IrisVulkanCompileEvent start(String phase, String shaderKey, String pipeline) {
        return new IrisVulkanCompileEvent(phase, shaderKey, pipeline);
    }

    public void succeeded() { succeeded = true; }

    @Override
    public void close() {
        end();
        commit();
        long elapsedNanos = System.nanoTime() - startedNanos;
        if (elapsedNanos >= 100_000_000L) {
            Iris.logger.info("Native Vulkan cold {} for {} / {} took {} ms ({}).", phase, shaderKey, pipeline,
                    elapsedNanos / 1_000_000L, succeeded ? "success" : "failed");
        }
    }
}
