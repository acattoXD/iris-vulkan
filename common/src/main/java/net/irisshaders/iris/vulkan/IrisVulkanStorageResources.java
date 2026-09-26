package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanStorageEncoderAccess;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageFormatProperties;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Writable pack images and SSBOs shared by world, shadow and compute shaders.
 *
 * Mojang's texture API always creates a 2D image, and its buffer usage API has no
 * storage bit. These allocations therefore use the engine's VMA allocator directly.
 * Commands and deferred destruction still use the engine encoder, so the resources
 * obey the same submission lifetime as ordinary Minecraft textures and buffers.
 * All image descriptors use GENERAL; a sampled 3D view references the same image
 * that imageStore/imageLoad access. Calls that record commands must be outside a pass.
 */
public final class IrisVulkanStorageResources {
    private static final int IMAGE_USAGE = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    private static VulkanDevice loadedDevice;
    private static ShaderPack loadedPack;
    private static Object loadedDimension;
    private static int loadedWidth;
    private static int loadedHeight;
    private static final Map<String, Image> images = new LinkedHashMap<>();
    private static final Map<String, Image> sampledImages = new LinkedHashMap<>();
    private static final Map<Integer, Buffer> buffers = new LinkedHashMap<>();

    private IrisVulkanStorageResources() { }

    /** Allocate once per pack; reinitialize persistent world data on dimension changes. */
    public static void ensureLoaded(VulkanDevice device, ShaderPack pack, int width, int height, Object dimensionKey) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(pack, "pack");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid storage viewport " + width + "x" + height);
        if (!pack.getIrisCustomImages().isEmpty() || !pack.getBufferObjects().isEmpty()) {
            IrisVulkanDeviceFeatures.enabled(device).requireStorage(!pack.getBufferObjects().isEmpty());
        }
        if (device != loadedDevice || pack != loadedPack) {
            close();
            loadedDevice = device;
            loadedPack = pack;
            loadedDimension = dimensionKey;
            loadedWidth = width;
            loadedHeight = height;
            try {
                for (ImageInformation information : pack.getIrisCustomImages()) {
                    if (images.containsKey(information.name())) {
                        throw new IllegalArgumentException("Duplicate pack storage image " + information.name());
                    }
                    if (hasSampler(information) && sampledImages.containsKey(information.samplerName())) {
                        throw new IllegalArgumentException("Duplicate pack storage sampler " + information.samplerName());
                    }
                    Image image = new Image(device, information, describe(information, width, height));
                    images.put(information.name(), image);
                    if (hasSampler(information)) sampledImages.put(information.samplerName(), image);
                }
                pack.getBufferObjects().forEach((index, information) -> {
                    if (index < 0) throw new IllegalArgumentException("Negative pack SSBO binding " + index);
                    buffers.put(index, new Buffer(device, index, information, bufferSize(information, width, height)));
                });
                initialize(new ArrayList<>(images.values()), new ArrayList<>(buffers.values()));
                Iris.logger.info("Allocated native Vulkan storage: {} writable image(s), {} SSBO(s), {} MiB requested.",
                    images.size(), buffers.size(), requestedBytes() / (1024L * 1024L));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
            return;
        }

        boolean dimensionChanged = !Objects.equals(loadedDimension, dimensionKey);
        if (width != loadedWidth || height != loadedHeight) resizeRelative(width, height);
        if (dimensionChanged) {
            initialize(new ArrayList<>(images.values()), new ArrayList<>(buffers.values()));
            loadedDimension = dimensionKey;
        }
        loadedWidth = width;
        loadedHeight = height;
    }

    /** Clear only images with image.* clear=true; flood-fill history and SSBOs persist. */
    public static void beginFrame() {
        List<Image> clear = images.values().stream().filter(image -> image.information.clear()).toList();
        if (!clear.isEmpty()) initialize(clear, List.of());
    }

    public static Image image(String name) { return images.get(name); }
    public static Image sampledImage(String samplerName) { return sampledImages.get(samplerName); }
    public static Buffer buffer(int binding) { return buffers.get(binding); }
    public static boolean active() { return !images.isEmpty() || !buffers.isEmpty(); }

    /** Makes image/SSBO writes visible to later compute, graphics, transfer and indirect reads. */
    public static void barrier(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, memory, null, null);
        }
    }

    public static void barrier() {
        if (active()) barrier(commandBuffer());
    }

    /** Retire allocations through the engine's in-flight submission destruction queue. */
    public static void close() {
        images.values().forEach(Image::close);
        buffers.values().forEach(Buffer::close);
        images.clear();
        sampledImages.clear();
        buffers.clear();
        loadedPack = null;
        loadedDevice = null;
        loadedDimension = null;
        loadedWidth = 0;
        loadedHeight = 0;
    }

    private static boolean hasSampler(ImageInformation information) {
        return information.samplerName() != null && !information.samplerName().isBlank();
    }

    private static VkCommandBuffer commandBuffer() {
        if (loadedDevice == null) throw new IllegalStateException("Pack storage resources are not loaded");
        return ((VKOnly_VulkanStorageEncoderAccess) loadedDevice.createCommandEncoder()).iris$storageCommandBuffer();
    }

    private static void resizeRelative(int width, int height) {
        List<Image> replacedImages = new ArrayList<>();
        List<Buffer> replacedBuffers = new ArrayList<>();
        try {
            for (Image image : images.values()) {
                if (image.information.isRelative()) {
                    ImageSpec spec = describe(image.information, width, height);
                    if (!spec.equals(image.spec)) replacedImages.add(new Image(loadedDevice, image.information, spec));
                }
            }
            for (Buffer buffer : buffers.values()) {
                if (buffer.information.relative()) {
                    long size = bufferSize(buffer.information, width, height);
                    if (size != buffer.size) replacedBuffers.add(new Buffer(loadedDevice, buffer.binding, buffer.information, size));
                }
            }
        } catch (RuntimeException | Error failure) {
            replacedImages.forEach(Image::close);
            replacedBuffers.forEach(Buffer::close);
            throw failure;
        }
        try {
            initialize(replacedImages, replacedBuffers);
        } catch (RuntimeException | Error failure) {
            replacedImages.forEach(Image::close);
            replacedBuffers.forEach(Buffer::close);
            throw failure;
        }
        for (Image image : replacedImages) {
            images.put(image.information.name(), image).close();
            if (hasSampler(image.information)) sampledImages.put(image.information.samplerName(), image);
        }
        for (Buffer buffer : replacedBuffers) buffers.put(buffer.binding, buffer).close();
    }

    private static void initialize(List<Image> initializeImages, List<Buffer> initializeBuffers) {
        if (initializeImages.isEmpty() && initializeBuffers.isEmpty()) return;
        VkCommandBuffer command = commandBuffer();
        // Includes previous-frame reads, so clearing persistent allocations cannot race them.
        barrier(command);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue zero = VkClearColorValue.calloc(stack);
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            for (Image image : initializeImages) {
                if (!image.initialized) {
                    VkImageMemoryBarrier.Buffer transition = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(0).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.vkImage).subresourceRange(range.get(0));
                    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, null, null, transition);
                    image.initialized = true;
                }
                // All-zero bits are valid for float, signed and unsigned integer clear values.
                vkCmdClearColorImage(command, image.vkImage, VK_IMAGE_LAYOUT_GENERAL, zero, range);
            }
            for (Buffer buffer : initializeBuffers) {
                vkCmdFillBuffer(command, buffer.vkBuffer, 0, buffer.allocationSize, 0);
                byte[] content = buffer.information.content();
                if (content != null && content.length != 0) {
                    // Fill and upload both write the same range and need an execution dependency.
                    barrier(command);
                    for (int offset = 0; offset < content.length;) {
                        // Leave room for the enclosing allocations on LWJGL's default 64 KiB stack.
                        int count = Math.min(32768, content.length - offset);
                        try (MemoryStack upload = MemoryStack.stackPush()) {
                            var bytes = upload.calloc((count + 3) & ~3);
                            bytes.put(content, offset, count).position(0);
                            vkCmdUpdateBuffer(command, buffer.vkBuffer, offset, bytes);
                        }
                        offset += count;
                    }
                }
            }
        }
        barrier(command);
    }

    private static long requestedBytes() {
        long bytes = 0;
        for (Image image : images.values()) bytes = Math.addExact(bytes, image.spec.byteSize());
        for (Buffer buffer : buffers.values()) bytes = Math.addExact(bytes, buffer.size);
        return bytes;
    }

    /** Pure sizing helpers also allow capability checks without allocating GPU memory. */
    public static ImageSpec describe(ImageInformation information, int width, int height) {
        TextureType target = information.isRelative() ? TextureType.TEXTURE_2D : information.target();
        int x = information.isRelative() ? scaledDimension(width, information.relativeWidth()) : information.width();
        int y = target == TextureType.TEXTURE_1D ? 1
            : information.isRelative() ? scaledDimension(height, information.relativeHeight()) : information.height();
        int z = target == TextureType.TEXTURE_3D ? information.depth() : 1;
        if (x <= 0 || y <= 0 || z <= 0) {
            throw new IllegalArgumentException("Invalid pack storage image extent for " + information.name() + ": " + x + "x" + y + "x" + z);
        }
        int imageType = switch (target) {
            case TEXTURE_1D -> VK_IMAGE_TYPE_1D;
            case TEXTURE_2D, TEXTURE_RECTANGLE -> VK_IMAGE_TYPE_2D;
            case TEXTURE_3D -> VK_IMAGE_TYPE_3D;
        };
        int viewType = switch (target) {
            case TEXTURE_1D -> VK_IMAGE_VIEW_TYPE_1D;
            case TEXTURE_2D, TEXTURE_RECTANGLE -> VK_IMAGE_VIEW_TYPE_2D;
            case TEXTURE_3D -> VK_IMAGE_VIEW_TYPE_3D;
        };
        InternalTextureFormat format = information.internalTextureFormat();
        return new ImageSpec(x, y, z, imageType, viewType, vkFormat(format), bytesPerTexel(format),
            format.getPixelFormat().isInteger(), glslFormat(format));
    }

    private static int scaledDimension(int dimension, float scale) {
        double result = (double) dimension * scale;
        if (!Float.isFinite(scale) || result < 1 || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid relative storage extent " + dimension + " * " + scale);
        }
        // Preserve the pack's existing float multiplication and truncation convention.
        return (int) (dimension * scale);
    }

    public static long bufferSize(BuiltShaderStorageInfo information, int width, int height) {
        long size = information.size();
        if (information.relative()) {
            long x = scaledDimension(width, information.scaleX());
            long y = scaledDimension(height, information.scaleY());
            size = Math.multiplyExact(Math.multiplyExact(x, y), size);
        }
        if (size <= 0 || size > Long.MAX_VALUE - 3) throw new IllegalArgumentException("Invalid pack SSBO size " + size);
        if (information.content() != null && information.content().length > size) {
            throw new IllegalArgumentException("Pack SSBO content exceeds its declared size");
        }
        return size;
    }

    public record ImageSpec(int width, int height, int depth, int imageType, int viewType, int vkFormat,
                            int bytesPerTexel, boolean integer, String glslFormat) {
        public long byteSize() {
            return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact((long) width, height), depth), bytesPerTexel);
        }
    }

    public static String glslFormat(InternalTextureFormat format) {
        return switch (format) {
            case RGBA -> "rgba8";
            case RGB10_A2 -> "rgb10_a2";
            case RGB10_A2UI -> "rgb10_a2ui";
            case R11F_G11F_B10F -> "r11f_g11f_b10f";
            default -> {
                // GLSL storage images permit one, two and four components, never RGB.
                if (format.name().startsWith("RGB") && !format.name().startsWith("RGBA")) {
                    throw new IllegalArgumentException("No GLSL storage image format for " + format);
                }
                vkFormat(format);
                yield format.name().toLowerCase(Locale.ROOT);
            }
        };
    }

    private static int bytesPerTexel(InternalTextureFormat format) {
        if (format == InternalTextureFormat.RGBA) return 4;
        String name = format.name();
        if (name.equals("RGB10_A2") || name.equals("RGB10_A2UI") || name.equals("R11F_G11F_B10F")) return 4;
        int components = name.startsWith("RGBA") ? 4 : name.startsWith("RG") ? 2 : 1;
        int bits = name.contains("32") ? 32 : name.contains("16") ? 16 : 8;
        return components * bits / 8;
    }

    public static int vkFormat(InternalTextureFormat format) {
        return switch (format) {
            case RGBA, RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case R8 -> VK_FORMAT_R8_UNORM;
            case RG8 -> VK_FORMAT_R8G8_UNORM;
            case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case RG8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R16 -> VK_FORMAT_R16_UNORM;
            case RG16 -> VK_FORMAT_R16G16_UNORM;
            case RGBA16 -> VK_FORMAT_R16G16B16A16_UNORM;
            case R16_SNORM -> VK_FORMAT_R16_SNORM;
            case RG16_SNORM -> VK_FORMAT_R16G16_SNORM;
            case RGBA16_SNORM -> VK_FORMAT_R16G16B16A16_SNORM;
            case R16F -> VK_FORMAT_R16_SFLOAT;
            case RG16F -> VK_FORMAT_R16G16_SFLOAT;
            case RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R32F -> VK_FORMAT_R32_SFLOAT;
            case RG32F -> VK_FORMAT_R32G32_SFLOAT;
            case RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case R8I -> VK_FORMAT_R8_SINT;
            case RG8I -> VK_FORMAT_R8G8_SINT;
            case RGBA8I -> VK_FORMAT_R8G8B8A8_SINT;
            case R8UI -> VK_FORMAT_R8_UINT;
            case RG8UI -> VK_FORMAT_R8G8_UINT;
            case RGBA8UI -> VK_FORMAT_R8G8B8A8_UINT;
            case R16I -> VK_FORMAT_R16_SINT;
            case RG16I -> VK_FORMAT_R16G16_SINT;
            case RGBA16I -> VK_FORMAT_R16G16B16A16_SINT;
            case R16UI -> VK_FORMAT_R16_UINT;
            case RG16UI -> VK_FORMAT_R16G16_UINT;
            case RGBA16UI -> VK_FORMAT_R16G16B16A16_UINT;
            case R32I -> VK_FORMAT_R32_SINT;
            case RG32I -> VK_FORMAT_R32G32_SINT;
            case RGBA32I -> VK_FORMAT_R32G32B32A32_SINT;
            case R32UI -> VK_FORMAT_R32_UINT;
            case RG32UI -> VK_FORMAT_R32G32_UINT;
            case RGBA32UI -> VK_FORMAT_R32G32B32A32_UINT;
            case RGB10_A2 -> VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RGB10_A2UI -> VK_FORMAT_A2B10G10R10_UINT_PACK32;
            case R11F_G11F_B10F -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            default -> throw new IllegalArgumentException("Unsupported Vulkan storage image format " + format);
        };
    }

    public static final class Image implements AutoCloseable, Destroyable {
        private final VulkanDevice device;
        private final ImageInformation information;
        private final ImageSpec spec;
        private long vkImage;
        private long allocation;
        private long vkImageView;
        private long vkSampler;
        private boolean initialized;
        private boolean closed;

        private Image(VulkanDevice device, ImageInformation information, ImageSpec spec) {
            this.device = device;
            this.information = information;
            this.spec = spec;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                validateImage(device, information, spec, stack);
                VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(spec.imageType()).format(spec.vkFormat()).mipLevels(1).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(IMAGE_USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                create.extent().set(spec.width(), spec.height(), spec.depth());
                VmaAllocationCreateInfo memory = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                var imageOut = stack.mallocLong(1);
                var allocationOut = stack.mallocPointer(1);
                check(vmaCreateImage(device.vma(), create, memory, imageOut, allocationOut, null), "image " + information.name());
                vkImage = imageOut.get(0);
                allocation = allocationOut.get(0);
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(vkImage).viewType(spec.viewType()).format(spec.vkFormat());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
                var viewOut = stack.mallocLong(1);
                check(vkCreateImageView(device.vkDevice(), view, null, viewOut), "storage view " + information.name());
                vkImageView = viewOut.get(0);
                int filter = spec.integer() ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
                VkSamplerCreateInfo sampler = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0).maxLod(0).maxAnisotropy(1);
                var samplerOut = stack.mallocLong(1);
                check(vkCreateSampler(device.vkDevice(), sampler, null, samplerOut), "storage sampler " + information.name());
                vkSampler = samplerOut.get(0);
                device.instance().debug().setObjectName(device.vkDevice(), VK_OBJECT_TYPE_IMAGE, vkImage, "Iris storage " + information.name());
                device.instance().debug().setObjectName(device.vkDevice(), VK_OBJECT_TYPE_IMAGE_VIEW, vkImageView, "Iris storage view " + information.name());
            } catch (RuntimeException | Error failure) {
                // Construction has recorded no commands, so partial allocations can be freed immediately.
                destroy();
                throw failure;
            }
        }

        public long vkImage() { return vkImage; }
        public long vkImageView() { return vkImageView; }
        public long vkSampler() { return vkSampler; }
        public int vkFormat() { return spec.vkFormat(); }
        public int imageLayout() { return VK_IMAGE_LAYOUT_GENERAL; }
        public int width() { return spec.width(); }
        public int height() { return spec.height(); }
        public int depth() { return spec.depth(); }
        public String glslFormat() { return spec.glslFormat(); }
        public boolean isClosed() { return closed; }

        @Override public void close() {
            if (!closed) {
                closed = true;
                device.createCommandEncoder().queueForDestroy(this);
            }
        }

        @Override public void destroy() {
            if (vkSampler != 0) vkDestroySampler(device.vkDevice(), vkSampler, null);
            if (vkImageView != 0) vkDestroyImageView(device.vkDevice(), vkImageView, null);
            if (vkImage != 0) vmaDestroyImage(device.vma(), vkImage, allocation);
            vkSampler = vkImageView = vkImage = allocation = 0;
        }
    }

    public static final class Buffer implements AutoCloseable, Destroyable {
        private final VulkanDevice device;
        private final int binding;
        private final BuiltShaderStorageInfo information;
        private final long size;
        private final long allocationSize;
        private long vkBuffer;
        private long allocation;
        private boolean closed;

        private Buffer(VulkanDevice device, int binding, BuiltShaderStorageInfo information, long size) {
            this.device = device;
            this.binding = binding;
            this.information = information;
            this.size = size;
            this.allocationSize = (size + 3) & ~3L;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), properties);
                long maxRange = Integer.toUnsignedLong(properties.limits().maxStorageBufferRange());
                if (size > maxRange) {
                    throw new IllegalStateException("Pack SSBO " + binding + " requests " + size
                        + " bytes, but Vulkan maxStorageBufferRange is " + maxRange);
                }
                VkBufferCreateInfo create = VkBufferCreateInfo.calloc(stack).sType$Default().size(allocationSize)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                        | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo memory = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                var bufferOut = stack.mallocLong(1);
                var allocationOut = stack.mallocPointer(1);
                check(vmaCreateBuffer(device.vma(), create, memory, bufferOut, allocationOut, null), "SSBO " + binding + " (" + size + " bytes)");
                vkBuffer = bufferOut.get(0);
                allocation = allocationOut.get(0);
                device.instance().debug().setObjectName(device.vkDevice(), VK_OBJECT_TYPE_BUFFER, vkBuffer, "Iris SSBO " + binding);
            } catch (RuntimeException | Error failure) {
                destroy();
                throw failure;
            }
        }

        public int binding() { return binding; }
        public long vkBuffer() { return vkBuffer; }
        public long size() { return size; }
        public boolean isClosed() { return closed; }

        @Override public void close() {
            if (!closed) {
                closed = true;
                device.createCommandEncoder().queueForDestroy(this);
            }
        }

        @Override public void destroy() {
            if (vkBuffer != 0) vmaDestroyBuffer(device.vma(), vkBuffer, allocation);
            vkBuffer = allocation = 0;
        }
    }

    private static void validateImage(VulkanDevice device, ImageInformation information, ImageSpec spec, MemoryStack stack) {
        VkImageFormatProperties extent = VkImageFormatProperties.calloc(stack);
        check(vkGetPhysicalDeviceImageFormatProperties(device.vkDevice().getPhysicalDevice(), spec.vkFormat(),
            spec.imageType(), VK_IMAGE_TILING_OPTIMAL, IMAGE_USAGE, 0, extent), "storage image format " + information.internalTextureFormat());
        if (spec.width() > extent.maxExtent().width() || spec.height() > extent.maxExtent().height()
            || spec.depth() > extent.maxExtent().depth() || spec.byteSize() > extent.maxResourceSize()) {
            throw new IllegalStateException("Pack storage image " + information.name() + " (" + spec.width() + "x"
                + spec.height() + "x" + spec.depth() + ") exceeds Vulkan image limits for " + information.internalTextureFormat());
        }
        VkFormatProperties format = VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(device.vkDevice().getPhysicalDevice(), spec.vkFormat(), format);
        int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
        if (!spec.integer()) required |= VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
        if ((format.optimalTilingFeatures() & required) != required) {
            throw new IllegalStateException("Vulkan format " + information.internalTextureFormat()
                + " lacks required storage/sampling/filter support for " + information.name());
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Cannot allocate native Vulkan " + operation
            + ": " + VulkanUtils.resultToString(result));
    }
}
