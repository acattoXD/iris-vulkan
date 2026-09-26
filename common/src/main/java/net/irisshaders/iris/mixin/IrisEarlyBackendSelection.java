package net.irisshaders.iris.mixin;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Resolve startup preference without loading Minecraft classes before their mixins. */
public final class IrisEarlyBackendSelection {
    // Minecraft 26.3's OptionsForceDefaultGraphicsApiFix resets older options.
    static final int FORCE_DEFAULT_DATA_VERSION = 4892;

    private IrisEarlyBackendSelection() { }

    public record Selection(boolean vulkan, String source) { }

    public static Selection read(Path gameDirectory, String[] launchArguments) {
        Map<String, String> options = new LinkedHashMap<>();
        Path path = gameDirectory.resolve("options.txt");
        if (Files.isRegularFile(path)) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                reader.lines().forEach(line -> {
                    int separator = line.indexOf(':');
                    if (separator >= 0) options.put(line.substring(0, separator), line.substring(separator + 1));
                });
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read graphics backend preference from options.txt", e);
            }
        }
        return resolve(launchArguments, options);
    }

    /** Mirrors Main argument precedence, Options migration and startup-crash fallback. */
    public static Selection resolve(String[] arguments, Map<String, String> options) {
        for (int i = 0; i < arguments.length; ++i) {
            String argument = arguments[i];
            if (argument.equals("--")) break;
            String value;
            if (argument.equals("--graphicsBackend") || argument.equals("-graphicsBackend")) {
                if (++i == arguments.length) throw new IllegalArgumentException("Missing --graphicsBackend value");
                value = arguments[i];
            } else if (argument.startsWith("--graphicsBackend=")) {
                value = argument.substring("--graphicsBackend=".length());
            } else if (argument.startsWith("-graphicsBackend=")) {
                value = argument.substring("-graphicsBackend=".length());
            } else continue;
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "VULKAN" -> new Selection(true, "launch argument");
                case "OPENGL", "DEFAULT" -> new Selection(false, "launch argument");
                default -> throw new IllegalArgumentException("Invalid --graphicsBackend value; expected DEFAULT, OPENGL or VULKAN");
            };
        }

        int dataVersion;
        try { dataVersion = Integer.parseInt(options.getOrDefault("version", "0")); }
        catch (NumberFormatException ignored) { dataVersion = 0; }
        if (dataVersion < FORCE_DEFAULT_DATA_VERSION) return new Selection(false, "options migration default");

        String preferred = "default";
        try {
            var value = JsonParser.parseString(options.getOrDefault("preferredGraphicsBackend", "default"));
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) preferred = value.getAsString();
        } catch (RuntimeException ignored) {
            // Minecraft leaves its default value when the option codec rejects it.
        }
        boolean startedCleanly = !options.containsKey("startedCleanly") || options.get("startedCleanly").equals("true");
        if (!startedCleanly) return new Selection(false, "Minecraft startup-crash fallback");
        // DEFAULT and OPENGL try OpenGL first in final 26.3. The option codec is case-sensitive.
        return new Selection(preferred.equals("vulkan"), "options.txt");
    }

    /** Fabric's sanitized arguments retain graphics options and omit credentials. */
    public static String[] launchArguments() {
        try {
            Class<?> loaderApi = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = loaderApi.getMethod("getInstance").invoke(null);
            return (String[]) loaderApi.getMethod("getLaunchArguments", boolean.class).invoke(loader, true);
        } catch (ClassNotFoundException ignored) {
            return new String[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read Fabric launch arguments before graphics mixin selection", e);
        }
    }
}
