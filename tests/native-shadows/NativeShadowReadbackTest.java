import net.irisshaders.iris.probe.NativeShadowReadback;

public final class NativeShadowReadbackTest {
	public static void main(String[] args) {
		var clear = NativeShadowReadback.analyze(2, 2, new float[]{1,1,1,1}, new float[]{1,1,1,1});
		check(!clear.bothTexturesContainCasters(), "a clear-only map must not establish geometry coverage");
		check(clear.depthRangeAndOrderingValid(), "clear depths remain a valid forward depth range");
		var terrainAndGlass = NativeShadowReadback.analyze(2, 2, new float[]{0.2f,0.3f,0.5f,1}, new float[]{0.2f,0.6f,0.5f,1});
		check(terrainAndGlass.bothTexturesContainCasters(), "opaque terrain is present in both maps");
		check(terrainAndGlass.translucentDepthPixels() == 1, "glass has nearer depth only in shadowtex0");
		check(terrainAndGlass.depthRangeAndOrderingValid(), "forward translucent depth order");
		check(terrainAndGlass.shadowtex0().validNonClearPixels() == 3, "nonclear coverage");
		check(!terrainAndGlass.shadowtex0().sha256().equals(terrainAndGlass.shadowtex1().sha256()), "distinct map hashes");
		var reversed = NativeShadowReadback.analyze(1, 1, new float[]{0.8f}, new float[]{0.3f});
		check(reversed.wrongDepthOrderPixels() == 1 && !reversed.depthRangeAndOrderingValid(), "reversed or corrupted depth is detected");
		var invalid = NativeShadowReadback.analyze(2, 1, new float[]{Float.NaN,1.1f}, new float[]{1,1});
		check(invalid.shadowtex0().invalidPixels() == 2 && !invalid.depthRangeAndOrderingValid(), "nonfinite and out-of-range depths detected");
		System.out.println("PASS: depth readback analysis distinguishes clear, real casters, translucent depth, wrong ordering and invalid data");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
