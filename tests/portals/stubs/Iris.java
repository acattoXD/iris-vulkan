package net.irisshaders.iris;

import java.util.Optional;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.IrisLogging;
import net.irisshaders.iris.pipeline.PipelineManager;

/** Only pack presence/startup services are stubbed; production portal handlers run unchanged. */
public final class Iris {
    public static boolean packActive;
    public static final IrisLogging logger = new IrisLogging("Portal CPU regression");
    public static Optional<Object> getCurrentPack() { return packActive ? Optional.of(Boolean.TRUE) : Optional.empty(); }
    public static IrisConfig getIrisConfig() { return null; }
    public static PipelineManager getPipelineManager() { return null; }
}
