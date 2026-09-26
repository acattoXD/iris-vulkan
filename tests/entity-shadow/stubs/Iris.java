package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.shaderpack.ShaderPack;
import java.util.Optional;

/** CPU test startup facade; rendering pipeline policy and actual mixin condition remain real. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-entity-shadow-test");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    public static PipelineManager pipelineManager;
    public static PipelineManager getPipelineManager() { return pipelineManager; }
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<ShaderPack> getCurrentPack() { return Optional.empty(); }
}
