import net.irisshaders.iris.vulkan.IrisVulkanShadowMath;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** CPU checks for the shader-visible/rasterized shadow-coordinate contract. */
public final class IrisVulkanShadowMathTest {
	public static void main(String[] args) {
		float near = -100.05f;
		float far = 156.0f;
		Matrix4f projection = IrisVulkanShadowMath.projection(160.0f, near, far, null);
		close(0.0f, shadowDepth(projection, -near), "near plane");
		close(1.0f, shadowDepth(projection, -far), "far plane");
		close(0.5f, shadowDepth(projection, -(near + far) * 0.5f), "middle plane");
		close(1.0f, new Vector4f(160, 0, 0, 1).mul(projection).x, "right boundary");
		close(-1.0f, new Vector4f(-160, 0, 0, 1).mul(projection).x, "left boundary");
		float caster = shadowDepth(projection, -10.0f);
		float receiver = shadowDepth(projection, -30.0f);
		check(caster < receiver, "LESS shadow test must retain the nearer caster");
		Matrix4f inverse = new Matrix4f(projection).invert();
		Vector4f original = new Vector4f(-37.5f, 29.0f, -78.0f, 1.0f);
		check(new Vector4f(original).mul(projection).mul(inverse).equals(original, 0.0001f), "projection inverse roundtrip");

		Matrix4f perspective = IrisVulkanShadowMath.projection(32, 0.05f, 156, 90.0f);
		close(0.0f, shadowDepth(perspective, -0.05f), "perspective near plane");
		close(1.0f, shadowDepth(perspective, -156), "perspective far plane");

		// Vulkan raster depth = (clip.z + clip.w)/2/clip.w; pack receiver depth = ndc.z/2+1/2.
		for (float w : new float[]{0.05f, 1.0f, 3.7f, 1000.0f}) {
			for (float ndc : new float[]{-1, -0.77f, 0, 0.125f, 1}) {
				float clipZ = ndc * w;
				float rasterDepth = (clipZ + w) * 0.5f / w;
				float receiverDepth = ndc * 0.5f + 0.5f;
				close(receiverDepth, rasterDepth, "raster/receiver depth agreement");
			}
		}
		String input = "#version 450\nvoid main(void) { if (true) { gl_Position = vec4(0, 0, -1, 1); return; } gl_Position = vec4(1); }";
		String patched = IrisVulkanShadowMath.patchVertexDepth(input);
		check(patched.contains("iris_vulkan_shadow_pack_main();\n    gl_Position.z"), "depth conversion runs after early returns");
		check(patched.equals(IrisVulkanShadowMath.patchVertexDepth(patched)), "shadow patch is idempotent");
		expectFailure(() -> IrisVulkanShadowMath.patchVertexDepth("#version 450\n"), "missing main rejected");
		expectFailure(() -> IrisVulkanShadowMath.projection(32, 10, 5, null), "backwards depth rejected");

		check(IrisVulkanShadowMath.intersectsDistance(-130, 0, 0, -110, 16, 16, -100, 8, 8, 10),
			"casters touching negative-coordinate distance boundary included");
		check(!IrisVulkanShadowMath.intersectsDistance(-131, 0, 0, -111, 16, 16, -100, 8, 8, 10),
			"distant caster excluded");
		System.out.println("PASS: native shadow projection, forward depth, receiver coordinates, wrapper, caster bounds");
	}

	private static float shadowDepth(Matrix4f projection, float eyeZ) {
		Vector4f clip = new Vector4f(0, 0, eyeZ, 1).mul(projection);
		return (clip.z / clip.w) * 0.5f + 0.5f;
	}

	private static void close(float expected, float actual, String message) {
		check(Math.abs(expected - actual) < 0.0001f, message + ": " + expected + " != " + actual);
	}

	private static void expectFailure(Runnable action, String message) {
		try {
			action.run();
			throw new AssertionError(message);
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
