package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline declaration/ownership checks; no Minecraft instance, graphics device or texture is created. */
public final class IrisVulkanStorageCapabilitiesTest {
    public static void main(String[] args) throws Exception {
        var voxel = image("voxel_img", "voxel_sampler", TextureType.TEXTURE_3D, InternalTextureFormat.R16UI);
        var flood = image("floodfill_img", "floodfill_sampler", TextureType.TEXTURE_3D, InternalTextureFormat.RGBA16F);
        var atlas = image("playerAtlas", "playerAtlasSampler", TextureType.TEXTURE_2D, InternalTextureFormat.RGBA8);
        var support = new IrisVulkanPackCapabilities.StorageSupport(true,
            Map.of(voxel.name(), voxel, flood.name(), flood, atlas.name(), atlas),
            Map.of(voxel.samplerName(), voxel, flood.samplerName(), flood, atlas.samplerName(), atlas), Set.of(0));
        String source = """
            #version 450 core
            layout(std430, binding = 0) buffer blockDataBuffer { uint entries[]; } blockDataSSBO;
            layout(r16ui) uniform uimage3D voxel_img;
            uniform writeonly image3D floodfill_img;
            uniform usampler3D voxel_sampler;
            uniform sampler3D floodfill_sampler;
            uniform image2D playerAtlas;
            uniform sampler2D playerAtlasSampler;
            void main() {
                imageStore(voxel_img, ivec3(0), uvec4(blockDataSSBO.entries[0]));
                imageStore(floodfill_img, ivec3(0), vec4(texture(voxel_sampler, vec3(0))));
                imageStore(playerAtlas, ivec2(0), texture(playerAtlasSampler, vec2(0)) + texture(floodfill_sampler, vec3(0)));
            }
            """;
        check(requirements(source, support).isEmpty(), "Bound native 2D/3D images, integer volume samplers and SSBO are supported");
        check(!requirements(source, IrisVulkanPackCapabilities.StorageSupport.DISABLED).isEmpty(), "Storage remains opt-in");
        fails(source.replace("uimage3D voxel_img", "uimage2D voxel_img"), support, "voxel_img");
        fails(source.replace("uimage3D voxel_img", "image3D voxel_img"), support, "voxel_img");
        fails(source.replace("usampler3D voxel_sampler", "sampler3D voxel_sampler"), support, "voxel_sampler");
        fails(source.replace("binding = 0", "binding = 9"), support, "blockDataBuffer");
        fails(source.replace("layout(std430, binding = 0)", "layout(std430)"), support, "blockDataBuffer");
        fails(source.replace("uimage3D voxel_img;", "uimage3D voxel_img[2];"), support, "voxel_img");
        fails(source.replace("sampler3D floodfill_sampler;", "sampler3D floodfill_sampler[2];"), support, "floodfill_sampler");
        fails(source.replace("floodfill_img", "unknown_image"), support, "unknown_image");
        check(IrisVulkanPackCapabilities.storageImageFailure(image("one", "s", TextureType.TEXTURE_1D, InternalTextureFormat.R16UI)) != null,
            "Unimplemented one-dimensional descriptors stay rejected");
        check(IrisVulkanPackCapabilities.storageImageFailure(image("rgb", "s", TextureType.TEXTURE_3D, InternalTextureFormat.RGB8)) != null,
            "Formats without a native storage-image layout stay rejected");
        for (String path : List.of("textures/atlas/blocks.png", "textures/atlas/items.png", "textures/atlas/particles.png")) {
            check(IrisVulkanCustomTextures.supportsResourceData(new CustomTextureData.ResourceData("minecraft", path)), "Real engine atlas " + path);
        }
        for (var resource : List.of(new CustomTextureData.ResourceData("minecraft", "textures/not-an-atlas.png"),
            new CustomTextureData.ResourceData("missingmod", "textures/atlas/blocks.png"),
            new CustomTextureData.ResourceData("minecraft", "textures/atlas/blocks_s.png"))) {
            check(!IrisVulkanCustomTextures.supportsResourceData(resource), "Unknown resource must not be treated as a supported PNG");
            check(IrisVulkanCustomTextures.unsupportedReason(resource).contains(resource.getNamespace() + ":" + resource.getLocation()),
                "Resource failure names the requested identifier");
        }
        verifyOwnership();
        verifyLiveLookup();
        int realSources = args.length == 0 ? 0 : verifyRealUltra(Path.of(args[0]), voxel, flood);
        System.out.println("Native Vulkan storage capability checks passed: exact bindings/types, disabled gate, unsupported resources, borrowed atlas lifetime, "
            + realSources + " actual ULTRA source stages.");
    }

    private static int verifyRealUltra(Path directory, ImageInformation voxel, ImageInformation flood) throws Exception {
        List<ImageInformation> images = List.of(voxel, flood,
            image("floodfill_img_copy", "floodfill_sampler_copy", TextureType.TEXTURE_3D, InternalTextureFormat.RGBA16F),
            image("wsr_img", "wsr_sampler", TextureType.TEXTURE_3D, InternalTextureFormat.R16UI),
            image("wsr_lod_img", "wsr_lod_sampler", TextureType.TEXTURE_3D, InternalTextureFormat.R8UI));
        Map<String, ImageInformation> imageNames = new java.util.HashMap<>(), samplerNames = new java.util.HashMap<>();
        for (var information : images) {
            imageNames.put(information.name(), information);
            samplerNames.put(information.samplerName(), information);
        }
        var supported = new IrisVulkanPackCapabilities.StorageSupport(true, imageNames, samplerNames, Set.of(0));
        int checked = 0;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".vsh") || path.toString().endsWith(".fsh") || path.toString().endsWith(".csh")).toList()) {
                var failures = requirements(Files.readString(path), supported);
                check(failures.isEmpty(), "Actual ULTRA resource stage rejected: " + path + " " + failures);
                checked++;
            }
        }
        check(checked > 0, "Actual ULTRA source evidence must exist");
        return checked;
    }

    private static ImageInformation image(String name, String sampler, TextureType target, InternalTextureFormat format) {
        return new ImageInformation(name, sampler, target, format.getPixelFormat(), format, PixelType.UNSIGNED_BYTE,
            64, 64, target == TextureType.TEXTURE_3D ? 64 : 0, false, false, 0, 0);
    }

    private static List<IrisVulkanPackCapabilities.Unsupported> requirements(String source, IrisVulkanPackCapabilities.StorageSupport support) {
        return IrisVulkanPackCapabilities.sourceRequirements("storage-test", source, name -> false, support);
    }

    private static void fails(String source, IrisVulkanPackCapabilities.StorageSupport support, String resource) {
        check(requirements(source, support).stream().anyMatch(failure -> failure.resource().equals(resource)), "Missing rejection for " + resource);
    }

    private static void verifyOwnership() {
        var image = new TestTexture();
        var view = new TestView(image);
        var sampler = new TestSampler();
        new IrisVulkanCustomTextures.Binding(image, view, sampler, false).close();
        check(!image.closed && !view.closed && !sampler.closed, "Borrowed engine atlas, view and sampler remain alive on pack cleanup");
        new IrisVulkanCustomTextures.Binding(image, view, sampler).close();
        check(image.closed && view.closed && sampler.closed, "Owned custom textures still release all resources");
    }

    private static void verifyLiveLookup() throws Exception {
        try (var input = IrisVulkanStorageCapabilitiesTest.class.getClassLoader().getResourceAsStream(
            "net/irisshaders/iris/vulkan/IrisVulkanCustomTextures.class")) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            var find = node.methods.stream().filter(method -> method.name.equals("find")).findFirst().orElseThrow();
            check(java.util.stream.StreamSupport.stream(find.instructions.spliterator(), false).anyMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.name.equals("liveResourceBinding")), "Each find resolves a live ResourceData binding");
            var live = node.methods.stream().filter(method -> method.name.equals("liveResourceBinding")).findFirst().orElseThrow();
            Set<String> required = new java.util.HashSet<>(Set.of("getTextureManager", "getTexture", "getTextureView", "getSampler"));
            for (var instruction : live.instructions) if (instruction instanceof MethodInsnNode call) required.remove(call.name);
            check(required.isEmpty(), "Live atlas lookup re-queries the manager, view and sampler after resource reloads");
        }
    }

    private static final class TestTexture extends GpuTexture {
        private boolean closed;
        private TestTexture() { super(USAGE_TEXTURE_BINDING, "ownership test", GpuFormat.RGBA8_UNORM, 16, 16, 1, 1); }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    private static final class TestView extends GpuTextureView {
        private boolean closed;
        private TestView(GpuTexture texture) { super(texture, 0, 1); }
        @Override public void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }

    private static final class TestSampler extends GpuSampler {
        private boolean closed;
        @Override public AddressMode getAddressModeU() { return AddressMode.CLAMP_TO_EDGE; }
        @Override public AddressMode getAddressModeV() { return AddressMode.CLAMP_TO_EDGE; }
        @Override public FilterMode getMinFilter() { return FilterMode.NEAREST; }
        @Override public FilterMode getMagFilter() { return FilterMode.NEAREST; }
        @Override public int getMaxAnisotropy() { return 1; }
        @Override public OptionalDouble getMaxLod() { return OptionalDouble.empty(); }
        @Override public void close() { closed = true; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
