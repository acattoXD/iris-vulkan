package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import java.util.Optional;

/** CPU harness facade only: no launcher, game, device or configuration file. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("photon-cpu-audit");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    public static ShaderPack currentPack;
    public static IrisConfig getIrisConfig() { return CONFIG; }
    public static Optional<ShaderPack> getCurrentPack() { return Optional.ofNullable(currentPack); }
    public static NamespacedId getCurrentDimension() { return DimensionId.OVERWORLD; }
}
