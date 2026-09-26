package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.probe.mixin.ProbeVulkanCommandEncoderAccessor;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import net.irisshaders.iris.vulkan.IrisVulkanComputeExecutor;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.irisshaders.iris.vulkan.IrisVulkanStorageResources;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.VK10.*;

/** Isolated GPU storage evidence. Reads small crops/face records; never changes pack storage. */
public final class NativeUltraReadback {
    public static final Map<String, String> ULTRA_OPTIONS = Map.of(
        "ANISOTROPIC_FILTER", "8", "CLOUD_QUALITY", "3", "COLORED_LIGHTING", "512", "DETAIL_QUALITY", "3",
        "LIGHTSHAFT_QUALI_DEFINE", "3", "SHADOW_QUALITY", "3", "WORLD_SPACE_REFLECTIONS", "1", "shadowDistance", "256.0");
    private static final long EXPECTED_BYTES = 2_052_325_376L;
    private static final int MAX_READBACK_BYTES = 4 * 1024 * 1024;
    private static final List<ImageContract> IMAGES = List.of(
        new ImageContract("voxel_img", "voxel_sampler", "r16ui", 512, 256, 512, 2, true),
        new ImageContract("floodfill_img", "floodfill_sampler", "rgba16f", 512, 256, 512, 8, false),
        new ImageContract("floodfill_img_copy", "floodfill_sampler_copy", "rgba16f", 512, 256, 512, 8, false),
        new ImageContract("wsr_img", "wsr_sampler", "r16ui", 512, 64, 512, 2, true),
        new ImageContract("wsr_lod_img", "wsr_lod_sampler", "r8ui", 128, 16, 128, 1, true));

    private NativeUltraReadback() { }

    public static void requireUltraOptions(ShaderPack pack) {
        var options = pack.getShaderPackOptions().getOptionValues();
        Map<String, String> actual = new TreeMap<>();
        for (String name : ULTRA_OPTIONS.keySet()) {
            if (!options.getOptionSet().getStringOptions().containsKey(name)) throw new IllegalStateException("Ultra probe pack is missing option " + name);
            actual.put(name, options.getStringValueOrDefault(name));
        }
        requireOptions(actual);
        if (!"0".equals(options.getStringValueOrDefault("RAIN_PUDDLES")) || !"-1".equals(options.getStringValueOrDefault("WORLD_SPACE_PLAYER_REF")))
            throw new IllegalStateException("Ultra probe requires the audited default puddle/player reflection settings");
    }

    /** Pure contract check used by the probe test. */
    public static void requireOptions(Map<String, String> selected) {
        if (!selected.equals(ULTRA_OPTIONS)) throw new IllegalStateException("Expected exact saved Ultra options, got " + selected);
    }

    /** Additional emitting blocks are installed only in the isolated Ultra test world. */
    public static List<String> sceneCommands() {
        List<String> commands = new ArrayList<>(List.of("time set 18000", "fill -4 63 -8 6 63 8 white_concrete", "fill -7 64 -8 7 69 -8 white_concrete",
            "setblock -4 65 0 verdant_froglight", "setblock 4 65 0 pearlescent_froglight",
            "setblock 0 64 -4 soul_lantern", "setblock 0 64 2 redstone_torch"));
        if (Boolean.getBoolean("iris.vulkan.probe.ultraMotion")) commands.addAll(List.of(
            "fill -12 64 -8 -8 69 -8 white_concrete",
            "fill -11 64 -7 -11 66 -7 gold_block",
            "fill -9 64 -7 -9 68 -7 red_concrete",
            "fill -6 64 -7 -6 65 -7 blue_concrete"));
        return commands;
    }

    /** Called at runTick RETURN, after all passes. Completion is polled without blocking later frames. */
    public static Capture capture(Path directory, String label, Minecraft client) throws Exception {
        if (!Boolean.getBoolean("iris.benchmark") && (!Boolean.getBoolean("iris.vulkan.probe.ultra") || !Boolean.getBoolean("iris.vulkan.storageDevelopment")))
            throw new IllegalStateException("Ultra readback is restricted to an explicitly enabled isolated Ultra probe");
        if (!(IrisBackend.getBackend(RenderSystem.getDevice()) instanceof VulkanDevice device))
            throw new IllegalStateException("Ultra readback requires a real Vulkan backend");
        if (IrisVulkanShadowRenderer.active()) throw new IllegalStateException("Ultra readback requested during the shadow pass");
        var encoder = device.createCommandEncoder();
        if (((ProbeVulkanCommandEncoderAccessor) encoder).iris$activeRenderPass() != null)
            throw new IllegalStateException("Ultra readback must run outside every native render pass");
        ShaderPack pack = Iris.getCurrentPack().orElseThrow();
        requireUltraOptions(pack);
        long bytes = requireResources(pack);
        var dispatch = IrisVulkanComputeExecutor.lastDispatch();
        requireDispatch(dispatch);
        long currentPipelineDispatches = IrisNativeVulkan.computeDispatchCount();
        if (currentPipelineDispatches <= 0) throw new IllegalStateException("The active Ultra pipeline has not dispatched compute");
        String prefix = label.replaceAll("[^A-Za-z0-9._-]", "_");
        if (prefix.isBlank()) throw new IllegalArgumentException("Empty Ultra evidence label");
        Files.createDirectories(directory);

        List<Crop> crops = new ArrayList<>();
        int readBytes = 0;
        for (ImageContract image : IMAGES) {
            int width = image.name.equals("wsr_lod_img") ? 12 : 48;
            int height = image.name.equals("wsr_lod_img") ? 6 : 24;
            int depth = width;
            Crop crop = new Crop(image.name, image.format, image.width / 2 - width / 2,
                image.height / 2 - height / 2, image.depth / 2 - depth / 2,
                width, height, depth, image.bytesPerTexel, readBytes);
            crops.add(crop);
            readBytes = align16(Math.addExact(readBytes, crop.bytes()));
        }
        var camera = client.gameRenderer.mainCamera().position();
        List<FaceSample> faces = faceSamples((int) Math.floor(camera.x()), (int) Math.floor(camera.y()), (int) Math.floor(camera.z()));
        int faceOffset = readBytes;
        readBytes = Math.addExact(readBytes, Math.multiplyExact(faces.size(), 16));
        if (readBytes > MAX_READBACK_BYTES) throw new IllegalStateException("Ultra staging readback is too large: " + readBytes);
        GpuBuffer staging = device.createBuffer(() -> "Iris probe Ultra cropped storage readback",
            GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, readBytes);
        GpuFence fence = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var command = encoder.allocateAndBeginTransientCommandBuffer();
            IrisVulkanStorageResources.barrier(command);
            for (Crop crop : crops) {
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(crop.bufferOffset);
                copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                copy.imageOffset().set(crop.x, crop.y, crop.z);
                copy.imageExtent().set(crop.width, crop.height, crop.depth);
                vkCmdCopyImageToBuffer(command, IrisVulkanStorageResources.image(crop.name).vkImage(), VK_IMAGE_LAYOUT_GENERAL,
                    ((VulkanGpuBuffer) staging).vkBuffer(), copy);
            }
            VkBufferCopy.Buffer copies = VkBufferCopy.calloc(faces.size(), stack);
            for (int i = 0; i < faces.size(); i++) copies.get(i).srcOffset(faces.get(i).sourceOffset).dstOffset(faceOffset + i * 16L).size(16);
            vkCmdCopyBuffer(command, IrisVulkanStorageResources.buffer(0).vkBuffer(), ((VulkanGpuBuffer) staging).vkBuffer(), copies);
            VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack).sType$Default()
                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, host, null, null);
            int ended = vkEndCommandBuffer(command);
            if (ended != VK_SUCCESS) throw new IllegalStateException("Cannot end Ultra readback commands: " + ended);
            encoder.execute(command);
            fence = encoder.createFence();
            encoder.submit();
            return new Capture(staging, fence, directory.resolve(prefix + "-ultra-storage-report.json"), prefix,
                Iris.getCurrentPackName(), String.valueOf(Iris.getCurrentDimension()), pack.getProfileInfo(), bytes,
                crops, faces, faceOffset, dispatch, currentPipelineDispatches, List.of(camera.x(), camera.y(), camera.z()));
        } catch (Throwable error) {
            if (fence != null) fence.close();
            staging.close();
            throw error;
        }
    }

    private static long requireResources(ShaderPack pack) {
        if (pack.getIrisCustomImages().size() != IMAGES.size() || pack.getBufferObjects().size() != 1)
            throw new IllegalStateException("Ultra requires exactly five custom images and SSBO 0");
        long bytes = 810_549_248L;
        for (ImageContract contract : IMAGES) {
            var declaration = pack.getIrisCustomImages().stream().filter(value -> value.name().equals(contract.name)).findFirst().orElseThrow();
            var image = IrisVulkanStorageResources.image(contract.name);
            if (image == null || image.isClosed() || image.vkImage() == 0 || image.vkImageView() == 0 || image.vkSampler() == 0
                || image.width() != contract.width || image.height() != contract.height || image.depth() != contract.depth
                || !image.glslFormat().equals(contract.format) || declaration.clear() != contract.clear
                || !contract.sampler.equals(declaration.samplerName()) || IrisVulkanStorageResources.sampledImage(contract.sampler) != image)
                throw new IllegalStateException("Actual Ultra image/alias mismatch: " + contract.name);
            bytes += (long) contract.width * contract.height * contract.depth * contract.bytesPerTexel;
        }
        var buffer = IrisVulkanStorageResources.buffer(0);
        if (buffer == null || buffer.isClosed() || buffer.vkBuffer() == 0 || buffer.size() != 810_549_248L)
            throw new IllegalStateException("Actual Ultra SSBO 0 allocation is missing or wrong");
        if (bytes != EXPECTED_BYTES) throw new IllegalStateException("Wrong Ultra allocation total " + bytes);
        return bytes;
    }

    public static void requireDispatch(IrisVulkanComputeExecutor.DispatchRecord record) {
        if (record == null || !record.name().equals("shadowcomp") || record.sequence() <= 0 || record.indirect()
            || record.localX() != 8 || record.localY() != 8 || record.localZ() != 8
            || record.groupsX() != 64 || record.groupsY() != 32 || record.groupsZ() != 64)
            throw new IllegalStateException("No actual dispatch matching the audited Ultra shadowcomp contract: " + record);
    }

    public static long faceIndex(int x, int y, int z, int axis, boolean positive) {
        if (x < 0 || x >= 512 || y < 0 || y >= 64 || z < 0 || z >= 512 || axis < 0 || axis > 2)
            throw new IllegalArgumentException("Invalid Ultra WSR voxel coordinate");
        int side = positive ? 1 : 0;
        return switch (axis) {
            case 0 -> side + x + y * 513L + z * 513L * 64;
            case 1 -> side + y + x * 65L + z * 65L * 512 + 513L * 64 * 512;
            default -> side + z + x * 513L + y * 513L * 512 + 513L * 64 * 512 + 65L * 512 * 512;
        };
    }

    private static List<FaceSample> faceSamples(int cameraX, int cameraY, int cameraZ) {
        List<FaceSample> samples = new ArrayList<>();
        for (int[] block : List.of(new int[]{0, 63, 0}, new int[]{2, 64, -3}, new int[]{-4, 65, 0}, new int[]{4, 65, 0}, new int[]{0, 65, -8})) {
            int x = block[0] - cameraX + 256, y = block[1] - cameraY + 32, z = block[2] - cameraZ + 256;
            for (int axis = 0; axis < 3; axis++) for (boolean positive : List.of(false, true))
                samples.add(new FaceSample(List.of(block[0], block[1], block[2]), axis, positive, faceIndex(x, y, z, axis, positive) * 16));
        }
        return samples;
    }

    private static int align16(int size) { return (size + 15) & ~15; }

    public record ImageContract(String name, String sampler, String format, int width, int height, int depth, int bytesPerTexel, boolean clear) { }
    public record Crop(String name, String format, int x, int y, int z, int width, int height, int depth, int bytesPerTexel, int bufferOffset) {
        public int bytes() { return Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(width, height), depth), bytesPerTexel); }
    }
    public record FaceSample(List<Integer> worldBlock, int axis, boolean positive, long sourceOffset) { }
    public record ImageStats(Crop crop, long nonzeroTexels, long coloredTexels, long nonFiniteComponents,
                             List<Float> maxRGB, Map<Integer, Long> voxelIds, String sha256) { }
    public record BufferStats(int binding, long allocationBytes, int sampledBytes, int nonzeroFaceRecords,
                              List<FaceSample> samples, String sha256) { }
    public record Report(String label, String pack, String dimension, String profile, String scope,
                         Map<String, String> selectedOptions, long actualAllocationBytes, int stagingBytes,
                         List<Double> camera, IrisVulkanComputeExecutor.DispatchRecord actualDispatch, long currentPipelineDispatchCount,
                         List<ImageStats> images, BufferStats ssbo, boolean activityPassed) { }

    /** Pixel interpretation is a pure CPU test target; data must originate from GPU copies at runtime. */
    public static ImageStats analyze(Crop crop, ByteBuffer source) throws Exception {
        ByteBuffer data = source.duplicate().order(ByteOrder.nativeOrder());
        long nonzero = 0, colored = 0, nonFinite = 0;
        float[] max = new float[3];
        Map<Integer, Long> ids = new TreeMap<>();
        int texels = crop.width * crop.height * crop.depth;
        for (int i = 0; i < texels; i++) {
            int offset = crop.bufferOffset + i * crop.bytesPerTexel;
            if (crop.format.equals("rgba16f")) {
                float r = Float.float16ToFloat(data.getShort(offset));
                float g = Float.float16ToFloat(data.getShort(offset + 2));
                float b = Float.float16ToFloat(data.getShort(offset + 4));
                float a = Float.float16ToFloat(data.getShort(offset + 6));
                for (float value : new float[]{r, g, b, a}) if (!Float.isFinite(value)) nonFinite++;
                if (r != 0 || g != 0 || b != 0 || a != 0) nonzero++;
                if (Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b)) {
                    max[0] = Math.max(max[0], r); max[1] = Math.max(max[1], g); max[2] = Math.max(max[2], b);
                    if (Math.max(Math.abs(r - g), Math.abs(g - b)) > 1e-5f) colored++;
                }
            } else {
                int value = crop.bytesPerTexel == 1 ? Byte.toUnsignedInt(data.get(offset)) : Short.toUnsignedInt(data.getShort(offset));
                if (value != 0) nonzero++;
                if (crop.name.equals("voxel_img") && value != 0) ids.merge(value & 32767, 1L, Long::sum);
            }
        }
        return new ImageStats(crop, nonzero, colored, nonFinite, List.of(max[0], max[1], max[2]), Map.copyOf(ids), hash(data, crop.bufferOffset, crop.bytes()));
    }

    public static boolean activityPassed(List<ImageStats> images, BufferStats buffer) {
        if (images.size() != 5 || buffer.nonzeroFaceRecords <= 0) return false;
        for (ImageStats stats : images) {
            if (stats.nonzeroTexels <= 0 || stats.nonFiniteComponents != 0) return false;
            if (stats.crop.format.equals("rgba16f") && stats.coloredTexels <= 0) return false;
            if (stats.crop.name.equals("voxel_img") && (!stats.voxelIds.containsKey(8) || !stats.voxelIds.containsKey(9))) return false;
        }
        return true;
    }

    private static String hash(ByteBuffer data, int offset, int length) throws Exception {
        ByteBuffer range = data.duplicate(); range.position(offset).limit(offset + length);
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(range);
        return HexFormat.of().formatHex(digest.digest());
    }

    public static final class Capture implements AutoCloseable {
        private final GpuBuffer staging;
        private final GpuFence fence;
        private final Path output;
        private final String label, pack, dimension, profile;
        private final long allocationBytes;
        private final List<Crop> crops;
        private final List<FaceSample> faces;
        private final int faceOffset;
        private final IrisVulkanComputeExecutor.DispatchRecord dispatch;
        private final long currentPipelineDispatches;
        private final List<Double> camera;
        private boolean closed;

        private Capture(GpuBuffer staging, GpuFence fence, Path output, String label, String pack, String dimension,
                        String profile, long allocationBytes, List<Crop> crops, List<FaceSample> faces, int faceOffset,
                        IrisVulkanComputeExecutor.DispatchRecord dispatch, long currentPipelineDispatches, List<Double> camera) {
            this.staging = staging; this.fence = fence; this.output = output; this.label = label; this.pack = pack;
            this.dimension = dimension; this.profile = profile; this.allocationBytes = allocationBytes;
            this.crops = crops; this.faces = faces; this.faceOffset = faceOffset; this.dispatch = dispatch;
            this.currentPipelineDispatches = currentPipelineDispatches; this.camera = camera;
        }

        /** Returns null while the submitted GPU copies are pending; never blocks the render thread. */
        public Report poll() throws Exception {
            if (closed) throw new IllegalStateException("Ultra capture already closed");
            if (!fence.awaitCompletion(0)) return null;
            try (var mapped = staging.slice().map(true, false)) {
                ByteBuffer data = mapped.data().duplicate().order(ByteOrder.nativeOrder());
                List<ImageStats> images = new ArrayList<>();
                for (Crop crop : crops) images.add(analyze(crop, data));
                int nonzero = 0;
                for (int i = 0; i < faces.size(); i++) {
                    int offset = faceOffset + i * 16;
                    if ((data.getInt(offset) | data.getInt(offset + 4) | data.getInt(offset + 8) | data.getInt(offset + 12)) != 0) nonzero++;
                }
                BufferStats buffer = new BufferStats(0, 810_549_248L, faces.size() * 16, nonzero, faces, hash(data, faceOffset, faces.size() * 16));
                Report report = new Report(label, pack, dimension, profile,
                    "Actual fence-completed GPU copies of five cropped 3D images and 30 SSBO face records; dispatch record follows native encoder.execute. This proves sampled storage activity, not complete visual equivalence.",
                    ULTRA_OPTIONS, allocationBytes, (int) staging.size(), camera, dispatch, currentPipelineDispatches, images, buffer, activityPassed(images, buffer));
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
                return report;
            } finally { close(); }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            fence.close();
            staging.close();
        }
    }
}
