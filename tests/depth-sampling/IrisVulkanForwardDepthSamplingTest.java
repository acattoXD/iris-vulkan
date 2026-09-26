package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/** Tiny independent Vulkan compute device; never attaches to a Minecraft process or window. */
public final class IrisVulkanForwardDepthSamplingTest {
    private static final float NEAR = 0.05f, FAR = 192.0f;
    private static final float[] DISTANCES = {0.05f, 0.075f, 0.1f, 0.25f, 0.5f, 1, 2, 3, 4, 8, 16, 48, 96, 191, 192};
    private static final int ROWS = 7;
    private static final String COMPUTE = """
            #version 450 core
            layout(local_size_x=1) in;
            layout(binding=0) uniform sampler2D nativeDepth;
            layout(binding=1) uniform sampler2D forwardDepth;
            layout(std430,binding=2) buffer Results { vec4 values[]; };
            layout(push_constant) uniform Params { mat4 projectionInverse; float nearPlane; float farPlane; uint width; };
            vec4 sampleAlias(sampler2D depthtex, vec2 coord) { return texture(depthtex, coord); }
            float GetLinearDepth(float depth) {
                return (2.0 * nearPlane) / (farPlane + nearPlane - depth * (farPlane - nearPlane));
            }
            // The pack's AO loop for a uniform plane: each symmetric sample pair is identical.
            float flatPlaneAO(float z0, float sampledDepth) {
                if (z0 < 0.56) return 1.0;
                float linearZ0 = GetLinearDepth(z0);
                float sampleLinear = GetLinearDepth(sampledDepth);
                float aosample = (farPlane - nearPlane) * (linearZ0 - sampleLinear) * 2.0;
                float angle = clamp(0.5 - aosample, 0.0, 1.0);
                float dist = clamp(0.5 * aosample - 1.0, 0.0, 1.0);
                return pow(clamp(2.0 * (angle + dist), 0.0, 1.0), 0.4);
            }
            float reflectionViewZ(float depth, vec2 uv) {
                vec4 position = projectionInverse * vec4(vec3(uv, depth) * 2.0 - 1.0, 1.0);
                return position.z / position.w;
            }
            void main() {
                uint i = gl_GlobalInvocationID.x;
                vec2 uv = vec2((float(i) + 0.75) / float(width), 0.5);
                vec4 forwardSample = textureLod(forwardDepth, uv, 0.0);
                vec4 aliasSample = sampleAlias(forwardDepth, uv);
                vec4 raw = textureLod(nativeDepth, uv, 0.0);
                uint base = i * 7;
                values[base] = forwardSample;
                values[base+1] = aliasSample;
                values[base+2] = vec4(GetLinearDepth(aliasSample.r), GetLinearDepth(raw.r),
                    flatPlaneAO(forwardSample.r, aliasSample.r), flatPlaneAO(forwardSample.r, raw.r));
                values[base+3] = vec4(reflectionViewZ(aliasSample.r, uv), reflectionViewZ(raw.r, uv),
                    forwardSample.r, 1.0 - raw.r);
                values[base+4] = vec4(texture(forwardDepth, uv).r,
                    textureGrad(forwardDepth, uv, vec2(0), vec2(0)).r,
                    textureLod(forwardDepth, uv, 0.0).r, texelFetch(forwardDepth, ivec2(i,0), 0).r);
                values[base+5] = textureGather(forwardDepth, uv, 0);
                values[base+6] = raw;
            }
            """;

    public static void main(String[] args) throws Exception {
        int width = DISTANCES.length;
        float[] raw = new float[width], forward = new float[width];
        Matrix4f reverseProjection = new Matrix4f().setPerspective((float) Math.toRadians(70), 16.0f / 9.0f, FAR, NEAR, true);
        Matrix4f inverse = new Matrix4f().setPerspective((float) Math.toRadians(70), 16.0f / 9.0f, NEAR, FAR, false).invert();
        for (int i = 0; i < width; i++) {
            Vector4f clip = reverseProjection.transform(new Vector4f(0, 0, -DISTANCES[i], 1));
            raw[i] = clip.z / clip.w;
            forward[i] = 1.0f - raw[i];
        }
        ByteBuffer spirv = compile(COMPUTE, Shaderc.shaderc_compute_shader);
        // Syntax check for the production R32_FLOAT conversion operation as a fragment shader.
        ByteBuffer fragment = compile("#version 450 core\nlayout(binding=0) uniform sampler2D depth;\nlayout(location=0) out float forward;\nvoid main(){forward=1.0-texelFetch(depth,ivec2(gl_FragCoord.xy),0).r;}", Shaderc.shaderc_fragment_shader);
        MemoryUtil.memFree(fragment);
        List<Object> rows = new ArrayList<>();
        int oldAliasDarkCases = 0;
        String deviceName;
        try (Device gpu = new Device(); MemoryStack stack = MemoryStack.stackPush()) {
            deviceName = gpu.name;
            Buffer upload = gpu.buffer(width * 8L, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            ByteBuffer pixels = gpu.map(upload);
            for (int i = 0; i < width; i++) { pixels.putFloat(i * 4, raw[i]); pixels.putFloat((width + i) * 4, forward[i]); }
            vkUnmapMemory(gpu.device, upload.memory);
            Image nativeImage = gpu.image(width, VK_FORMAT_D32_SFLOAT, VK_IMAGE_ASPECT_DEPTH_BIT);
            Image forwardImage = gpu.image(width, VK_FORMAT_R32_SFLOAT, VK_IMAGE_ASPECT_COLOR_BIT);
            Buffer output = gpu.buffer(width * ROWS * 16L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            gpu.createPipeline(spirv, nativeImage, forwardImage, output);
            gpu.begin();
            gpu.upload(upload, nativeImage, 0);
            gpu.upload(upload, forwardImage, width * 4L);
            vkCmdBindPipeline(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipeline);
            vkCmdBindDescriptorSets(gpu.command, VK_PIPELINE_BIND_POINT_COMPUTE, gpu.pipelineLayout, 0, stack.longs(gpu.descriptorSet), null);
            ByteBuffer constants = stack.calloc(80).order(ByteOrder.nativeOrder());
            inverse.get(0, constants);
            constants.putFloat(64, NEAR).putFloat(68, FAR).putInt(72, width);
            vkCmdPushConstants(gpu.command, gpu.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
            vkCmdDispatch(gpu.command, width, 1, 1);
            VkMemoryBarrier.Buffer host = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(gpu.command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, host, null, null);
            gpu.submitAndWait();
            ByteBuffer values = gpu.map(output);
            for (int i = 0; i < width; i++) {
                int base = i * ROWS * 16;
                for (int sample = 0; sample < 2; sample++) {
                    same(values.getFloat(base + sample * 16), forward[i], "forward red/alias " + i);
                    same(values.getFloat(base + sample * 16 + 4), 0, "R32 green");
                    same(values.getFloat(base + sample * 16 + 8), 0, "R32 blue");
                    same(values.getFloat(base + sample * 16 + 12), 1, "R32 alpha");
                }
                // GPU contraction may evaluate the near/far subtraction with an FMA.
                float expectedLinear = linearDepth(forward[i]);
                float sampledLinear = values.getFloat(base + 2 * 16);
                if (Math.abs(sampledLinear - expectedLinear) > Math.max(1e-7f, Math.abs(expectedLinear) * 1e-4f)) {
                    throw new AssertionError("AO linear depth: " + sampledLinear + " != " + expectedLinear);
                }
                same(values.getFloat(base + 2 * 16 + 8), 1, "correct flat-plane AO must be unoccluded");
                float oldAO = values.getFloat(base + 2 * 16 + 12);
                if (oldAO < 0.9f) oldAliasDarkCases++;
                float uvx = (i + 0.75f) / width;
                Vector4f view = inverse.transform(new Vector4f(uvx * 2 - 1, 0, forward[i] * 2 - 1, 1));
                near(values.getFloat(base + 3 * 16), view.z / view.w, "reflection reconstruction");
                same(values.getFloat(base + 3 * 16 + 8), values.getFloat(base + 3 * 16 + 12), "R32 data equals GPU 1-nativeDepth");
                for (int call = 0; call < 4; call++) same(values.getFloat(base + 4 * 16 + call * 4), forward[i], "texture/Grad/Lod/fetch " + i);
                float next = forward[Math.min(i + 1, width - 1)];
                float[] gather = {forward[i], next, next, forward[i]};
                for (int lane = 0; lane < 4; lane++) same(values.getFloat(base + 5 * 16 + lane * 4), gather[lane], "gather lane " + lane);
                same(values.getFloat(base + 6 * 16), raw[i], "D32 red");
                same(values.getFloat(base + 6 * 16 + 4), 0, "D32 green");
                same(values.getFloat(base + 6 * 16 + 8), 0, "D32 blue");
                same(values.getFloat(base + 6 * 16 + 12), 1, "D32 alpha");
                rows.add(Map.of("distance", DISTANCES[i], "nativeDepth", raw[i], "forwardDepth", forward[i],
                        "forwardAliasAO", values.getFloat(base + 2 * 16 + 8), "unconvertedAliasAO", oldAO,
                        "forwardReconstructedZ", values.getFloat(base + 3 * 16), "unconvertedReconstructedZ", values.getFloat(base + 3 * 16 + 4)));
            }
            vkUnmapMemory(gpu.device, output.memory);
        } finally {
            MemoryUtil.memFree(spirv);
        }
        if (oldAliasDarkCases == 0) throw new AssertionError("The original alias error was not reproduced");
        Path report = Path.of(args.length == 0 ? "build/forward-depth-sampling-report.json" : args[0]).toAbsolutePath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "device", deviceName, "pixelsTested", width, "oldAliasDarkCases", oldAliasDarkCases,
                "checks", List.of("D32/R32 sampled RGBA channels", "passed sampler alias", "texture/textureGrad/textureLod/texelFetch", "textureGather component order", "Complementary flat-plane AO", "Complementary inverse-projection reflection position"),
                "limits", "R32 image is uploaded from exact float 1-nativeDepth data and compared with GPU subtraction; this test does not invoke the production framebuffer copy pass or validate temporal history.", "samples", rows)));
        System.out.println("IRIS_VULKAN_FORWARD_DEPTH_PASS: " + width + " sampled pixels; reproduced " + oldAliasDarkCases + " false-dark AO cases with the old alias path; " + report);
    }

    private static float linearDepth(float d) { return 2 * NEAR / (FAR + NEAR - d * (FAR - NEAR)); }
    private static void same(float actual, float expected, String label) {
        if (Float.floatToIntBits(actual) != Float.floatToIntBits(expected) && Math.abs(actual - expected) > 2e-6f) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    private static void near(float actual, float expected, String label) {
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > Math.max(0.0003f, Math.abs(expected) * 0.0005f)) throw new AssertionError(label + ": " + actual + " != " + expected);
    }
    private static void ok(int result) { if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan error " + result); }
    private static ByteBuffer compile(String source, int kind) {
        long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize();
        long result = 0;
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "forward-depth-regression", "main", options);
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining()); copy.put(bytes).flip(); return copy;
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private record Buffer(long handle, long memory, long size) { }
    private record Image(long handle, long memory, long view, int width, int aspect) { }

    private static final class Device implements AutoCloseable {
        final VkInstance instance; final VkPhysicalDevice physical; final VkDevice device; final VkQueue queue;
        final VkCommandBuffer command; final long commandPool; final String name;
        final List<Buffer> buffers = new ArrayList<>(); final List<Image> images = new ArrayList<>();
        long shader, sampler, setLayout, descriptorPool, descriptorSet, pipelineLayout, pipeline;
        Device() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer pointer = stack.mallocPointer(1);
                VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().pApplicationName(stack.UTF8("Iris forward-depth regression")).apiVersion(VK12.VK_API_VERSION_1_2);
                VkInstanceCreateInfo create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app);
                ok(vkCreateInstance(create, null, pointer)); instance = new VkInstance(pointer.get(0), create);
                var count = stack.mallocInt(1); ok(vkEnumeratePhysicalDevices(instance, count, null));
                PointerBuffer devices = stack.mallocPointer(count.get(0)); ok(vkEnumeratePhysicalDevices(instance, count, devices));
                physical = new VkPhysicalDevice(devices.get(0), instance);
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack); vkGetPhysicalDeviceProperties(physical, properties); name = properties.deviceNameString();
                vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack); vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
                int family = -1; for (int i = 0; i < families.capacity(); i++) if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) { family = i; break; }
                if (family < 0) throw new IllegalStateException("No compute queue");
                VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1));
                VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues);
                ok(vkCreateDevice(physical, deviceInfo, null, pointer)); device = new VkDevice(pointer.get(0), physical, deviceInfo);
                vkGetDeviceQueue(device, family, 0, pointer); queue = new VkQueue(pointer.get(0), device);
                LongBuffer value = stack.mallocLong(1);
                ok(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family).flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT), null, value)); commandPool = value.get(0);
                ok(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer)); command = new VkCommandBuffer(pointer.get(0), device);
            }
        }
        int memoryType(int mask, int flags) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.malloc(stack); vkGetPhysicalDeviceMemoryProperties(physical, properties);
                for (int i = 0; i < properties.memoryTypeCount(); i++) if ((mask & 1 << i) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) return i;
                throw new IllegalStateException("No memory type for " + flags);
            }
        }
        Buffer buffer(long size, int usage) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                ok(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, value)); long handle = value.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack); vkGetBufferMemoryRequirements(device, handle, requirements);
                ok(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)), null, value)); long memory = value.get(0);
                ok(vkBindBufferMemory(device, handle, memory, 0)); Buffer buffer = new Buffer(handle, memory, size); buffers.add(buffer); return buffer;
            }
        }
        ByteBuffer map(Buffer buffer) {
            try (MemoryStack stack = MemoryStack.stackPush()) { PointerBuffer pointer = stack.mallocPointer(1); ok(vkMapMemory(device, buffer.memory, 0, buffer.size, 0, pointer)); return MemoryUtil.memByteBuffer(pointer.get(0), (int) buffer.size).order(ByteOrder.nativeOrder()); }
        }
        Image image(int width, int format, int aspect) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, 1, 1); ok(vkCreateImage(device, info, null, value)); long handle = value.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack); vkGetImageMemoryRequirements(device, handle, requirements);
                ok(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size()).memoryTypeIndex(memoryType(requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, value)); long memory = value.get(0);
                ok(vkBindImageMemory(device, handle, memory, 0));
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default().image(handle).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
                view.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1); ok(vkCreateImageView(device, view, null, value));
                Image image = new Image(handle, memory, value.get(0), width, aspect); images.add(image); return image;
            }
        }
        void createPipeline(ByteBuffer spirv, Image raw, Image forward, Buffer output) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer value = stack.mallocLong(1);
                ok(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, value)); shader = value.get(0);
                ok(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST).addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0), null, value)); sampler = value.get(0);
                VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
                for (int i = 0; i < 3; i++) bindings.get(i).binding(i).descriptorCount(1).descriptorType(i == 2 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                ok(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, value)); setLayout = value.get(0);
                VkPushConstantRange.Buffer constants = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).size(80);
                ok(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(constants), null, value)); pipelineLayout = value.get(0);
                VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
                pipelineInfo.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
                ok(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, value)); pipeline = value.get(0);
                VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack); sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2); sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
                ok(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, value)); descriptorPool = value.get(0);
                ok(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout)), value)); descriptorSet = value.get(0);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
                for (int i = 0; i < 2; i++) {
                    VkDescriptorImageInfo.Buffer image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(i == 0 ? raw.view : forward.view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(image);
                }
                writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(output.handle).range(output.size));
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
        void begin() { try (MemoryStack stack = MemoryStack.stackPush()) { ok(vkBeginCommandBuffer(command, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))); } }
        void upload(Buffer buffer, Image image, long offset) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack).sType$Default().image(image.handle).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.subresourceRange().aspectMask(image.aspect).levelCount(1).layerCount(1);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
                VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack).bufferOffset(offset); copy.imageSubresource().aspectMask(image.aspect).layerCount(1); copy.imageExtent().set(image.width, 1, 1);
                vkCmdCopyBufferToImage(command, buffer.handle, image.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
                barrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
            }
        }
        void submitAndWait() { try (MemoryStack stack = MemoryStack.stackPush()) { ok(vkEndCommandBuffer(command)); VkSubmitInfo.Buffer submit = VkSubmitInfo.calloc(1, stack).sType$Default().pCommandBuffers(stack.pointers(command)); ok(vkQueueSubmit(queue, submit, VK_NULL_HANDLE)); ok(vkQueueWaitIdle(queue)); } }
        @Override public void close() {
            vkDeviceWaitIdle(device);
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
            if (descriptorPool != 0) vkDestroyDescriptorPool(device, descriptorPool, null);
            if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
            if (sampler != 0) vkDestroySampler(device, sampler, null);
            if (shader != 0) vkDestroyShaderModule(device, shader, null);
            for (Image image : images) { vkDestroyImageView(device, image.view, null); vkDestroyImage(device, image.handle, null); vkFreeMemory(device, image.memory, null); }
            for (Buffer buffer : buffers) { vkDestroyBuffer(device, buffer.handle, null); vkFreeMemory(device, buffer.memory, null); }
            vkDestroyCommandPool(device, commandPool, null); vkDestroyDevice(device, null); vkDestroyInstance(instance, null);
        }
    }
}
