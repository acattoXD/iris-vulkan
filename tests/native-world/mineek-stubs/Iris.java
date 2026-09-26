package net.irisshaders.iris;

import java.nio.file.Path;
import java.util.Optional;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.pipeline.PipelineManager;

/** Supplies read-only transform settings; config initialization/save is never invoked. */
public final class Iris {
    private static final IrisConfig CONFIG = new IrisConfig(Path.of("unused-test-config"), Path.of("unused-test-exclusions"));
    private static final PipelineManager MANAGER = new PipelineManager(ignored -> null);
    public static final IrisLogging logger = new IrisLogging("Mineek CPU regression");
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<Object> getCurrentPack() { return Optional.empty(); }
    public static PipelineManager getPipelineManager() { return MANAGER; }
}
