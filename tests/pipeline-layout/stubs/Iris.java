package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import sun.misc.Unsafe;
import java.util.Optional;

/** Offline shader-transform facade; no live pack, pipeline or graphics context. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-makeup-layout-test");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    private static final PipelineManager MANAGER = emptyManager();
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<ShaderPack> getCurrentPack() { return Optional.empty(); }
    public static PipelineManager getPipelineManager() { return MANAGER; }
    private static PipelineManager emptyManager() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return (PipelineManager) ((Unsafe) field.get(null)).allocateInstance(PipelineManager.class);
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
