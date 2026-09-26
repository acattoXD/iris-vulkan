package net.irisshaders.iris;

import java.util.Optional;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.PipelineManager;

/** Only supplies controllable pipeline/startup state; no game or GPU is initialized. */
public final class Iris {
    public static PipelineManager pipelineManager;
    public static final IrisLogging logger = new IrisLogging("Native normal regression");
    public static PipelineManager getPipelineManager() { return pipelineManager; }
    public static IrisConfig getIrisConfig() { return null; }
    public static Optional<Object> getCurrentPack() { return Optional.empty(); }
}
