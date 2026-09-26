package net.irisshaders.iris;

import net.irisshaders.iris.pipeline.PipelineManager;

/** CPU-only startup facade; no Fabric launcher or graphics context is initialized. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-pipeline-warmup-test");
    private static final PipelineManager MANAGER = new PipelineManager();
    public static PipelineManager getPipelineManager() { return MANAGER; }
}
