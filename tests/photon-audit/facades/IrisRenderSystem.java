package net.irisshaders.iris.gl;

/** Explicit feature assumptions for parsing; no hardware capability claim. */
public final class IrisRenderSystem {
    public static boolean supportsImageLoadStore() { return true; }
    public static boolean supportsBufferBlending() { return true; }
    public static boolean supportsCompute() { return true; }
    public static boolean supportsTesselation() { return true; }
    public static boolean supportsSSBO() { return true; }
}
