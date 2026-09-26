package net.irisshaders.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;

/** Supplies test pipeline state while retaining the real API and camera gate. */
public final class Iris {
    public static PipelineManager pipelineManager;

    public static PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    public static boolean isPackInUseQuick() {
        return pipelineManager.getPipelineNullable() instanceof IrisRenderingPipeline;
    }
}
