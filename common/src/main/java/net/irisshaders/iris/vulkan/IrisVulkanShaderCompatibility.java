package net.irisshaders.iris.vulkan;

import java.util.Arrays;
import java.util.regex.Pattern;

/** Narrow compatibility corrections for shader code with otherwise undefined Vulkan results. */
public final class IrisVulkanShaderCompatibility {
	private static final Pattern SAMPLER_PARAMETER = Pattern.compile("\\b[iu]?sampler[A-Za-z0-9_]+(?:\\s*\\[[^]{};]*])*\\s+(sampler)\\b");
	private static final Pattern SAMPLER_NAME = Pattern.compile("\\bsampler\\b");
	private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
	private static final Pattern LIGHT_VOLUME_ACCUMULATOR = Pattern.compile(
		"\\bvec4\\s+GetComplexLightVolume\\s*\\(\\s*vec3\\s+pos\\s*,\\s*sampler3D\\s+ff_sampler\\s*\\)"
			+ "\\s*\\{\\s*vec4\\s+(lightVolume)\\s*;");

	private IrisVulkanShaderCompatibility() { }

	/** Vulkan GLSL reserves sampler as a type; legacy packs also use it as a parameter name. */
	public static String renameSamplerParameters(String source) {
		if (source == null || !source.contains("sampler")) return source;
		char[] masked = IrisVulkanShaderPruning.maskComments(source).toCharArray();
		// Preprocessor diagnostic strings are not identifiers.
		for (int i = 0; i < masked.length; i++) if (masked[i] == '"') {
			masked[i++] = ' ';
			for (; i < masked.length; i++) {
				char c = masked[i]; masked[i] = ' ';
				if (c == '\\' && i + 1 < masked.length) masked[++i] = ' ';
				else if (c == '"') break;
			}
		}
		String code = new String(masked);
		String replacement = "iris_vk_samplerParameter";
		while (Pattern.compile("\\b" + replacement + "\\b").matcher(code).find()) replacement += "_";
		var parameters = SAMPLER_PARAMETER.matcher(code);
		java.util.SortedSet<Integer> positions = new java.util.TreeSet<>();
		while (parameters.find()) {
			int open = parameters.start() - 1, nested = 0;
			for (; open >= 0; open--) {
				char c = code.charAt(open);
				if (c == ')') nested++;
				else if (c == '(') { if (nested-- == 0) break; }
				else if (nested == 0 && (c == ';' || c == '{' || c == '}')) break;
			}
			if (open < 0 || code.charAt(open) != '(') continue; // Preserve resource/interface names.
			int close = matching(code, open, '(', ')');
			if (close < 0) continue;
			int body = close + 1;
			while (body < code.length() && Character.isWhitespace(code.charAt(body))) body++;
			int end;
			if (body < code.length() && code.charAt(body) == '{') end = matching(code, body, '{', '}');
			else if (body < code.length() && code.charAt(body) == ';') end = close;
			else continue;
			if (end < 0) continue;
			var names = SAMPLER_NAME.matcher(code).region(parameters.start(1), end + 1);
			while (names.find()) {
				int before = names.start() - 1;
				while (before >= 0 && Character.isWhitespace(code.charAt(before))) before--;
				if (before < 0 || code.charAt(before) != '.') positions.add(names.start());
			}
		}
		if (positions.isEmpty()) return source;
		StringBuilder result = new StringBuilder(source.length()); int copied = 0;
		for (int position : positions) { result.append(source, copied, position).append(replacement); copied = position + "sampler".length(); }
		return result.append(source, copied, source.length()).toString();
	}

	private static int matching(String code, int start, char open, char close) {
		int depth = 0;
		for (int i = start; i < code.length(); i++) {
			if (code.charAt(i) == open) depth++;
			else if (code.charAt(i) == close && --depth == 0) return i;
		}
		return -1;
	}

	/**
	 * Complementary r5.8.1's colored-light interpolation adds into an uninitialized
	 * local before taking its square root. GLSL leaves that value undefined, and
	 * Vulkan can produce a black hand from NaNs. Supply the intended zero sum only
	 * for this known function/declaration; explicitly initialized code is retained.
	 */
	public static String initializeLightVolumeAccumulator(String source) {
		if (source == null || !source.contains("GetComplexLightVolume")) return source;
		char[] code = source.toCharArray();
		var comments = COMMENTS.matcher(source);
		while (comments.find()) Arrays.fill(code, comments.start(), comments.end(), ' ');
		var accumulator = LIGHT_VOLUME_ACCUMULATOR.matcher(new String(code));
		StringBuilder result = null;
		int copied = 0;
		while (accumulator.find()) {
			if (result == null) result = new StringBuilder(source.length() + 16);
			int insertion = accumulator.end(1);
			result.append(source, copied, insertion).append(" = vec4(0.0)");
			copied = insertion;
		}
		return result == null ? source : result.append(source, copied, source.length()).toString();
	}
}
