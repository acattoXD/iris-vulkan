package net.irisshaders.iris.platform;

import java.nio.file.Path;

/** Isolates the mixin-selection test from Fabric/Minecraft startup. */
public interface IrisPlatformHelpers {
    static IrisPlatformHelpers getInstance() {
        return () -> Path.of(System.getProperty("iris.test.gameDir"));
    }

    Path getGameDir();
}
