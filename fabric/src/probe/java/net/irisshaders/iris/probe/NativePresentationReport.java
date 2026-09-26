package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Reads the live surface and limiter state; it never changes game or driver settings. */
public final class NativePresentationReport {
	private NativePresentationReport() {
	}

	/** Call on the render thread once the world and window surface are ready. */
	public static Report capture(Minecraft client) {
		GpuSurface surface = client.windowSurface();
		var configuration = surface.currentConfiguration();
		List<GpuSurface.PresentMode> supported = surface.supportedPresentModes().stream().sorted().toList();
		boolean vsync = client.options.enableVsync().get();
		GpuSurface.PresentMode preferred = supported.isEmpty() ? null
			: GpuSurface.PresentMode.getSupportedVsyncMode(supported, vsync);
		GpuSurface.PresentMode actual = configuration.map(GpuSurface.Configuration::presentMode).orElse(null);
		var limiter = client.getFramerateLimitTracker();
		int configuredLimit = client.options.framerateLimit().get();
		int effectiveLimit = limiter.getFramerateLimit();
		// runTick checks this extracted field, which can briefly differ from the tracker after a setting change.
		int frameLimit = client.gameRenderer.gameRenderState().framerateLimit;
		boolean limiterActive = frameLimit < Options.UNLIMITED_FRAMERATE_CUTOFF;
		String reason = limiter.getThrottleReason().name();
		var window = client.getWindow();
		return new Report(Instant.now().toString(), RenderSystem.getDevice().getDeviceInfo().backendName(),
			actual == null ? null : actual.name(), supported.stream().map(Enum::name).toList(),
			preferred == null ? null : preferred.name(), actual != null && actual == preferred,
			configuration.map(GpuSurface.Configuration::width).orElse(null),
			configuration.map(GpuSurface.Configuration::height).orElse(null),
			vsync, configuredLimit, configuredLimit >= Options.UNLIMITED_FRAMERATE_CUTOFF,
			effectiveLimit, frameLimit, limiterActive, reason, client.getFps(),
			window.getRefreshRate(), window.isFullscreen(), window.isFocused(), window.isIconified(),
			client.level != null, diagnosis(actual, preferred, vsync, limiterActive, frameLimit, reason),
			"FPS is Minecraft's latest counter, not a benchmark average. This reports live engine state; driver-level caps and GPU/CPU cost require separate measurement.");
	}

	/** Writes one snapshot to the requested evidence file and returns that same snapshot. */
	public static Report write(Path outputFile, Minecraft client) throws IOException {
		Report report = capture(client);
		Path output = outputFile.toAbsolutePath().normalize();
		Files.createDirectories(output.getParent());
		Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(report));
		Iris.logger.info("Live presentation: backend={}, mode={}, preferred={}, supported={}, VSync={}, maxFps={}, effectiveLimit={}, frameLimit={}, throttle={}, FPS={}, refreshHz={}",
			report.backend(), report.actualPresentMode(), report.preferredPresentMode(), report.supportedPresentModes(),
			report.vsyncEnabled(), report.configuredMaxFps(), report.effectiveFramerateLimit(), report.renderStateFramerateLimit(),
			report.throttleReason(), report.actualFps(), report.monitorRefreshRate());
		return report;
	}

	private static String diagnosis(GpuSurface.PresentMode actual, GpuSurface.PresentMode preferred,
									boolean vsync, boolean engineLimiter, int frameLimit, String throttle) {
		String engine = engineLimiter
			? "Minecraft's CPU frame limiter is active at " + frameLimit + " FPS (throttle reason " + throttle + "). "
			: "Minecraft's CPU frame limiter is bypassed. ";
		if (actual == null) return engine + "The window surface is not configured yet.";
		if (actual != preferred) {
			return engine + "The live surface uses " + actual + " while the current setting prefers " + preferred
				+ "; capture again after surface reconfiguration before drawing a VSync conclusion.";
		}
		return engine + switch (actual) {
			case IMMEDIATE -> "The live Vulkan surface uses IMMEDIATE; it does not request refresh-synchronized presentation.";
			case MAILBOX -> "The live surface uses MAILBOX, which permits rendering faster than display refresh while presenting the newest completed frame.";
			case FIFO, FIFO_RELAXED -> vsync
				? "The live surface uses " + actual + " with VSync enabled."
				: "VSync is disabled, but the surface falls back to FIFO because neither IMMEDIATE nor MAILBOX is supported.";
		};
	}

	public record Report(String capturedAt, String backend, String actualPresentMode,
						 List<String> supportedPresentModes, String preferredPresentMode,
						 boolean currentModeMatchesSettings, Integer surfaceWidth, Integer surfaceHeight,
						 boolean vsyncEnabled, int configuredMaxFps, boolean configuredMaxFpsIsUnlimited,
						 int effectiveFramerateLimit, int renderStateFramerateLimit, boolean engineFrameLimiterActive,
						 String throttleReason, int actualFps, int monitorRefreshRate, boolean fullscreen,
						 boolean focused, boolean iconified, boolean worldActive, String diagnosis,
						 String measurementLimit) {
	}
}
