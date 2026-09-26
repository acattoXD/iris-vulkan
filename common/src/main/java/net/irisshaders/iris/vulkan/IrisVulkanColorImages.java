package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import static org.lwjgl.vulkan.VK10.*;

/** Writable aliases of the currently selected gbuffer side. Owns no GPU images. */
public final class IrisVulkanColorImages {
	private static final Pattern DECLARATION = Pattern.compile("\\buniform\\s+(?:(?:readonly|writeonly|coherent|volatile|restrict|lowp|mediump|highp)\\s+)*[iu]?image\\w+\\s+([^;{}]+);");
	private static final Pattern DECLARATOR = Pattern.compile("\\s*(colorimg[0-9]+)\\b.*", Pattern.DOTALL);
    private static final ThreadLocal<Boolean> STORAGE_ALLOCATION = ThreadLocal.withInitial(() -> false);
    private IrisVulkanColorImages() { }

    public static int target(String name) {
        if (name == null || !name.startsWith("colorimg")) return -1;
        try {
            int index = Integer.parseInt(name.substring(8));
            return index >= 0 && index < IrisVulkanTargetIndices.LOGICAL_TARGET_COUNT && name.equals("colorimg" + index) ? index : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    static Set<Integer> sourceTargets(String source) {
        if (source == null) return Set.of();
        String code = IrisVulkanShaderPruning.maskComments(IrisVulkanShaderPruning.removeUnusedUniforms(source));
        Set<Integer> result = new LinkedHashSet<>();
        var declarations = DECLARATION.matcher(code);
        while (declarations.find()) for (String item : declarations.group(1).split(",")) {
            var name = DECLARATOR.matcher(item);
            if (name.matches()) { int index = target(name.group(1)); if (index >= 0) result.add(index); }
        }
        return Set.copyOf(result);
    }

    public static Set<Integer> computeTargets(ProgramSet programs) {
        Set<Integer> result = new LinkedHashSet<>();
        collect(result, programs.getSetup()); collect(result, programs.getShadowCompute()); collect(result, programs.getFinalCompute());
        for (ProgramArrayId stage : ProgramArrayId.values()) for (ComputeSource[] group : programs.getCompute(stage)) collect(result, group);
        // Explicit custom images keep their existing namespace precedence.
        for (ImageInformation custom : programs.getPack().getIrisCustomImages()) result.remove(target(custom.name()));
        return Set.copyOf(result);
    }

    /** Built-in image aliases declared by graphics stages (vertex, fragment, geometry and tessellation). */
    public static Set<Integer> graphicsTargets(ProgramSet programs) {
        Set<Integer> result = new LinkedHashSet<>();
        if (programs == null) return Set.of();
        for (ProgramId id : ProgramId.values()) programs.get(id).ifPresent(source -> collectGraphics(result, source));
        for (ProgramArrayId stage : ProgramArrayId.values())
            for (ProgramSource source : programs.getComposite(stage)) collectGraphics(result, source);
        for (ImageInformation custom : programs.getPack().getIrisCustomImages()) result.remove(target(custom.name()));
        return Set.copyOf(result);
    }

    private static void collect(Set<Integer> indices, ComputeSource[] sources) {
        if (sources == null) return;
        for (ComputeSource source : sources) if (source != null && source.isValid()) source.getSource().ifPresent(code -> indices.addAll(sourceTargets(code)));
    }

    private static void collectGraphics(Set<Integer> indices, ProgramSource source) {
        if (source == null || !source.isValid()) return;
        source.getVertexSource().ifPresent(code -> indices.addAll(sourceTargets(code)));
        source.getFragmentSource().ifPresent(code -> indices.addAll(sourceTargets(code)));
        source.getGeometrySource().ifPresent(code -> indices.addAll(sourceTargets(code)));
        source.getTessControlSource().ifPresent(code -> indices.addAll(sourceTargets(code)));
        source.getTessEvalSource().ifPresent(code -> indices.addAll(sourceTargets(code)));
    }

    public static Map<String, ImageInformation> declarations(ProgramSet programs) {
		return declarations(programs, computeTargets(programs));
	}

	/** Native image metadata for aliases used in graphics stages. */
	public static Map<String, ImageInformation> graphicsDeclarations(ProgramSet programs) {
		return declarations(programs, graphicsTargets(programs));
	}

	private static Map<String, ImageInformation> declarations(ProgramSet programs, Set<Integer> targets) {
        Map<String, ImageInformation> result = new LinkedHashMap<>();
        var settings = programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings();
		for (int index : targets) {
            var target = settings.get(index);
            if (target == null) throw new IllegalArgumentException("Missing render-target settings for colorimg" + index);
            InternalTextureFormat format = target.getInternalFormat();
            result.put("colorimg" + index, new ImageInformation("colorimg" + index, null, TextureType.TEXTURE_2D,
                format.getPixelFormat(), format, PixelType.FLOAT, 1, 1, 1, false, false, 1, 1));
        }
        return Map.copyOf(result);
    }

	/** Returns the borrowed current-side view for a graphics descriptor without requiring shader metadata. */
	static GpuTextureView view(String name) {
		int index = target(name);
		if (index < 0) return null;
		GpuTextureView view = IrisVulkanGbufferTargets.colorImageView(index);
		if (view == null || view.isClosed() || view.texture().isClosed())
			throw new IllegalStateException("No live storage-capable current target for " + name);
		return view;
	}

    static <T> T allocate(boolean storage, Supplier<T> allocation) {
        boolean previous = STORAGE_ALLOCATION.get();
        STORAGE_ALLOCATION.set(storage);
        try { return allocation.get(); }
        finally { if (previous) STORAGE_ALLOCATION.set(true); else STORAGE_ALLOCATION.remove(); }
    }

    /** Called only by the native allocation conversion hook; existing usages are preserved. */
    public static int allocationUsage(int original) {
        return STORAGE_ALLOCATION.get() ? original | VK_IMAGE_USAGE_STORAGE_BIT : original;
    }

    static GpuTextureView view(String name, String imageType, String imageFormat) {
        int index = target(name);
        if (index < 0) return null;
        GpuTextureView view = IrisVulkanGbufferTargets.colorImageView(index);
        if (view == null || view.isClosed() || view.texture().isClosed())
            throw new IllegalStateException("No live storage-capable current target for " + name);
        validateFormat(name, view.texture().getFormat(), imageType, imageFormat);
        return view;
    }

    static void validateFormat(String name, GpuFormat actual, String imageType, String imageFormat) {
        String prefix = actual.name().endsWith("_UINT") ? "u" : actual.name().endsWith("_SINT") ? "i" : "";
        if (!(prefix + "image2D").equals(imageType) || !format(actual).equals(imageFormat))
            throw new IllegalArgumentException("Storage alias " + name + " requests " + imageType + "/" + imageFormat + " but current target is " + actual);
    }

    static String formatFailure(InternalTextureFormat requested) {
        GpuFormat actual = IrisVulkanTargetFormat.resolve(requested, GpuFormat.RGBA8_UNORM, -1);
        String expected = IrisVulkanStorageResources.glslFormat(requested);
        if (!format(actual).equals(expected)) return "Declared " + requested + " is not represented by native allocation " + actual;
        var gpu = RenderSystem.tryGetDevice();
        if (gpu == null) return null; // CPU preflight has no device; allocation still validates when one exists.
        if (!((Object)gpu instanceof GpuDeviceAccessor accessor) || !(accessor.getBackend() instanceof VulkanDevice device))
            return "Render-target storage requires the native Vulkan backend";
        boolean extended = !Set.of("rgba32f", "rgba16f", "r32f", "rgba8", "rgba8_snorm", "rgba32i", "rgba16i", "rgba8i", "r32i",
            "rgba32ui", "rgba16ui", "rgba8ui", "r32ui").contains(expected);
        var missing = IrisVulkanDeviceFeatures.enabled(device).missing(false, false, extended);
        if (!missing.isEmpty()) return "Device did not enable " + String.join(", ", missing);
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), VulkanConst.toVk(actual), properties);
            int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            return (properties.optimalTilingFeatures() & required) == required ? null
                : "Device format " + actual + " cannot combine storage, color attachment and sampled image usage";
        }
    }

    static String format(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> "r8"; case RG8_UNORM -> "rg8"; case RGBA8_UNORM -> "rgba8";
            case R8_SNORM -> "r8_snorm"; case RG8_SNORM -> "rg8_snorm"; case RGBA8_SNORM -> "rgba8_snorm";
            case R16_UNORM -> "r16"; case RG16_UNORM -> "rg16"; case RGBA16_UNORM -> "rgba16";
            case R16_SNORM -> "r16_snorm"; case RG16_SNORM -> "rg16_snorm"; case RGBA16_SNORM -> "rgba16_snorm";
            case R16_FLOAT -> "r16f"; case RG16_FLOAT -> "rg16f"; case RGBA16_FLOAT -> "rgba16f";
            case R32_FLOAT -> "r32f"; case RG32_FLOAT -> "rg32f"; case RGBA32_FLOAT -> "rgba32f";
            case R8_SINT -> "r8i"; case RG8_SINT -> "rg8i"; case RGBA8_SINT -> "rgba8i";
            case R16_SINT -> "r16i"; case RG16_SINT -> "rg16i"; case RGBA16_SINT -> "rgba16i";
            case R32_SINT -> "r32i"; case RG32_SINT -> "rg32i"; case RGBA32_SINT -> "rgba32i";
            case R8_UINT -> "r8ui"; case RG8_UINT -> "rg8ui"; case RGBA8_UINT -> "rgba8ui";
            case R16_UINT -> "r16ui"; case RG16_UINT -> "rg16ui"; case RGBA16_UINT -> "rgba16ui";
            case R32_UINT -> "r32ui"; case RG32_UINT -> "rg32ui"; case RGBA32_UINT -> "rgba32ui";
            case RG11B10_FLOAT -> "r11f_g11f_b10f";
            case RGB10A2_UNORM -> "rgb10_a2"; case RGB10A2_UINT -> "rgb10_a2ui";
            default -> throw new UnsupportedOperationException("Render-target storage image format is not represented: " + format);
        };
    }
}
