package net.irisshaders.iris.gl;

/** Offline capability facade matching native Vulkan's feature branch; no OpenGL initialization. */
public final class IrisRenderSystem {
    public static boolean supportsImageLoadStore() { return true; }
    public static boolean supportsBufferBlending() { return true; }
    public static boolean supportsCompute() { return true; }
    public static boolean supportsTesselation() { return true; }
    public static boolean supportsSSBO() { return true; }
}
