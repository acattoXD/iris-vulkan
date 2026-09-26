package iris.diagnostics;

import java.io.BufferedWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Diagnostic-only attach agent. Never changes a game/mod field or invokes game/native-window methods. */
public final class DynamicFpsReadOnlyAgent {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final int SAMPLES = 30;

    public static void agentmain(String outputArgument, Instrumentation instrumentation) {
        Path output = Path.of(outputArgument);
        if (!output.isAbsolute() || !output.getFileName().toString().endsWith(".jsonl")) {
            throw new IllegalArgumentException("Pass an absolute .jsonl output path");
        }
        if (!RUNNING.compareAndSet(false, true)) throw new IllegalStateException("A state capture is already running");
        Map<String, Class<?>> loaded = new LinkedHashMap<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName();
            if (name.equals("dynamic_fps.impl.DynamicFPSMod") || name.equals("dynamic_fps.impl.feature.state.IdleHandler")
                || name.equals("dynamic_fps.impl.config.DynamicFPSConfig")) loaded.put(name, type);
        }
        Thread observer = new Thread(() -> capture(output.normalize(), loaded), "Iris read-only Dynamic FPS state observer");
        observer.setDaemon(true);
        observer.start();
    }

    private static void capture(Path output, Map<String, Class<?>> loaded) {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int sample = 0; sample < SAMPLES; sample++) {
                long now = System.currentTimeMillis();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sample", sample);
                row.put("timestampEpochMillis", now);
                row.put("scope", "Read-only fields; no updates, GLFW calls or configuration writes; snapshots are not transactional");
                // The target is the active game: its recorded render/state-change
                // calls establish these classes have already initialized. Never
                // discover/load a mod class with Class.forName or loadClass.
                Class<?> mod = loaded.get("dynamic_fps.impl.DynamicFPSMod");
                Class<?> idleClass = loaded.get("dynamic_fps.impl.feature.state.IdleHandler");
                Class<?> configClass = loaded.get("dynamic_fps.impl.config.DynamicFPSConfig");
                row.put("modClassAlreadyLoaded", mod != null);
                if (mod != null) {
                    Object selected = field(mod, null, "config"), window = field(mod, null, "window");
                    Object settings = configClass == null ? null : field(configClass, null, "INSTANCE");
                    Object idle = field(settings, "idle");
                    Object rate = field(selected, "frameRateTarget");
                    Object previousActivity = idleClass == null ? null : field(idleClass, null, "previousActivity");
                    Object selectedState = field(selected, "state");
                    row.put("currentState", field(mod, null, "state"));
                    row.put("selectedConfigurationState", selectedState == null ? "FOCUSED_OR_UNAVAILABLE" : selectedState);
                    row.put("rawTargetFps", rate);
                    row.put("effectiveTargetFps", rate instanceof Number n && n.intValue() == -1 ? 260 : rate);
                    row.put("configuredEnabled", field(settings, "enabled"));
                    row.put("forcedLowFps", field(mod, null, "isForcingLowFPS"));
                    row.put("disabledByKeybind", field(mod, null, "isKeybindDisabled"));
                    row.put("windowFocused", field(window, "isFocused"));
                    row.put("windowHovered", field(window, "isHovered"));
                    row.put("windowIconified", field(window, "isIconified"));
                    row.put("idleTrackingActive", idleClass == null ? null : field(idleClass, null, "active"));
                    row.put("lastObservedIdle", idleClass == null ? null : field(idleClass, null, "wasIdle"));
                    row.put("idleCondition", field(idle, "condition"));
                    row.put("idleTimeoutSeconds", field(idle, "timeout"));
                    row.put("previousActivityEpochMillis", previousActivity);
                    row.put("millisecondsSinceActivity", previousActivity instanceof Number n ? now - n.longValue() : null);
                    row.put("lastRenderEpochMillis", field(mod, null, "lastRender"));
                    row.put("renderedCurrentFrame", field(mod, null, "renderedCurrentFrame"));
                    row.put("hasRenderedLastFrame", field(mod, null, "hasRenderedLastFrame"));
                    row.put("selectedVsync", field(selected, "enableVsync"));
                    row.put("selectedRunGarbageCollector", field(selected, "runGarbageCollector"));
                    row.put("selectedGraphicsState", field(selected, "graphicsState"));
                }
                writer.write(json(row)); writer.newLine(); writer.flush();
                if (sample + 1 < SAMPLES) Thread.sleep(1000);
            }
        } catch (Exception error) {
            // No retry, no application logger, and no serialized game object or stack dump.
            System.err.println("Read-only Dynamic FPS capture ended: " + error.getClass().getSimpleName());
        } finally { RUNNING.set(false); }
    }

    private static Object field(Object instance, String name) { return instance == null ? null : field(instance.getClass(), instance, name); }
    private static Object field(Class<?> type, Object instance, String name) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
            catch (NoSuchFieldException ignored) { }
            catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
        }
        return null;
    }
    private static String json(Map<String, Object> values) {
        StringBuilder result = new StringBuilder("{"); boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) result.append(','); first = false;
            result.append(quote(entry.getKey())).append(':'); Object value = entry.getValue();
            if (value == null) result.append("null");
            else if (value instanceof Number || value instanceof Boolean) result.append(value);
            else if (value instanceof Enum<?> enumeration) result.append(quote(enumeration.name()));
            else if (value instanceof String text) result.append(quote(text));
            else result.append(quote("UNSUPPORTED_VALUE_TYPE"));
        }
        return result.append('}').toString();
    }
    private static String quote(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
}
