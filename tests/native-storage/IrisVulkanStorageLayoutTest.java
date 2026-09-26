import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.vulkan.IrisVulkanStorageResources;

import static org.lwjgl.vulkan.VK12.*;

/** Exact ULTRA allocation contracts; intentionally requires neither a Vulkan device nor a game. */
public final class IrisVulkanStorageLayoutTest {
    public static void main(String[] args) {
        long total = 0;
        total += image("voxel_img", "voxel_sampler", InternalTextureFormat.R16UI, 512, 256, 512,
            VK_FORMAT_R16_UINT, 134217728L, true);
        total += image("floodfill_img", "floodfill_sampler", InternalTextureFormat.RGBA16F, 512, 256, 512,
            VK_FORMAT_R16G16B16A16_SFLOAT, 536870912L, false);
        total += image("floodfill_img_copy", "floodfill_sampler_copy", InternalTextureFormat.RGBA16F, 512, 256, 512,
            VK_FORMAT_R16G16B16A16_SFLOAT, 536870912L, false);
        total += image("wsr_img", "wsr_sampler", InternalTextureFormat.R16UI, 512, 64, 512,
            VK_FORMAT_R16_UINT, 33554432L, true);
        total += image("wsr_lod_img", "wsr_lod_sampler", InternalTextureFormat.R8UI, 128, 16, 128,
            VK_FORMAT_R8_UINT, 262144L, true);
        long storage = IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(810549248L, false, 0, 0, null), 1920, 1080);
        check(storage == 810549248L, "ULTRA SSBO must retain its full byte range");
        check(total + storage == 2052325376L, "ULTRA full storage footprint");

        var relative = new ImageInformation("relative", "sample", TextureType.TEXTURE_3D,
            InternalTextureFormat.RGBA8.getPixelFormat(), InternalTextureFormat.RGBA8, PixelType.UNSIGNED_BYTE,
            0, 0, 0, true, true, 0.5f, 0.25f);
        var scaled = IrisVulkanStorageResources.describe(relative, 1919, 1079);
        check(scaled.width() == 959 && scaled.height() == 269 && scaled.depth() == 1, "relative dimensions truncate like Iris");
        check(scaled.imageType() == VK_IMAGE_TYPE_2D && scaled.viewType() == VK_IMAGE_VIEW_TYPE_2D, "relative image is 2D");
        check(IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(16, true, 0.5f, 0.25f, null), 1919, 1079)
            == 4127536L, "relative SSBO uses scaled pixel count and bytes per pixel");
        check(IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(4294967296L, false, 0, 0, null), 1, 1)
            == 4294967296L, "sizes use long before device limit validation");
        expectFailure(() -> IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(0, false, 0, 0, null), 1, 1));
        expectFailure(() -> IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(3, false, 0, 0, new byte[4]), 1, 1));
        expectFailure(() -> IrisVulkanStorageResources.bufferSize(new BuiltShaderStorageInfo(Long.MAX_VALUE / 2, true, 1, 1, null), 4, 4));
        expectFailure(() -> IrisVulkanStorageResources.describe(relative, 1, 1));
        expectFailure(() -> IrisVulkanStorageResources.glslFormat(InternalTextureFormat.RGB8));
        check("rgba8".equals(IrisVulkanStorageResources.glslFormat(InternalTextureFormat.RGBA)), "default RGBA layout is explicit rgba8");
        System.out.println("Native Vulkan storage layout checks passed: five 3D images + full 810549248-byte SSBO = 2052325376 bytes.");
    }

    private static long image(String name, String sampler, InternalTextureFormat format, int width, int height, int depth,
                              int vkFormat, long bytes, boolean integer) {
        var information = new ImageInformation(name, sampler, TextureType.TEXTURE_3D, format.getPixelFormat(), format,
            integer ? (format == InternalTextureFormat.R8UI ? PixelType.UNSIGNED_BYTE : PixelType.UNSIGNED_SHORT)
                : PixelType.HALF_FLOAT, width, height, depth, false, false, 0, 0);
        var spec = IrisVulkanStorageResources.describe(information, 1920, 1080);
        check(spec.width() == width && spec.height() == height && spec.depth() == depth, name + " true volume dimensions");
        check(spec.imageType() == VK_IMAGE_TYPE_3D && spec.viewType() == VK_IMAGE_VIEW_TYPE_3D, name + " real 3D image/view types");
        check(spec.vkFormat() == vkFormat && spec.integer() == integer, name + " exact image format and filtering class");
        check(spec.byteSize() == bytes, name + " full volume byte count");
        check(spec.glslFormat().equals(format.name().toLowerCase(java.util.Locale.ROOT)), name + " shader format qualifier");
        return spec.byteSize();
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected invalid storage metadata to fail");
        } catch (IllegalArgumentException | ArithmeticException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
