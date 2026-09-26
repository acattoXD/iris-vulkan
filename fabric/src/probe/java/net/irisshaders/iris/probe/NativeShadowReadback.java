package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/** GPU readback evidence for the isolated probe mod; never included in a published Iris jar. */
public final class NativeShadowReadback {
	private static final float CLEAR_DEPTH = 1.0f;
	private static final float EPSILON = 1.0e-6f;
	private static final long MAX_CAPTURE_TEXELS = 4096L * 4096L;

	private NativeShadowReadback() {
	}

	/** Call on the render thread after shadow rendering; completion follows the GPU copy fences. */
	public static CompletableFuture<Report> capture(Path directory, String label) {
		try {
			if (IrisVulkanShadowRenderer.active()) {
				throw new IllegalStateException("Read shadow depth after its render pass, not during it");
			}
			var translucent = IrisVulkanShadowRenderer.binding("shadowtex0");
			var opaque = IrisVulkanShadowRenderer.binding("shadowtex1");
			if (translucent == null || opaque == null) {
				throw new IllegalStateException("No rendered native shadow textures are available");
			}
			GpuTexture depth0 = translucent.view().texture();
			GpuTexture depth1 = opaque.view().texture();
			int width = depth0.getWidth(0);
			int height = depth0.getHeight(0);
			if (depth0.getFormat() != GpuFormat.D32_FLOAT || depth1.getFormat() != GpuFormat.D32_FLOAT
				|| depth1.getWidth(0) != width || depth1.getHeight(0) != height) {
				throw new IllegalStateException("Shadow readback expects two equally sized D32_FLOAT textures");
			}
			if ((long) width * height > MAX_CAPTURE_TEXELS) {
				throw new IllegalStateException("Probe readback memory limit exceeded: " + width + "x" + height);
			}
			Path output = directory.toAbsolutePath().normalize();
			Files.createDirectories(output);
			String prefix = label.replaceAll("[^A-Za-z0-9._-]", "_");
			if (prefix.isBlank()) throw new IllegalArgumentException("Missing shadow capture label");
			CompletableFuture<float[]> first = read(depth0);
			CompletableFuture<float[]> second = read(depth1);
			return first.thenCombine(second, (values0, values1) -> {
				try {
					Report report = analyze(width, height, values0, values1);
					Files.writeString(output.resolve(prefix + "-shadow-report.json"),
						new GsonBuilder().setPrettyPrinting().create().toJson(report));
					writeDepthImage(output.resolve(prefix + "-shadowtex0-depth.png"), width, height, values0);
					writeDepthImage(output.resolve(prefix + "-shadowtex1-depth.png"), width, height, values1);
					writeDifferenceImage(output.resolve(prefix + "-shadow-translucent-difference.png"), width, height, values0, values1);
					return report;
				} catch (IOException error) {
					throw new java.util.concurrent.CompletionException(error);
				}
			});
		} catch (Throwable failure) {
			return CompletableFuture.failedFuture(failure);
		}
	}

	private static CompletableFuture<float[]> read(GpuTexture texture) {
		int size = Math.multiplyExact(texture.getWidth(0), texture.getHeight(0));
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Iris probe shadow depth readback",
			GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) size * Float.BYTES);
		CompletableFuture<float[]> result = new CompletableFuture<>();
		try {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
				try (var mapped = buffer.slice().map(true, false)) {
					ByteBuffer data = mapped.data().duplicate().order(ByteOrder.nativeOrder());
					float[] values = new float[size];
					for (int i = 0; i < size; i++) values[i] = data.getFloat(i * Float.BYTES);
					result.complete(values);
				} catch (Throwable failure) {
					result.completeExceptionally(failure);
				} finally {
					buffer.close();
				}
			}, 0);
		} catch (Throwable failure) {
			buffer.close();
			result.completeExceptionally(failure);
		}
		return result;
	}

	/** Pure CPU analysis is separately testable; the arrays themselves come from D32 GPU transfers. */
	public static Report analyze(int width, int height, float[] depth0, float[] depth1) {
		int pixels = Math.multiplyExact(width, height);
		if (depth0.length != pixels || depth1.length != pixels || pixels == 0) {
			throw new IllegalArgumentException("Shadow readback dimensions do not match data");
		}
		long differences = 0;
		long inverted = 0;
		float largestDifference = 0.0f;
		for (int i = 0; i < pixels; i++) {
			float a = depth0[i], b = depth1[i];
			if (!Float.isFinite(a) || !Float.isFinite(b)) continue;
			float change = b - a;
			if (change > EPSILON) differences++;
			if (change < -EPSILON) inverted++;
			largestDifference = Math.max(largestDifference, change);
		}
		DepthStats first = stats(depth0);
		DepthStats second = stats(depth1);
		return new Report(width, height, "GPU D32_FLOAT, forward shadow depth (clear=1, near=0)",
			first, second, differences, (double) differences / pixels, inverted, largestDifference,
			first.validNonClearPixels() > 0 && second.validNonClearPixels() > 0,
			first.invalidPixels() == 0 && second.invalidPixels() == 0 && inverted == 0,
			"Coverage and depth ordering are evidence only; inspect scene shadows and material correctness separately.");
	}

	private static DepthStats stats(float[] depth) {
		long nonClear = 0, invalid = 0;
		float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
		double sum = 0.0;
		boolean[] occupiedBins = new boolean[4096];
		MessageDigest digest;
		try { digest = MessageDigest.getInstance("SHA-256"); }
		catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
		for (float value : depth) {
			int bits = Float.floatToRawIntBits(value);
			digest.update((byte) bits);
			digest.update((byte) (bits >>> 8));
			digest.update((byte) (bits >>> 16));
			digest.update((byte) (bits >>> 24));
			if (!Float.isFinite(value) || value < -EPSILON || value > CLEAR_DEPTH + EPSILON) {
				invalid++;
				continue;
			}
			if (value < CLEAR_DEPTH - EPSILON) {
				nonClear++;
				min = Math.min(min, value);
				max = Math.max(max, value);
				sum += value;
				occupiedBins[Math.clamp((int) (value * 4095.0f), 0, 4095)] = true;
			}
		}
		int bins = 0;
		for (boolean occupied : occupiedBins) if (occupied) bins++;
		return new DepthStats(depth.length, nonClear, invalid, (double) nonClear / depth.length,
			nonClear == 0 ? null : min, nonClear == 0 ? null : max, nonClear == 0 ? null : sum / nonClear,
			bins, HexFormat.of().formatHex(digest.digest()));
	}

	private static void writeDepthImage(Path path, int width, int height, float[] depth) throws IOException {
		int imageWidth = Math.min(512, width), imageHeight = Math.min(512, height);
		try (NativeImage image = new NativeImage(imageWidth, imageHeight, false)) {
			for (int y = 0; y < imageHeight; y++) for (int x = 0; x < imageWidth; x++) {
				float value = depth[(y * height / imageHeight) * width + x * width / imageWidth];
				int gray = Math.clamp(Math.round(value * 255), 0, 255);
				image.setPixel(x, y, Float.isFinite(value) ? 0xFF000000 | gray * 0x010101 : 0xFFFF00FF);
			}
			image.writeToFile(path);
		}
	}

	private static void writeDifferenceImage(Path path, int width, int height, float[] depth0, float[] depth1) throws IOException {
		int imageWidth = Math.min(512, width), imageHeight = Math.min(512, height);
		try (NativeImage image = new NativeImage(imageWidth, imageHeight, false)) {
			for (int y = 0; y < imageHeight; y++) for (int x = 0; x < imageWidth; x++) {
				int index = (y * height / imageHeight) * width + x * width / imageWidth;
				float difference = depth1[index] - depth0[index];
				int pixel = !Float.isFinite(difference) ? 0xFFFF00FF
					: difference < -EPSILON ? 0xFFFF0000 : difference > EPSILON ? 0xFF00FF00 : 0xFF000000;
				image.setPixel(x, y, pixel);
			}
			image.writeToFile(path);
		}
	}

	public record DepthStats(long pixels, long validNonClearPixels, long invalidPixels, double nonClearCoverage,
							 Float minimumNonClearDepth, Float maximumNonClearDepth, Double meanNonClearDepth,
							 int occupiedDepthBins, String sha256) {
	}

	public record Report(int width, int height, String source, DepthStats shadowtex0, DepthStats shadowtex1,
						 long translucentDepthPixels, double translucentCoverage, long wrongDepthOrderPixels,
						 float largestTranslucentDepthDifference, boolean bothTexturesContainCasters,
						 boolean depthRangeAndOrderingValid, String verificationLimit) {
	}
}
