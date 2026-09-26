package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import java.util.Optional;

/** CPU test startup facade. No Fabric launcher, live pack, or graphics context. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-color-images-test");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<ShaderPack> getCurrentPack() { return Optional.empty(); }
}
