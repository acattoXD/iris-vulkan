package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanComputeExecutor;
import net.minecraft.client.CameraType;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed-scene packaged-JAR benchmark. All instrumentation lives in the disposable test mod. */
public final class NativeBenchmarkProbe {
    private static final ThreadMXBean THREAD = ManagementFactory.getThreadMXBean();
    private static final long START = System.nanoTime();
    private static final int RING = 128;
    private static final AtomicBoolean SCREENSHOT_PENDING = new AtomicBoolean();
    private static final AtomicReference<Throwable> SCREENSHOT_FAILURE = new AtomicReference<>();
    private static NativeBenchmarkProbe active;
    private static boolean done;
    private static long frameStart, threadStart, previousFrameStart;
    private static double frameInterval;
    private final Minecraft client;
    private final Path run;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Window> windows = new ArrayList<>();
    private final GpuQueryPool queries;
    private final double timestampPeriod;
    private final Slot[] slots = new Slot[RING];
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();
    private final long warmupNs, intervalNs;
    private final int repetitions;
    private CompletableFuture<Void> setup;
    private NativeUltraReadback.Capture storageCapture;
    private int phase, repetition, frameNumber, currentSlot = -1, sampleIndex = -1, sampleWindow = -1;
    private long phaseStart, worldStart, lastProgress;
    private double worldCpuMs;
    private int droppedQueries;

    private NativeBenchmarkProbe(Minecraft client, Path run) throws Exception {
        this.client = client;
        this.run = run;
        warmupNs = seconds("iris.benchmark.warmupSeconds", 15, 0.5, 120);
        intervalNs = seconds("iris.benchmark.measureSeconds", 20, 1, 120);
        repetitions = Integer.getInteger("iris.benchmark.repetitions", 3);
        require(repetitions >= 1 && repetitions <= 10, "Repetitions must be 1..10");
        require(THREAD.isCurrentThreadCpuTimeSupported(), "Render-thread CPU timing unsupported");
        if (!THREAD.isThreadCpuTimeEnabled()) THREAD.setThreadCpuTimeEnabled(true);
        var device = RenderSystem.getDevice().getDeviceInfo();
        require(device.backendName().equalsIgnoreCase("Vulkan"), "GPU benchmark requires actual Vulkan");
        timestampPeriod = device.timestampPeriod();
        require(timestampPeriod > 0 && Double.isFinite(timestampPeriod), "Invalid GPU timestamp period");
        queries = RenderSystem.getDevice().createTimestampQueryPool(RING * 2);
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
        for (int i = 0; i < repetitions; i++) windows.add(new Window((int) (intervalNs / 1_000_000_000.0 * 1000) + 2048));
        report.put("label", System.getProperty("iris.benchmark.label", "benchmark"));
        report.put("device", Map.of("name", device.name(), "vendor", device.vendorName(), "driver", device.driverInfo(), "backend", device.backendName(), "timestampPeriodNs", timestampPeriod));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        report.put("java", System.getProperty("java.version"));
        report.put("warmupSeconds", warmupNs / 1e9);
        report.put("measurementSeconds", intervalNs / 1e9);
        report.put("repetitions", repetitions);
        report.put("gpuScope", "GameRenderer.renderLevel HEAD through RETURN: world, shadows, hand and composition; excludes GUI/presentation");
        report.put("gpuReadPolicy", "128 paired timestamp slots; query availability polled without WAIT, minimum four frames later; no texture/storage readbacks during warmup or measurement");
        report.put("cpuScope", "frameIntervalMs is runTick start-to-start; frameWallMs is runTick wall duration; renderThreadCpuMs is ThreadMXBean CPU duration; worldSubmitMs is renderLevel CPU wall duration");
        report.put("productionFlags", ManagementFactory.getRuntimeMXBean().getInputArguments().stream().filter(s -> s.startsWith("-Diris.vulkan.")).toList());
        require(((List<?>) report.get("productionFlags")).isEmpty(), "Rendering development switches are forbidden in packaged comparisons");
        report.put("loadedClasses", classEvidence());
        Path inputManifest = run.resolve("benchmark-input.json");
        require(Files.isRegularFile(inputManifest), "Missing packaged benchmark input provenance");
        report.put("input", com.google.gson.JsonParser.parseString(Files.readString(inputManifest)));
        client.options.renderDistance().set(4);
        client.options.fov().set(70);
        client.options.framerateLimit().set(260);
        client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        client.options.enableVsync().set(false);
        client.options.bobView().set(true);
        client.options.setCameraType(CameraType.FIRST_PERSON);
        client.options.save();
        var server = client.getSingleplayerServer();
        require(server != null, "Benchmark requires copied isolated singleplayer world");
        setup = CompletableFuture.runAsync(() -> {
            var source = server.createCommandSourceStack().withSuppressedOutput();
            for (String command : List.of("tp @p 0.0 64.0 15.0 180 17", "time set 4000", "weather clear")) {
                server.getCommands().performPrefixedCommand(source, command);
            }
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
            server.clockManager().setPaused(clock, true);
            server.forceGameTimeSynchronization();
        }, server::execute);
    }

    public static void beginFrame() {
        if (!Boolean.getBoolean("iris.benchmark") || done) return;
        long now = System.nanoTime();
        frameInterval = previousFrameStart == 0 ? Double.NaN : (now - previousFrameStart) / 1e6;
        previousFrameStart = now;
        frameStart = now;
        threadStart = THREAD.isThreadCpuTimeEnabled() ? THREAD.getCurrentThreadCpuTime() : 0;
        if (active != null) {
            if (active.client.player != null) {
                active.client.player.setYRot(180);
                active.client.player.setXRot(17);
                active.client.player.setOldPosAndRot();
            }
            active.currentSlot = -1;
            active.sampleIndex = -1;
            active.sampleWindow = -1;
            active.worldCpuMs = Double.NaN;
            if (active.phase == 2) {
                active.sampleWindow = active.repetition;
                active.sampleIndex = active.windows.get(active.repetition).size;
            }
        }
    }

    public static void beginWorld() {
        var probe = active;
        if (probe == null || done) return;
        probe.worldStart = System.nanoTime();
        if (probe.phase != 2) return;
        for (int index = 0; index < RING; index++) {
            Slot slot = probe.slots[index];
            if (!slot.pending) {
                probe.currentSlot = index;
                slot.pending = true;
                slot.frame = probe.frameNumber;
                slot.window = probe.sampleWindow;
                slot.sample = probe.sampleIndex;
                RenderSystem.getDevice().createCommandEncoder().writeTimestamp(probe.queries, index * 2);
                return;
            }
        }
        probe.droppedQueries++;
    }

    public static void endWorld() {
        var probe = active;
        if (probe == null || done) return;
        probe.worldCpuMs = (System.nanoTime() - probe.worldStart) / 1e6;
        if (probe.currentSlot >= 0) {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(probe.queries, probe.currentSlot * 2 + 1);
            probe.pending.addLast(probe.currentSlot);
        }
    }

    public static void frame(Minecraft client) {
        if (done) return;
        long end = System.nanoTime();
        long threadEnd = THREAD.isThreadCpuTimeEnabled() ? THREAD.getCurrentThreadCpuTime() : 0;
        Path run = client.gameDirectory.toPath().toAbsolutePath().normalize();
        try {
            require(run.equals(Path.of(System.getProperty("iris.benchmark.runDir", "")).toAbsolutePath().normalize()) && run.getFileName().toString().contains("benchmark"), "Benchmark requires its explicit isolated benchmark directory");
            require(System.nanoTime() - START < 1_800_000_000_000L, "Benchmark startup timed out");
            if (client.gui.overlay() != null || client.player == null || client.level == null) return;
            if (client.gui.screen() instanceof PauseScreen) client.gui.setScreen(null);
            if (client.gui.screen() != null) {
                require(active == null || active.phase != 2, "A screen interrupted the measurement window");
                return;
            }
            if (active == null) { active = new NativeBenchmarkProbe(client, run); return; }
            active.frameNumber++;
            active.pollQueries();
            if (active.sampleWindow >= 0 && active.sampleIndex >= 0) {
                Window window = active.windows.get(active.sampleWindow);
                window.add(frameInterval, (end - frameStart) / 1e6,
                        (threadEnd - threadStart) / 1e6, active.worldCpuMs);
                var limiter = client.getFramerateLimitTracker();
                var reason = limiter.getThrottleReason();
                int cap = limiter.getFramerateLimit();
                boolean focused = client.getWindow().isFocused(), iconified = client.getWindow().isIconified();
                window.recordLimiter(cap, reason, focused, iconified);
                if (!validLimiter(client.options.inactivityFpsLimit().get(), reason, cap, iconified)) {
                    active.report.put("limiterFailure", active.limiterState());
                    throw new IllegalStateException("Measurement was throttled or limiter settings changed: " + reason + ", cap=" + cap);
                }
            }
            active.advance(end);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(client, run, failure);
        }
    }

    private void advance(long now) throws Exception {
        if (now - lastProgress > 5_000_000_000L) {
            lastProgress = now;
            Iris.logger.info("IRIS_BENCHMARK_PROGRESS phase={} repetition={} frames={} pendingGpu={}", phase, repetition + 1, frameNumber, pending.size());
        }
        if (phase == 0) {
            if (!setup.isDone()) return;
            setup.join();
            if (client.player.position().distanceToSqr(0, 64, 15) > 0.01) return;
            client.player.setYRot(180);
            client.player.setXRot(17);
            client.player.setOldPosAndRot();
            NativeUltraReadback.requireUltraOptions(Iris.getCurrentPack().orElseThrow());
            require(client.gameRenderer.mainRenderTarget().width == 1280 && client.gameRenderer.mainRenderTarget().height == 720, "Benchmark resolution must be 1280x720");
            phase = 1; phaseStart = now;
            report.put("startState", state());
        } else if (phase == 1 && now - phaseStart >= warmupNs) {
            phase = 2; phaseStart = now;
        } else if (phase == 2 && now - phaseStart >= intervalNs) {
            windows.get(repetition).elapsedNs = now - phaseStart;
            repetition++;
            if (repetition < repetitions) { phase = 1; phaseStart = now - warmupNs + 1_000_000_000L; }
            else { phase = 3; phaseStart = now; }
        } else if (phase == 3) {
            if (!pending.isEmpty() && now - phaseStart < 5_000_000_000L) return;
            report.put("unavailableGpuSamples", pending.size());
            report.put("droppedGpuQueries", droppedQueries);
            report.put("endState", state());
            Files.createDirectories(run.resolve("evidence"));
            SCREENSHOT_PENDING.set(true);
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                try { image.writeToFile(run.resolve("evidence/benchmark-after.png")); }
                catch (Throwable error) { SCREENSHOT_FAILURE.set(error); }
                finally { image.close(); SCREENSHOT_PENDING.set(false); }
            });
            storageCapture = NativeUltraReadback.capture(run.resolve("evidence"), "benchmark-after", client);
            phase = 4; phaseStart = now;
        } else if (phase == 4) {
            require(now - phaseStart < 30_000_000_000L, "Post-measurement evidence timed out");
            if (SCREENSHOT_FAILURE.get() != null) throw new IllegalStateException("Screenshot failed", SCREENSHOT_FAILURE.get());
            if (SCREENSHOT_PENDING.get()) return;
            if (storageCapture != null) {
                var storage = storageCapture.poll();
                if (storage == null) return;
                require(storage.activityPassed(), "Post-measurement Ultra GPU activity verification failed");
                report.put("postMeasurementStorage", storage);
                storageCapture = null;
            }
            List<Object> result = new ArrayList<>();
            for (Window window : windows) result.add(window.summary());
            report.put("windows", result);
            report.put("measuredGpuSamples", windows.stream().mapToInt(Window::gpuSamples).sum());
            require(windows.stream().allMatch(window -> window.size > 0 && window.gpuSamples() > window.size * 0.9), "Insufficient paired GPU measurements");
            StringBuilder csv = new StringBuilder("window,frame,frameIntervalMs,frameWallMs,renderThreadCpuMs,worldSubmitMs,gpuWorldMs,effectiveCapFps,throttleReason,windowFocused,windowIconified\n");
            for (int index = 0; index < windows.size(); index++) {
                Window window = windows.get(index);
                for (int frame = 0; frame < window.size; frame++) csv.append(index + 1).append(',').append(frame).append(',')
                        .append(window.intervals[frame]).append(',').append(window.wall[frame]).append(',').append(window.cpu[frame]).append(',')
                        .append(window.world[frame]).append(',').append(window.gpu[frame]).append(',')
                        .append(window.effectiveCaps[frame]).append(',').append(window.throttleReasons[frame]).append(',')
                        .append(window.focused[frame]).append(',').append(window.iconified[frame]).append('\n');
            }
            Files.writeString(run.resolve("evidence/benchmark-samples.csv"), csv);
            finish(client, run, null);
        }
    }

    private void pollQueries() {
        for (int budget = 0; budget < 4 && !pending.isEmpty(); budget++) {
            int index = pending.peekFirst();
            Slot slot = slots[index];
            if (frameNumber - slot.frame < 4) return;
            var values = queries.getValues(index * 2, 2); // availability flags; no WAIT bit.
            if (values[0].isEmpty() || values[1].isEmpty()) return;
            long elapsed = values[1].getAsLong() - values[0].getAsLong();
            if (elapsed >= 0 && slot.window >= 0 && slot.sample >= 0) {
                windows.get(slot.window).gpu[slot.sample] = elapsed * timestampPeriod / 1e6;
            }
            slot.pending = false;
            pending.removeFirst();
        }
    }

    private Map<String, Object> state() {
        var pos = client.gameRenderer.mainCamera().position();
        Map<String, Object> state = new LinkedHashMap<>(Map.of("camera", List.of(pos.x(), pos.y(), pos.z()), "player", List.of(client.player.getX(), client.player.getY(), client.player.getZ()),
                "yawPitch", List.of(client.player.getYRot(), client.player.getXRot()), "resolution", List.of(client.gameRenderer.mainRenderTarget().width, client.gameRenderer.mainRenderTarget().height),
                "fov", client.options.fov().get(), "renderDistance", client.options.renderDistance().get(), "maxFps", client.options.framerateLimit().get(),
                "vsync", client.options.enableVsync().get(), "pack", Iris.getCurrentPackName(), "lastComputeDispatch", String.valueOf(IrisVulkanComputeExecutor.lastDispatch())));
        state.put("limiter", limiterState());
        return state;
    }

    private Map<String, Object> limiterState() {
        var limiter = client.getFramerateLimitTracker();
        return Map.of("inactivityFpsLimit", client.options.inactivityFpsLimit().get().getSerializedName(),
                "throttleReason", limiter.getThrottleReason().name(), "effectiveCapFps", limiter.getFramerateLimit(),
                "windowFocused", client.getWindow().isFocused(), "windowIconified", client.getWindow().isIconified());
    }

    static boolean validLimiter(InactivityFpsLimit option, FramerateThrottleReason reason, int cap, boolean iconified) {
        return option == InactivityFpsLimit.MINIMIZED && reason == FramerateThrottleReason.NONE && cap == 260 && !iconified;
    }

    private static Map<String, Object> classEvidence() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : List.of("net.irisshaders.iris.Iris", "net.irisshaders.iris.vulkan.IrisVulkanRenderPassBindings", "net.irisshaders.iris.vulkan.IrisVulkanUniformSnapshot", "net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline", "net.irisshaders.iris.probe.NativeBenchmarkProbe")) {
            Class<?> type = Class.forName(name);
            var url = type.getClassLoader().getResource(name.replace('.', '/') + ".class");
            try (var in = url.openStream()) { result.put(name, Map.of("source", url.toString(), "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())))); }
        }
        return result;
    }

    private static long seconds(String key, double fallback, double min, double max) {
        double value = Double.parseDouble(System.getProperty(key, Double.toString(fallback)));
        require(Double.isFinite(value) && value >= min && value <= max, "Invalid duration " + key);
        return (long) (value * 1e9);
    }

    private static void finish(Minecraft client, Path run, Throwable failure) {
        done = true;
        try {
            Map<String, Object> result = active == null ? new LinkedHashMap<>() : active.report;
            result.put("passed", failure == null);
            if (failure != null) result.put("failure", failure.toString());
            Files.createDirectories(run.resolve("evidence"));
            Files.writeString(run.resolve("evidence/benchmark-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
            Files.writeString(run.resolve("benchmark-result.txt"), failure == null ? "IRIS_BENCHMARK_PASS\n" : "IRIS_BENCHMARK_FAIL\n" + failure);
        } catch (Throwable error) { error.printStackTrace(); }
        finally { if (active != null) { if (active.storageCapture != null) active.storageCapture.close(); active.queries.close(); } client.stop(); }
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    private static final class Slot { boolean pending; int frame, window, sample; }

    static final class Window {
        final double[] intervals, wall, cpu, world, gpu;
        final int[] effectiveCaps;
        final FramerateThrottleReason[] throttleReasons;
        final boolean[] focused, iconified;
        int size;
        long elapsedNs;
        Window(int capacity) {
            intervals = new double[capacity]; wall = new double[capacity]; cpu = new double[capacity]; world = new double[capacity]; gpu = new double[capacity];
            effectiveCaps = new int[capacity]; throttleReasons = new FramerateThrottleReason[capacity];
            focused = new boolean[capacity]; iconified = new boolean[capacity];
            Arrays.fill(gpu, Double.NaN);
        }
        void add(double interval, double wallTime, double cpuTime, double worldTime) {
            require(size < wall.length, "Benchmark sample capacity exhausted");
            intervals[size] = interval; wall[size] = wallTime; cpu[size] = cpuTime; world[size] = worldTime; size++;
        }
        int gpuSamples() { int count = 0; for (int i = 0; i < size; i++) if (Double.isFinite(gpu[i])) count++; return count; }
        void recordLimiter(int cap, FramerateThrottleReason reason, boolean hasFocus, boolean minimized) {
            require(size > 0, "Limiter state needs a corresponding frame sample");
            effectiveCaps[size - 1] = cap; throttleReasons[size - 1] = reason;
            focused[size - 1] = hasFocus; iconified[size - 1] = minimized;
        }
        Map<String, Object> limiterSummary() {
            Map<String, Integer> reasons = new LinkedHashMap<>(), caps = new LinkedHashMap<>();
            int focusedFrames = 0, iconifiedFrames = 0, samples = 0;
            for (int i = 0; i < size; i++) {
                if (throttleReasons[i] == null) continue;
                samples++;
                reasons.merge(throttleReasons[i].name(), 1, Integer::sum);
                caps.merge(Integer.toString(effectiveCaps[i]), 1, Integer::sum);
                if (focused[i]) focusedFrames++;
                if (iconified[i]) iconifiedFrames++;
            }
            return Map.of("samples", samples, "throttleReasons", reasons, "effectiveCapsFps", caps,
                    "focusedFrames", focusedFrames, "unfocusedFrames", samples - focusedFrames, "iconifiedFrames", iconifiedFrames);
        }
        Map<String, Object> summary() {
            return Map.of("frames", size, "seconds", elapsedNs / 1e9, "fps", size * 1e9 / elapsedNs,
                    "frameIntervalMs", stats(intervals, size), "frameWallMs", stats(wall, size), "renderThreadCpuMs", stats(cpu, size), "worldSubmitMs", stats(world, size), "gpuWorldMs", stats(gpu, size), "limiter", limiterSummary());
        }
    }

    static Map<String, Object> stats(double[] values, int size) {
        double[] sorted = Arrays.stream(Arrays.copyOf(values, size)).filter(Double::isFinite).sorted().toArray();
        require(sorted.length > 0, "No valid timing samples");
        int over33 = 0, over50 = 0, over100 = 0;
        double total = 0;
        for (double value : sorted) { total += value; if (value > 33) over33++; if (value > 50) over50++; if (value > 100) over100++; }
        return Map.of("samples", sorted.length, "mean", total / sorted.length, "p50", percentile(sorted, .50), "p95", percentile(sorted, .95), "p99", percentile(sorted, .99), "max", sorted[sorted.length - 1], "over33ms", over33, "over50ms", over50, "over100ms", over100);
    }
    private static double percentile(double[] sorted, double quantile) { return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)]; }
}
