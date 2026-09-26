package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class IrisVulkanCustomTextures {
	private static final Set<String> WARNED_UNSUPPORTED = ConcurrentHashMap.newKeySet();
	private static final Set<String> LIVE_ATLAS_PATHS = Set.of("textures/atlas/blocks.png", "textures/atlas/items.png", "textures/atlas/particles.png");
	private static final TextureFilteringData LINEAR_REPEAT = new TextureFilteringData(true, false);
	private static final Pattern SAMPLER_3D = Pattern.compile(
		"(?m)^(\\s*(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+)sampler3D(\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;)");

	private static ShaderPack loadedPack;
	private static EnumMap<TextureStage, Map<String, Binding>> stageTextures = new EnumMap<>(TextureStage.class);
	private static Map<String, Binding> globalTextures = Map.of();
	private static Binding customNoiseTexture;
	private static Binding generatedNoiseTexture;
	private static int generatedNoiseResolution = -1;

	private IrisVulkanCustomTextures() {
	}

	static Binding find(TextureStage stage, String sampler) {
		ensureLoaded();
		CustomTextureData data = loadedPack == null ? null : findData(loadedPack, stage, sampler);
		if (data instanceof CustomTextureData.ResourceData resource) {
			// The atlas, view and sampler can all change during resource reloads.
			return liveResourceBinding(resource, sampler);
		}

		Map<String, Binding> stageBindings = stageTextures.get(stage);
		if (stageBindings != null) {
			Binding binding = stageBindings.get(sampler);

			if (binding != null) {
				return binding;
			}
		}

		return globalTextures.get(sampler);
	}

	static Binding noise(int resolution) {
		ensureLoaded();

		if (customNoiseTexture != null) {
			return customNoiseTexture;
		}

		if (generatedNoiseTexture == null || generatedNoiseTexture.closed() || generatedNoiseResolution != resolution) {
			closeGeneratedNoiseTexture();
			generatedNoiseTexture = createGeneratedNoiseTexture(resolution);
			generatedNoiseResolution = resolution;
		}

		return generatedNoiseTexture;
	}

	static boolean supports(ShaderPack pack, TextureStage stage, String sampler) {
		if (pack == null) {
			return false;
		}

		return supports(findData(pack, stage, sampler));
	}

	static boolean supportsStaticVolume(ShaderPack pack, TextureStage stage, String sampler) {
		return pack != null && findData(pack, stage, sampler) instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw);
	}

	/** Preflight sees authored names; the shader transformer later applies this typed stage alias. */
	static boolean supportsStaticVolume(ShaderPack pack, TextureStage stage, String sampler,
		Map<Tri<String, TextureType, TextureStage>, String> textureMap) {
		String resolved = textureMap.getOrDefault(new Tri<>(sampler, TextureType.TEXTURE_3D, stage), sampler);
		return supportsStaticVolume(pack, stage, resolved);
	}

	/** Registered engine atlases are resources, not PNG files in the pack. */
	static boolean supportsResourceData(CustomTextureData.ResourceData resource) {
		return resource != null && "minecraft".equals(resource.getNamespace()) && resource.getLocation() != null
			&& LIVE_ATLAS_PATHS.contains(resource.getLocation());
	}

	static String unsupportedReason(CustomTextureData data) {
		if (data instanceof CustomTextureData.ResourceData resource) {
			return "Resource texture " + resource.getNamespace() + ":" + resource.getLocation()
				+ " has no native live binding. Supported engine atlas resources are minecraft:textures/atlas/blocks.png, "
				+ "minecraft:textures/atlas/items.png and minecraft:textures/atlas/particles.png.";
		}
		if (data instanceof CustomTextureData.RawData3D raw) {
			try { volumeLayout(raw); }
			catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
		}
		return (data == null ? "Missing texture data" : data.getClass().getSimpleName()) + " has no native binding";
	}

	private static Identifier resourceIdentifier(CustomTextureData.ResourceData resource) {
		return supportsResourceData(resource) ? Identifier.fromNamespaceAndPath(resource.getNamespace(), resource.getLocation()) : null;
	}

	private static Binding liveResourceBinding(CustomTextureData.ResourceData resource, String samplerName) {
		Identifier identifier = resourceIdentifier(resource);
		if (identifier == null) throw new UnsupportedOperationException(unsupportedReason(resource));
		Minecraft client = Minecraft.getInstance();
		if (client == null || RenderSystem.tryGetDevice() == null) {
			throw new IllegalStateException("Live resource texture " + identifier + " for " + samplerName + " is not ready before renderer initialization");
		}
		try {
			var manager = client.getTextureManager();
			if (manager == null) throw new IllegalStateException("The Minecraft texture manager is not initialized");
			var texture = manager.getTexture(identifier);
			if (!(texture instanceof TextureAtlas)) {
				throw new IllegalStateException("The registered resource is not a texture atlas");
			}
			GpuTextureView view = texture.getTextureView();
			GpuSampler sampler = texture.getSampler();
			GpuTexture image = view == null ? null : view.texture();
			if (image == null || image.isClosed() || view.isClosed() || sampler == null
				|| view.getWidth(0) <= 0 || view.getHeight(0) <= 0) {
				throw new IllegalStateException("The atlas image, view or sampler is not ready");
			}
			return new Binding(image, view, sampler, false);
		} catch (IllegalStateException failure) {
			throw new IllegalStateException("Live resource texture " + identifier + " for " + samplerName
				+ " is unavailable; wait for the Minecraft atlas resource reload to complete", failure);
		}
	}

	static String patchShaderSource(ShaderPack pack, TextureStage stage, String source) {
		if (pack == null || source == null || !source.contains("sampler3D")) {
			return source;
		}

		Matcher declarations = SAMPLER_3D.matcher(source);
		Map<String, CustomTextureData.RawData3D> flattened = new HashMap<>();
		while (declarations.find()) {
			String sampler = declarations.group(3);
			CustomTextureData data = findData(pack, stage, sampler);
			if (data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw)) {
				flattened.put(sampler, raw);
			}
		}

		if (flattened.isEmpty()) {
			return source;
		}

		String patched = source;
		for (Map.Entry<String, CustomTextureData.RawData3D> entry : flattened.entrySet()) {
			String sampler = entry.getKey();
			CustomTextureData.RawData3D raw = entry.getValue();
			String function = "iris_vulkan_sample3d_" + sampler;
			patched = Pattern.compile("\\btexture\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,")
				.matcher(patched).replaceAll(Matcher.quoteReplacement(function + "("));
			patched = Pattern.compile("\\btextureSize\\s*\\(\\s*" + Pattern.quote(sampler) + "\\s*,\\s*[^)]+\\)")
				.matcher(patched).replaceAll("ivec3(" + raw.getSizeX() + ", " + raw.getSizeY() + ", " + raw.getSizeZ() + ")");
		}

		Matcher matcher = SAMPLER_3D.matcher(patched);
		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			String sampler = matcher.group(3);
			CustomTextureData.RawData3D raw = flattened.get(sampler);
			if (raw == null) {
				matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
				continue;
			}

			String replacement = matcher.group(1) + "sampler2D" + matcher.group(2)
				+ flattenedSamplerFunction(sampler, raw);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	static void close() {
		for (Map<String, Binding> bindings : stageTextures.values()) {
			bindings.values().forEach(Binding::close);
		}

		globalTextures.values().forEach(Binding::close);
		closeCustomNoiseTexture();
		closeGeneratedNoiseTexture();

		loadedPack = null;
		stageTextures = new EnumMap<>(TextureStage.class);
		globalTextures = Map.of();
	}

	private static void ensureLoaded() {
		Optional<ShaderPack> currentPack = Iris.getCurrentPack();

		if (currentPack.isEmpty()) {
			if (loadedPack != null) {
				close();
			}

			return;
		}

		ShaderPack pack = currentPack.get();
		if (pack == loadedPack) {
			return;
		}

		close();
		loadedPack = pack;
		stageTextures = new EnumMap<>(TextureStage.class);

		pack.getCustomTextureDataMap().forEach((stage, textures) -> {
			Map<String, Binding> bindings = new HashMap<>();

			textures.forEach((sampler, data) -> {
				Binding binding = createTexture("Iris native Vulkan custom texture " + stage.name().toLowerCase() + "/" + sampler,
					data, sampler);

				if (binding != null) {
					bindings.put(sampler, binding);
				}
			});

			if (!bindings.isEmpty()) {
				stageTextures.put(stage, bindings);
			}
		});

		Map<String, Binding> globals = new HashMap<>();
		pack.getIrisCustomTextureDataMap().forEach((sampler, data) -> {
			Binding binding = createTexture("Iris native Vulkan custom texture global/" + sampler, data, sampler);

			if (binding != null) {
				globals.put(sampler, binding);
			}
		});
		globalTextures = Map.copyOf(globals);

		customNoiseTexture = createTexture("Iris native Vulkan custom noisetex", pack.getCustomNoiseTexture(), "noisetex");
		Iris.logger.info("Loaded native Vulkan custom textures: {} stage texture(s), {} global texture(s), customNoise={}.",
			stageTextures.values().stream().mapToInt(Map::size).sum(), globalTextures.size(), customNoiseTexture != null);
	}

	private static Binding createTexture(String label, CustomTextureData data, String sampler) {
		if (data == null) {
			return null;
		}
		// Borrowed engine resources are looked up at bind time and never put into owned caches.
		if (data instanceof CustomTextureData.ResourceData) return null;

		if (data instanceof CustomTextureData.PngData png) {
			try {
				return createPngTexture(label, png);
			} catch (IOException | RuntimeException e) {
				Iris.logger.warn("Failed to load native Vulkan custom texture {}: {}", sampler, e.getMessage());
				return null;
			}
		}

		if (data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw)) {
			try {
				return createFlattenedRaw3DTexture(label, raw);
			} catch (RuntimeException e) {
				Iris.logger.warn("Failed to load native Vulkan flattened 3D custom texture {}: {}", sampler, e.getMessage());
				return null;
			}
		}

		if (WARNED_UNSUPPORTED.add(sampler + ":" + data.getClass().getName())) {
			Iris.logger.warn("Native Vulkan custom texture {} uses unsupported data type {}; using fallback binding if sampled.",
				sampler, data.getClass().getSimpleName());
		}

		return null;
	}

	private static Binding createPngTexture(String label, CustomTextureData.PngData data) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocateDirect(data.getContent().length);
		buffer.put(data.getContent());
		buffer.flip();

		try (NativeImage image = NativeImage.read(buffer)) {
			return createImageTexture(label, image, data.getFilteringData());
		}
	}

	private static Binding createGeneratedNoiseTexture(int resolution) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, resolution, resolution, false);
		Random random = new Random(0);

		for (int x = 0; x < resolution; x++) {
			for (int y = 0; y < resolution; y++) {
				image.setPixel(x, y, random.nextInt() | (255 << 24));
			}
		}

		try (image) {
			Binding binding = createImageTexture("Iris native Vulkan generated noisetex " + resolution, image, LINEAR_REPEAT);
			Iris.logger.info("Created native Vulkan generated noisetex at {}x{}.", resolution, resolution);
			return binding;
		}
	}

	private static Binding createImageTexture(String label, NativeImage image, TextureFilteringData filteringData) {
		GpuTexture texture = RenderSystem.getDevice().createTexture(() -> label,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			formatFor(image), image.getWidth(), image.getHeight(), 1, 1);
		GpuTextureView view = RenderSystem.getDevice().createTextureView(texture);
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);

		AddressMode addressMode = filteringData.shouldClamp() ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
		FilterMode filterMode = filteringData.shouldBlur() ? FilterMode.LINEAR : FilterMode.NEAREST;
		GpuSampler sampler = RenderSystem.getDevice().createSampler(addressMode, addressMode,
			filterMode, filterMode, 1, OptionalDouble.empty());

		return new Binding(texture, view, sampler);
	}

	private static Binding createFlattenedRaw3DTexture(String label, CustomTextureData.RawData3D data) {
		var layout = volumeLayout(data);
		GpuFormat format = switch (layout.encoding()) {
			case RGBA32_FLOAT -> GpuFormat.RGBA32_FLOAT;
			case RGB16_FLOAT -> GpuFormat.RGBA16_FLOAT;
			case R8_UNORM -> GpuFormat.R8_UNORM;
		};
		var device = RenderSystem.getDevice();
		var limits = device.getDeviceInfo().limits();
		int maximum = limits.maxTextureSizeForFormat(format);
		if (layout.width() > maximum || layout.atlasHeight() > maximum
			|| limits.maxMemoryAllocationSize() > 0 && layout.outputBytes() > limits.maxMemoryAllocationSize()) {
			throw new IllegalArgumentException("Static volume slice atlas exceeds native texture/allocation limits: "
				+ layout.width() + "x" + layout.atlasHeight() + " " + format);
		}
		ByteBuffer content = IrisVulkanStaticVolume.upload(layout, data.getContent());
		GpuTexture texture = device.createTexture(() -> label + " (flattened 3D)",
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
			format, layout.width(), layout.atlasHeight(), 1, 1);
		GpuTextureView view = null;
		GpuSampler sampler = null;
		try {
			view = device.createTextureView(texture);
			device.createCommandEncoder().writeToTexture(texture, content, 0, 0, 0, 0, layout.width(), layout.atlasHeight());
			// Logical filtering and all three address modes are implemented by texelFetch
			// in the generated helper. Native 2D filtering would bleed across Z slices.
			sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
				FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
			Iris.logger.info("Loaded native Vulkan flattened 3D texture {}x{}x{} as {}x{} {}.",
				layout.width(), layout.height(), layout.depth(), layout.width(), layout.atlasHeight(), format);
			return new Binding(texture, view, sampler);
		} catch (RuntimeException | Error failure) {
			if (sampler != null) sampler.close();
			if (view != null) view.close();
			texture.close();
			throw failure;
		}
	}

	private static GpuFormat formatFor(NativeImage image) {
		return switch (image.format()) {
			case RGBA -> GpuFormat.RGBA8_UNORM;
			case RGB -> GpuFormat.RGB8_UNORM;
			case LUMINANCE_ALPHA -> GpuFormat.RG8_UNORM;
			case LUMINANCE -> GpuFormat.R8_UNORM;
		};
	}

	private static boolean supports(CustomTextureData data) {
		return data instanceof CustomTextureData.PngData
			|| data instanceof CustomTextureData.ResourceData resource && supportsResourceData(resource)
			|| data instanceof CustomTextureData.RawData3D raw && supportsRaw3D(raw);
	}

	private static boolean supportsRaw3D(CustomTextureData.RawData3D data) {
		try { volumeLayout(data); return true; }
		catch (IllegalArgumentException unsupported) { return false; }
	}

	private static IrisVulkanStaticVolume.Layout volumeLayout(CustomTextureData.RawData3D data) {
		IrisVulkanStaticVolume.Encoding encoding;
		if (data.getInternalFormat() == InternalTextureFormat.RGBA32F
			&& data.getPixelFormat() == PixelFormat.RGBA && data.getPixelType() == PixelType.FLOAT) {
			encoding = IrisVulkanStaticVolume.Encoding.RGBA32_FLOAT;
		} else if (data.getInternalFormat() == InternalTextureFormat.RGB16F
			&& data.getPixelFormat() == PixelFormat.RGB && data.getPixelType() == PixelType.HALF_FLOAT) {
			encoding = IrisVulkanStaticVolume.Encoding.RGB16_FLOAT;
		} else if (data.getInternalFormat() == InternalTextureFormat.R8
			&& data.getPixelFormat() == PixelFormat.RED && data.getPixelType() == PixelType.UNSIGNED_BYTE) {
			encoding = IrisVulkanStaticVolume.Encoding.R8_UNORM;
		} else {
			throw new IllegalArgumentException("Unsupported static 3D texture format " + data.getInternalFormat()
				+ "/" + data.getPixelFormat() + "/" + data.getPixelType());
		}
		return IrisVulkanStaticVolume.layout(data.getSizeX(), data.getSizeY(), data.getSizeZ(), data.getContent().length, encoding);
	}

	private static CustomTextureData findData(ShaderPack pack, TextureStage stage, String sampler) {
		Object2ObjectMap<String, CustomTextureData> stageData = pack.getCustomTextureDataMap().get(stage);
		if (stageData != null && stageData.containsKey(sampler)) {
			return stageData.get(sampler);
		}
		return pack.getIrisCustomTextureDataMap().get(sampler);
	}

	private static String flattenedSamplerFunction(String sampler, CustomTextureData.RawData3D data) {
		return IrisVulkanStaticVolume.samplerFunction(sampler, volumeLayout(data),
			data.getFilteringData().shouldBlur(), data.getFilteringData().shouldClamp());
	}

	private static void closeCustomNoiseTexture() {
		if (customNoiseTexture != null) {
			customNoiseTexture.close();
			customNoiseTexture = null;
		}
	}

	private static void closeGeneratedNoiseTexture() {
		if (generatedNoiseTexture != null) {
			generatedNoiseTexture.close();
			generatedNoiseTexture = null;
		}

		generatedNoiseResolution = -1;
	}

	record Binding(GpuTexture texture, GpuTextureView view, GpuSampler sampler, boolean owned) {
		Binding(GpuTexture texture, GpuTextureView view, GpuSampler sampler) {
			this(texture, view, sampler, true);
		}

		private boolean closed() {
			return texture == null || texture.isClosed()
				|| view == null || view.isClosed();
		}

		void close() {
			if (!owned) return;
			if (view != null && !view.isClosed()) {
				view.close();
			}

			if (texture != null && !texture.isClosed()) {
				texture.close();
			}

			if (sampler != null) {
				sampler.close();
			}
		}
	}
}
