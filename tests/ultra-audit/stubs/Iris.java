package net.irisshaders.iris;

import net.irisshaders.iris.config.IrisConfig;

/** Offline audit facade: logging and disabled debug-file emission only. Never loaded by the mod. */
public class Iris {
    public static final IrisLogging logger = new IrisLogging("iris-ultra-offline-audit");
    private static final IrisConfig CONFIG = new IrisConfig(null, null);
    public static IrisConfig getIrisConfig() { return CONFIG; }
}
