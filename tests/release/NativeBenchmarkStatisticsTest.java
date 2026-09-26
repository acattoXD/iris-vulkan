package net.irisshaders.iris.probe;

import com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason;
import net.minecraft.client.InactivityFpsLimit;

/** CPU-only checks for percentile/tail accounting and delayed GPU sample slots. */
public final class NativeBenchmarkStatisticsTest {
    public static void main(String[] args) {
        var summary = NativeBenchmarkProbe.stats(new double[]{1, 2, 3, 34, 50, 101, Double.NaN}, 7);
        check(((Number) summary.get("samples")).intValue() == 6, "Nonfinite GPU placeholders excluded");
        check(((Number) summary.get("p50")).doubleValue() == 3, "Nearest-rank p50");
        check(((Number) summary.get("p95")).doubleValue() == 101, "Nearest-rank p95");
        check(((Number) summary.get("over33ms")).intValue() == 3, "33ms tail count");
        check(((Number) summary.get("over50ms")).intValue() == 1, "Strict 50ms tail count");
        check(((Number) summary.get("over100ms")).intValue() == 1, "100ms tail count");
        var window = new NativeBenchmarkProbe.Window(4);
        window.add(4, 3, 2, 1);
        window.recordLimiter(260, FramerateThrottleReason.NONE, true, false);
        window.add(8, 7, 6, 5);
        window.recordLimiter(260, FramerateThrottleReason.NONE, false, false);
        check(window.gpuSamples() == 0, "Unresolved timestamps are not zero-duration samples");
        window.gpu[0] = 2.5;
        check(window.gpuSamples() == 1, "Resolved query updates its original frame slot");
        window.gpu[1] = 3.5;
        window.elapsedNs = 1_000_000_000L;
        check(((Number) window.summary().get("fps")).doubleValue() == 2, "Window throughput uses measurement duration");
        check(InactivityFpsLimit.MINIMIZED.getSerializedName().equals("minimized"), "Actual Minecraft enum serialization");
        check(NativeBenchmarkProbe.validLimiter(InactivityFpsLimit.MINIMIZED, FramerateThrottleReason.NONE, 260, false), "Unthrottled limiter accepted");
        check(!NativeBenchmarkProbe.validLimiter(InactivityFpsLimit.AFK, FramerateThrottleReason.NONE, 260, false), "AFK mode rejected even before timeout");
        check(!NativeBenchmarkProbe.validLimiter(InactivityFpsLimit.MINIMIZED, FramerateThrottleReason.SHORT_AFK, 30, false), "30 FPS AFK throttling rejected");
        check(!NativeBenchmarkProbe.validLimiter(InactivityFpsLimit.MINIMIZED, FramerateThrottleReason.WINDOW_ICONIFIED, 10, true), "Minimized window rejected");
        var limiter = window.limiterSummary();
        check(((Number) limiter.get("samples")).intValue() == 2 && ((Number) limiter.get("focusedFrames")).intValue() == 1
                && ((Number) limiter.get("unfocusedFrames")).intValue() == 1, "Every sample records effective limiter and window focus");
        System.out.println("IRIS_BENCHMARK_STATS_PASS: percentiles, delayed GPU slots, actual minimized enum, AFK rejection and per-sample focus/cap evidence");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
