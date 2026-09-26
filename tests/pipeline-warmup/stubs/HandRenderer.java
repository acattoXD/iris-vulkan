package net.irisshaders.iris.pathways;

/** Native phases supply hand routing; avoid the legacy renderer's GPU constructor in CPU tests. */
public final class HandRenderer {
    public static final HandRenderer INSTANCE = new HandRenderer();
    public boolean isActive() { return false; }
    public boolean isRenderingSolid() { return false; }
}
