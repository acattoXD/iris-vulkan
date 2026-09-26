package net.irisshaders.iris.vulkan;

import org.joml.Matrix4f;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shadow packs operate in OpenGL clip coordinates even when the attachment is native Vulkan. */
public final class IrisVulkanShadowMath {
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
	private static final String PACK_MAIN = "iris_vulkan_shadow_pack_main";

	private IrisVulkanShadowMath() {
	}

	public static Matrix4f projection(float distance, float near, float far, Float fov) {
		if (!Float.isFinite(near) || !Float.isFinite(far) || far <= near) {
			throw new IllegalArgumentException("Invalid shadow depth interval " + near + ".." + far);
		}
		if (fov != null) {
			if (!Float.isFinite(fov) || fov <= 0.0f || fov >= 180.0f) {
				throw new IllegalArgumentException("Invalid shadow FOV " + fov);
			}
			// Legacy perspective shadow packs require a positive eye-space near distance.
			return new Matrix4f().setPerspective((float) Math.toRadians(fov), 1.0f, Math.max(0.05f, near), far, false);
		}
		if (!Float.isFinite(distance) || distance <= 0.0f) {
			throw new IllegalArgumentException("Invalid shadow distance " + distance);
		}
		return new Matrix4f().setOrthoSymmetric(distance * 2.0f, distance * 2.0f, near, far, false);
	}

	/**
	 * Convert only the final position, after the pack's non-linear shadow distortion.
	 * Wrapping main also covers packs which return before the end of their main body.
	 * Keep X/Y unchanged: sampling coordinates and Vulkan's positive-height viewport
	 * then address the same texel. Shadow depth is forward (near=0, far=1), unlike the
	 * main Minecraft depth attachment, which uses reversed Z.
	 */
	public static String patchVertexDepth(String source) {
		if (source.contains("void " + PACK_MAIN + "(")) {
			return source;
		}
		Matcher main = MAIN.matcher(source);
		if (!main.find()) {
			throw new IllegalArgumentException("Shadow vertex shader has no main function");
		}
		String renamed = main.replaceFirst("void " + PACK_MAIN + "() {");
		return renamed + "\nvoid main() {\n    " + PACK_MAIN + "();\n    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n}\n";
	}

	public static boolean intersectsDistance(double minX, double minY, double minZ,
											 double maxX, double maxY, double maxZ,
											 double cameraX, double cameraY, double cameraZ, double distance) {
		return maxX >= cameraX - distance && minX <= cameraX + distance
			&& maxY >= cameraY - distance && minY <= cameraY + distance
			&& maxZ >= cameraZ - distance && minZ <= cameraZ + distance;
	}
}
