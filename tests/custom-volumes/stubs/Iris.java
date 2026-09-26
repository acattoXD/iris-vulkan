package net.irisshaders.iris;

import net.irisshaders.iris.shaderpack.ShaderPack;
import java.util.Optional;

/** Startup facade only; tests exercise real texture metadata, routing and GLSL generation. */
public final class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-static-volume-test");
    public static Optional<ShaderPack> getCurrentPack() { return Optional.empty(); }
}
