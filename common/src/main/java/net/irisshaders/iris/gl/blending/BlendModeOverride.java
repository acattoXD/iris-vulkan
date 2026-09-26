package net.irisshaders.iris.gl.blending;

public class BlendModeOverride {
	public static final BlendModeOverride OFF = new BlendModeOverride(null);

	private final BlendMode blendMode;

	public BlendModeOverride(BlendMode blendMode) {
		this.blendMode = blendMode;
	}

	/** Backend-neutral state; a null mode explicitly disables blending. */
	public BlendMode blendMode() { return blendMode; }

	public static void restore() {
		BlendModeStorage.restoreBlend();
	}

	public void apply() {
		BlendModeStorage.overrideBlend(this.blendMode);
	}
}
