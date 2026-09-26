package net.irisshaders.iris.vulkan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative removal of declarations the preprocessed shader never references. */
public final class IrisVulkanShaderPruning {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UNIFORM = Pattern.compile(
            "(?m)^\\h*(?:layout\\h*\\([^)]*\\)\\h*)?uniform\\h+(?:(?:lowp|mediump|highp|coherent|volatile|restrict|readonly|writeonly)\\h+)*[A-Za-z_]\\w*\\h+([A-Za-z_][^;{}]*);"
    );
    private static final Pattern SINGLE_DECLARATOR = Pattern.compile("\\h*([A-Za-z_]\\w*)\\h*(?:\\[[^]]*]\\h*)*(?:=[\\s\\S]*)?");
    private static final Pattern PLAIN_INPUT = Pattern.compile(
            "(?m)^\\h*in\\h+(?:float|double|int|uint|bool|[bdiu]?vec[234]|d?mat[234](?:x[234])?)\\h+([A-Za-z_]\\w*)\\h*;\\h*(?=\\r?$)"
    );

    private IrisVulkanShaderPruning() { }

    public static String removeUnusedUniforms(String source) {
        if (source == null) return null;
        String code = maskComments(source);
        Map<String, Integer> counts = new HashMap<>();
        var identifiers = IDENTIFIER.matcher(code);
        while (identifiers.find()) counts.merge(identifiers.group(), 1, Integer::sum);
        var declarations = UNIFORM.matcher(code);
        StringBuilder result = null;
        while (declarations.find()) {
            // Iris's transformed uniforms each have one declarator. Retain unusual
            // declarations rather than risk removing an opaque array or shared name.
            String declaration = declarations.group(1);
            if (declaration.indexOf(',') >= 0) continue;
            var name = SINGLE_DECLARATOR.matcher(declaration);
            if (!name.matches() || counts.getOrDefault(name.group(1), 0) != 1) continue;
            if (result == null) result = new StringBuilder(source);
            for (int i = declarations.start(); i < declarations.end(); i++) {
                char character = result.charAt(i);
                if (character != '\n' && character != '\r') result.setCharAt(i, ' ');
            }
        }
        return result == null ? source : result.toString();
    }

    /**
     * Mojang reflects even unused inputs before linking stages. Remove only plain
     * declarations with no possible reference so absent, unused pack varyings do
     * not make a valid vertex/fragment pair fail this early interface check.
     */
    public static String removeUnusedInputs(String source) {
        return removeUnusedInputs(source, Set.of());
    }

    /**
     * Preserve the interface slots produced by the vertex stage, even when the
     * fragment never reads them. Mojang's rebind() numbers only inputs it finds,
     * so deleting a matched input would shift every later fragment location.
     */
    public static String removeUnmatchedUnusedInputs(String vertex, String fragment) {
        if (vertex == null || fragment == null) return fragment;
        String vertexCode = maskComments(vertex);
        if (vertexCode.contains("##")) return fragment;
        Set<String> vertexNames = new HashSet<>();
        var names = IDENTIFIER.matcher(vertexCode);
        while (names.find()) vertexNames.add(names.group());
        // Keeping every name mentioned by the producer is deliberately conservative:
        // it covers qualified, shared, array and macro-generated output declarations.
        return removeUnusedInputs(fragment, vertexNames);
    }

    private static String removeUnusedInputs(String source, Set<String> preservedNames) {
        if (source == null) return null;
        String code = maskComments(source);
        // A macro can manufacture an identifier from tokens that are not present
        // as a complete name in the source; lexical reference counts cannot prove
        // that any input is unused in that case.
        if (code.contains("##")) return source;
        Map<String, Integer> counts = new HashMap<>();
        var identifiers = IDENTIFIER.matcher(code);
        while (identifiers.find()) counts.merge(identifiers.group(), 1, Integer::sum);
        var declarations = PLAIN_INPUT.matcher(maskPreprocessorLines(code));
        StringBuilder result = null;
        while (declarations.find()) {
            String name = declarations.group(1);
            if (preservedNames.contains(name) || counts.getOrDefault(name, 0) != 1) continue;
            if (result == null) result = new StringBuilder(source);
            for (int i = declarations.start(); i < declarations.end(); i++) {
                char character = result.charAt(i);
                if (character != '\n' && character != '\r') result.setCharAt(i, ' ');
            }
        }
        return result == null ? source : result.toString();
    }

    private static String maskPreprocessorLines(String code) {
        char[] result = code.toCharArray();
        boolean continuation = false;
        for (int start = 0; start < code.length();) {
            int end = code.indexOf('\n', start);
            if (end < 0) end = code.length();
            int first = start;
            while (first < end && Character.isWhitespace(code.charAt(first))) first++;
            boolean directive = continuation || first < end && code.charAt(first) == '#';
            continuation = false;
            if (directive) {
                int last = end - 1;
                while (last >= start && Character.isWhitespace(code.charAt(last))) last--;
                continuation = last >= start && code.charAt(last) == '\\';
                for (int i = start; i < end; i++) {
                    if (result[i] != '\r') result[i] = ' ';
                }
            }
            start = end + 1;
        }
        return new String(result);
    }

    /** Preserve positions/newlines and consume line comments before interpreting their contents. */
    static String maskComments(String source) {
        char[] result = source.toCharArray();
        for (int i = 0; i + 1 < result.length; i++) {
			// Quoted preprocessor paths/diagnostics can contain // or /*.
			// Leave their text intact while skipping it as a comment source.
			if (result[i] == '"') {
				for (i++; i < result.length; i++) {
					if (result[i] == '\\' && i + 1 < result.length) i++;
					else if (result[i] == '"') break;
				}
				continue;
			}
            if (result[i] != '/') continue;
            if (result[i + 1] == '/') {
                while (i < result.length && result[i] != '\n' && result[i] != '\r') result[i++] = ' ';
            } else if (result[i + 1] == '*') {
                result[i++] = ' ';
                result[i++] = ' ';
                while (i < result.length) {
                    if (i + 1 < result.length && result[i] == '*' && result[i + 1] == '/') {
                        result[i++] = ' ';
                        result[i] = ' ';
                        break;
                    }
                    if (result[i] != '\n' && result[i] != '\r') result[i] = ' ';
                    i++;
                }
            }
        }
        return new String(result);
    }
}
