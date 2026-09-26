package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import java.util.Optional;

/** CPU-only startup facade; the property parser, resource collector and descriptor reflection remain real. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-storage-2d-test");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    public static ShaderPack currentPack;
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<ShaderPack> getCurrentPack() { return Optional.ofNullable(currentPack); }
}
