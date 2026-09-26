package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.gl.IrisLimits;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CPU-only logical target planning; logical indices are independent of MRT attachment slots. */
public final class IrisVulkanTargetIndices {
	public static final int LOGICAL_TARGET_COUNT = IrisLimits.MAX_COLOR_BUFFERS;
	public static final int MAX_COLOR_ATTACHMENTS = 8;
	private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
	private static final Pattern PREPROCESSOR_LINE = Pattern.compile("(?m)^\\h*#.*$");
	private static final Pattern TEXTURE_DECLARATION = Pattern.compile(
		"\\buniform\\s+(?:(?:lowp|mediump|highp|readonly|writeonly|coherent|volatile|restrict)\\s+)*"
			+ "(?:[iu]?sampler\\w*|[iu]?image\\w*)\\s+([^;]+);");
	private static final Pattern TARGET_NAME = Pattern.compile(
		"\\b(?:colortex\\d+|colorimg\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\\b");
	private static final Pattern UNRESOLVED_PREPROCESSOR = Pattern.compile(
		"(?m)^\\h*#\\h*(?:define|undef|include|if|ifdef|ifndef|elif|else|endif)\\b");
	private static final Pattern SIMPLE_SAMPLER_DECLARATION = Pattern.compile(
		"\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)*[iu]?sampler\\w+\\s+"
			+ "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\\s*;");
	private final TreeSet<Integer> required = new TreeSet<>();

	public void require(int index, String reason) {
		if (index < 0 || index >= LOGICAL_TARGET_COUNT) {
			throw new IllegalArgumentException(reason + " references colortex" + index
				+ "; supported logical indices are 0.." + (LOGICAL_TARGET_COUNT - 1));
		}
		required.add(index);
	}

	public void requireDrawBuffers(String pass, int[] drawBuffers) {
		validateDrawBuffers(pass, drawBuffers);
		for (int index : drawBuffers) {
			require(index, pass);
		}
	}

	/** Input must already have shaderpack options/includes/conditionals preprocessed by Iris. */
	public void requireDeclaredTextures(String preprocessedSource) {
		if (preprocessedSource == null) {
			return;
		}
		String source = PREPROCESSOR_LINE.matcher(COMMENTS.matcher(preprocessedSource).replaceAll(" "))
			.replaceAll("");
		Matcher declarations = TEXTURE_DECLARATION.matcher(source);
		while (declarations.find()) {
			Matcher names = TARGET_NAME.matcher(declarations.group(1));
			while (names.find()) {
				String name = names.group();
				String sampler = name.startsWith("colorimg") ? "colortex" + name.substring(8) : name;
				int index = colorSamplerTarget(sampler);
				if (index < 0) {
					throw new IllegalArgumentException("Texture declaration " + name
						+ " exceeds the supported logical color-target range 0.." + (LOGICAL_TARGET_COUNT - 1));
				}
				require(index, "Texture declaration " + name);
			}
		}
	}

	/**
	 * Do not allocate a framebuffer solely for an unused, simple sampler declaration.
	 * This plans resources only; shader source and compiler behavior are unchanged.
	 * Image declarations, arrays and multiple/unknown declarators stay conservative.
	 * Draw buffers, mipmaps, flips and internal fallbacks are required separately.
	 */
	public void requireReferencedTextures(String preprocessedSource) {
		if (preprocessedSource == null) return;
		IrisVulkanTargetIndices declared = new IrisVulkanTargetIndices();
		// Validate the original declarations even when an unused sampler could be omitted.
		declared.requireDeclaredTextures(preprocessedSource);
		String source = COMMENTS.matcher(preprocessedSource).replaceAll(" ");
		if (UNRESOLVED_PREPROCESSOR.matcher(source).find()) {
			required.addAll(declared.required);
			return;
		}
		// Stay at least as conservative as the existing native resource compiler.
		// For example, duplicate declarations or an identifier inside a #line path
		// can make it retain an otherwise unused sampler in the pipeline interface.
		IrisVulkanTargetIndices compilerDeclarations = new IrisVulkanTargetIndices();
		compilerDeclarations.requireDeclaredTextures(IrisVulkanShaderPruning.removeUnusedUniforms(preprocessedSource));
		required.addAll(compilerDeclarations.required);
		source = PREPROCESSOR_LINE.matcher(source).replaceAll("");
		source = SIMPLE_SAMPLER_DECLARATION.matcher(source).replaceAll(" ");
		Matcher names = TARGET_NAME.matcher(source);
		while (names.find()) {
			String name = names.group();
			int index = colorSamplerTarget(name.startsWith("colorimg") ? "colortex" + name.substring(8) : name);
			if (declared.required.contains(index)) required.add(index);
		}
	}

	public Set<Integer> indices() {
		return Collections.unmodifiableSet(new TreeSet<>(required));
	}

	public static int colorSamplerTarget(String sampler) {
		if (sampler == null) {
			return -1;
		}
		if (sampler.startsWith("colortex")) {
			String suffix = sampler.substring(8);
			if (suffix.isEmpty() || !suffix.chars().allMatch(character -> character >= '0' && character <= '9')) {
				return -1;
			}
			try {
				int index = Integer.parseInt(suffix);
				return index < LOGICAL_TARGET_COUNT ? index : -1;
			} catch (NumberFormatException ignored) {
				return -1;
			}
		}
		// Keep these aliases identical to PackRenderTargetDirectives.LEGACY_RENDER_TARGETS.
		return switch (sampler) {
			case "gcolor" -> 0;
			case "gdepth" -> 1;
			case "gnormal" -> 2;
			case "composite" -> 3;
			case "gaux1" -> 4;
			case "gaux2" -> 5;
			case "gaux3" -> 6;
			case "gaux4" -> 7;
			default -> -1;
		};
	}

	public static void validateDrawBuffers(String pass, int[] drawBuffers) {
		if (drawBuffers == null || drawBuffers.length > MAX_COLOR_ATTACHMENTS) {
			throw new IllegalArgumentException(pass + " exceeds the native limit of " + MAX_COLOR_ATTACHMENTS
				+ " simultaneous color attachments; logical texture indices do not increase that limit");
		}
		Set<Integer> seen = new TreeSet<>();
		for (int index : drawBuffers) {
			if (index < 0 || index >= LOGICAL_TARGET_COUNT || !seen.add(index)) {
				throw new IllegalArgumentException(pass + " has an invalid or duplicate logical color target " + index);
			}
		}
	}
}
