package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.ImageInformation;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;

/** Actual compiled planners and allocation loops, replacing only live world and GPU providers. */
public final class ColorImageContract {
    private static final String PACKAGE = "net.irisshaders.iris.vulkan.";
    private static final String SELF = ColorImageContract.class.getName().replace('.', '/');
    private static final List<Texture> TEXTURES = new ArrayList<>();
    private static Class<?> modelType, helperType;
    private static Object model;
    private static GpuTextureView overrideView;
    private static int checks, rejectedAllocations;
    private static boolean rejectStorage;
    private static final String VERTEX = "#version 450\nvoid main(){gl_Position=vec4(0);}\n";
    private static final String COMPUTE = "#version 450\nlayout(local_size_x=1) in;\n"
            + "layout(rgba16f) writeonly uniform image2D colorimg4;\n"
            + "uniform sampler2D colortex4;\nvoid main(){imageStore(colorimg4,ivec2(0),texelFetch(colortex4,ivec2(0),0));}\n";

    public static void main(String[] args) throws Exception {
        pureContracts();
        try (ModelLoader loader = new ModelLoader(Path.of(args[0]))) {
            modelType = loader.loadClass(PACKAGE + "IrisVulkanTargetModel");
            helperType = loader.loadClass(PACKAGE + "IrisVulkanColorImages");
            lifecycle();
        }
        System.out.println("PASS: " + checks + " color-image CPU contracts; real parser/allocation/selection/cleanup loops, "
                + "scoped storage flags, exact formats, mip-zero borrowed views, and compiler descriptors; no GPU execution");
    }

    private static void pureContracts() throws Exception {
        for (int i = 0; i < 32; i++) check(IrisVulkanColorImages.target("colorimg" + i) == i, "Canonical index " + i);
        for (String name : Arrays.asList(null, "", "colortex4", "colorimg", "colorimg-1", "colorimg32", "colorimg04",
                "colorimg+4", "colorimg4x", "colorimg2147483648", "shadowcolorimg4"))
            check(IrisVulkanColorImages.target(name) == -1, "Reject invalid alias " + name);
        check(IrisVulkanColorImages.sourceTargets(null).isEmpty(), "Null source");
        check(IrisVulkanColorImages.sourceTargets("uniform image2D colorimg4;\nvoid main(){}\n").isEmpty(), "Prune unused declaration");
        check(IrisVulkanColorImages.sourceTargets("/* uniform image2D colorimg8; imageStore(colorimg8); */\n"
                + "// uniform image2D colorimg9; imageStore(colorimg9);\n" + COMPUTE).equals(Set.of(4)), "Comments cannot allocate targets");
        check(IrisVulkanColorImages.sourceTargets(COMPUTE.replace("colorimg4", "colorimg31")).equals(Set.of(31)), "Highest supported target");
        check(IrisVulkanColorImages.sourceTargets(COMPUTE.replace("colorimg4", "colorimg32")).isEmpty(), "Out-of-range source target");
        check(IrisVulkanColorImages.sourceTargets(COMPUTE.replace("writeonly uniform", "uniform coherent writeonly")).equals(Set.of(4)), "Qualifiers after uniform");
        String multipleImages = "#version 450\nlayout(local_size_x=1) in;\nlayout(rgba16f) uniform image2D colorimg4, colorimg5;\n"
                + "void main(){imageStore(colorimg4,ivec2(0),vec4(1));imageStore(colorimg5,ivec2(0),vec4(2));}\n";
        check(IrisVulkanColorImages.sourceTargets(multipleImages).equals(Set.of(4, 5)), "Every live opaque declarator gains storage allocation");
        check(IrisVulkanComputeCompiler.prepareSource("multiple-images", multipleImages, Map.of("colorimg4", "rgba16f", "colorimg5", "rgba16f"))
                .descriptors().stream().filter(d -> d.kind() == IrisVulkanComputeCompiler.Kind.STORAGE_IMAGE).count() == 2,
                "Both opaque declarators retain independent storage descriptors");
        String imageArray = "#version 450\nlayout(local_size_x=1) in;\nlayout(rgba16f) uniform image2D colorimg4[2];\n"
                + "void main(){imageStore(colorimg4[0],ivec2(0),vec4(1));}\n";
        reject(UnsupportedOperationException.class, () -> IrisVulkanComputeCompiler.prepareSource("image-array", imageArray, Map.of("colorimg4", "rgba16f")));
        String switchedComments = "#version 450\nlayout(local_size_x=1) in;\n//*\n"
                + "layout(rgba16f) uniform image2D colorimg4;\nfloat active(float value){return value/2.0;}\n//*/\n/*\n"
                + "layout(rgba16f) uniform image2D colorimg5;\nfloat inactive(float value){return undefined(value);}\n//*/\n"
                + "void main(){imageStore(colorimg4,ivec2(0),vec4(active(2.0)));}\n";
        check(IrisVulkanColorImages.sourceTargets(switchedComments).equals(Set.of(4)), "Line-comment switch keeps active alias and excludes block-comment alias");
        var switchedPrepared = IrisVulkanComputeCompiler.prepareSource("switch-comments", switchedComments, Map.of("colorimg4", "rgba16f"));
        check(switchedPrepared.source().contains("float active(") && !switchedPrepared.source().contains("float inactive("),
                "Photon comment switches preserve active functions and remove disabled functions");
        check(!switchedPrepared.source().matches("(?s).*\\n\\h*/\\h*\\n.*"), "Comment switches cannot leave bare slash tokens");

        int base = 0x15;
        check(IrisVulkanColorImages.allocationUsage(base) == base, "Unrelated allocation unchanged");
        Object token = new Object();
        check(IrisVulkanColorImages.allocate(true, () -> {
            check(IrisVulkanColorImages.allocationUsage(base) == (base | VK_IMAGE_USAGE_STORAGE_BIT), "Storage scope active");
            IrisVulkanColorImages.allocate(false, () -> {
                check(IrisVulkanColorImages.allocationUsage(base) == base, "Nested false scope masks outer scope");
                IrisVulkanColorImages.allocate(true, () -> {
                    check(IrisVulkanColorImages.allocationUsage(base) == (base | VK_IMAGE_USAGE_STORAGE_BIT), "Nested true scope");
                    return null;
                });
                check(IrisVulkanColorImages.allocationUsage(base) == base, "Inner scope restores false");
                return null;
            });
            check(IrisVulkanColorImages.allocationUsage(base) == (base | VK_IMAGE_USAGE_STORAGE_BIT), "LIFO restores outer true");
            return token;
        }) == token, "Scope preserves returned allocation identity");
        check(IrisVulkanColorImages.allocationUsage(base) == base, "Outer scope removed");
        reject(IllegalStateException.class, () -> IrisVulkanColorImages.allocate(true, () -> { throw new IllegalStateException("allocation failure"); }));
        check(IrisVulkanColorImages.allocationUsage(base) == base, "Exceptional cleanup");
        IrisVulkanColorImages.allocate(true, () -> {
            var inherited = new java.util.concurrent.atomic.AtomicInteger();
            Thread thread = Thread.ofPlatform().start(() -> inherited.set(IrisVulkanColorImages.allocationUsage(base)));
            try { thread.join(); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            check(inherited.get() == base, "Allocation scope is thread-local");
            return null;
        });

        for (GpuFormat format : GpuFormat.values()) {
            String glsl;
            try { glsl = IrisVulkanColorImages.format(format); }
            catch (UnsupportedOperationException unsupported) { continue; }
            String type = (format.name().endsWith("_UINT") ? "u" : format.name().endsWith("_SINT") ? "i" : "") + "image2D";
            IrisVulkanColorImages.validateFormat("colorimg4", format, type, glsl);
            check(true, "Accepted exact type/format " + format);
            reject(IllegalArgumentException.class, () -> IrisVulkanColorImages.validateFormat("colorimg4", format, "image3D", glsl));
            reject(IllegalArgumentException.class, () -> IrisVulkanColorImages.validateFormat("colorimg4", format, type, "not_a_format"));
        }
        reject(IllegalArgumentException.class, () -> IrisVulkanColorImages.validateFormat("colorimg4", GpuFormat.R32_UINT, "image2D", "r32ui"));
        reject(IllegalArgumentException.class, () -> IrisVulkanColorImages.validateFormat("colorimg4", GpuFormat.R32_SINT, "uimage2D", "r32i"));
        reject(UnsupportedOperationException.class, () -> IrisVulkanColorImages.format(GpuFormat.RGB16_FLOAT));
        var plain = new IrisVulkanTargetSpec(4, GpuFormat.RGBA16_FLOAT, 192, 108, 1, false, null, IrisVulkanTargetSpec.SeedPolicy.CLEAR);
        var writable = plain.withStorageImage(true);
        check(!plain.storageImage() && writable.storageImage() && !plain.equals(writable), "Storage capability participates in configuration equality");
        check(writable.withFormat(GpuFormat.RGBA8_UNORM).storageImage(), "Format replacement retains capability");
        check(writable.withStorageImage(false).equals(plain), "Capability can be removed");

        ProgramSet programSet = programs(COMPUTE, true, false, List.of());
        check(IrisVulkanColorImages.computeTargets(programSet).equals(Set.of(4)), "Compute planner selects required alias");
        var declaration = IrisVulkanColorImages.declarations(programSet).get("colorimg4");
        check(declaration.internalTextureFormat() == InternalTextureFormat.RGBA16F && declaration.target() == TextureType.TEXTURE_2D,
                "Builtin image declaration inherits target format and image dimension");
        var source = programSet.getCompute(ProgramArrayId.Deferred)[4][0];
        var prepared = IrisVulkanComputeCompiler.prepare(source);
        var storage = prepared.descriptors().stream().filter(d -> d.kind() == IrisVulkanComputeCompiler.Kind.STORAGE_IMAGE).findFirst().orElseThrow();
        check(storage.name().equals("colorimg4") && storage.imageFormat().equals("rgba16f") && storage.type().equals("image2D"), "Compiler retains format metadata");
        check(prepared.descriptors().stream().anyMatch(d -> d.kind() == IrisVulkanComputeCompiler.Kind.SAMPLED_IMAGE && d.name().equals("colortex4")), "Sampler alias stays live");
        check(prepared.source().contains("imageStore(colorimg4"), "Compiler preserves the image write");
        reject(UnsupportedOperationException.class, () -> IrisVulkanComputeCompiler.prepareSource("mismatch", COMPUTE, Map.of("colorimg4", "rgba8")));
        reject(UnsupportedOperationException.class, () -> IrisVulkanComputeCompiler.prepareSource("missing", COMPUTE.replace("layout(rgba16f) ", ""), Map.of()));
        check(IrisVulkanComputeCompiler.prepareSource("implicit", COMPUTE.replace("layout(rgba16f) ", ""), Map.of("colorimg4", "rgba16f"))
                .descriptors().stream().anyMatch(d -> "rgba16f".equals(d.imageFormat())), "Known allocation supplies an omitted image format");
        var custom = new ImageInformation("colorimg4", "custom_alias", TextureType.TEXTURE_2D,
                InternalTextureFormat.RGBA16F.getPixelFormat(), InternalTextureFormat.RGBA16F, PixelType.FLOAT, 192, 108, 1, false, false, 1, 1);
        check(IrisVulkanColorImages.computeTargets(programs(COMPUTE, true, false, List.of(custom))).isEmpty(), "Custom image namespace wins");
        check(IrisVulkanColorImages.computeTargets(programs(Map.of("gbuffers_terrain.vsh", VERTEX,
                "gbuffers_terrain.fsh", "#version 450\nlayout(rgba16f) uniform image2D colorimg4;\nvoid main(){imageStore(colorimg4,ivec2(0),vec4(1));}\n"),
                "", List.of())).isEmpty(), "Graphics-only colorimg declaration does not enable compute allocation");
        String rethinkingFragment = "#version 450\nconst int colortex3Format=RGBA16F;\nconst int colortex8Format=RGBA16F;\nconst int colortex9Format=R32UI;\n"
                + "layout(rgba16f) writeonly uniform image2D colorimg3;\n"
                + "layout(rgba16f) writeonly uniform image2D colorimg8;\n"
                + "layout(r32ui) writeonly uniform uimage2D colorimg9;\n"
                + "void main(){imageStore(colorimg3,ivec2(0),vec4(1));imageStore(colorimg8,ivec2(0),vec4(1));imageStore(colorimg9,ivec2(0),uvec4(1));}\n";
        ProgramSet rethinking = programs(Map.of("gbuffers_terrain.vsh", VERTEX, "gbuffers_terrain.fsh", rethinkingFragment), "", List.of());
        check(IrisVulkanColorImages.computeTargets(rethinking).isEmpty(),
                "Original compute-only planner leaves Rethinking graphics aliases out");
        check(IrisVulkanColorImages.graphicsTargets(rethinking).equals(Set.of(3, 8, 9)),
                "Rethinking graphics aliases are planned for storage usage");
        var rethinkingDeclarations = IrisVulkanColorImages.graphicsDeclarations(rethinking);
        check(rethinkingDeclarations.get("colorimg9").internalTextureFormat() == InternalTextureFormat.R32UI,
                "Unsigned colorimg9 inherits the R32UI target format");
        for (String stage : List.of("setup", "shadow", "final", "begin", "prepare", "deferred", "composite", "shadowcomp")) {
            ProgramSet stages = programs(Map.of(stage + ".csh", COMPUTE), "", List.of());
            check(IrisVulkanColorImages.computeTargets(stages).contains(4), "Compute stage discovery " + stage);
        }
    }

    private static void lifecycle() throws Exception {
        ProgramSet writable = programs(COMPUTE, true, false, List.of());
        model = modelType.getConstructor().newInstance();
        int start = TEXTURES.size();
        check(configure(writable, 640, 360), "Initial pair configuration");
        List<Texture> first = List.copyOf(TEXTURES.subList(start, TEXTURES.size()));
        List<Texture> target = first.stream().filter(t -> t.label.contains("colortex4 ")).toList();
        check(target.size() == 2, "Both physical sides allocated");
        for (Texture texture : first) {
            check((texture.nativeUsage & VK_IMAGE_USAGE_STORAGE_BIT) != 0 == texture.label.contains("colortex4 "), "Only required pair gains storage usage");
            check(texture.nativeUsage == (VulkanConst.textureUsageToVk(texture.usage, texture.format)
                    | (texture.label.contains("colortex4 ") ? VK_IMAGE_USAGE_STORAGE_BIT : 0)), "Existing native usages preserved");
        }
        check(target.getFirst().width == 192 && target.getFirst().height == 108 && target.getFirst().format == GpuFormat.RGBA16_FLOAT, "Photon target extent/format");
        GpuTextureView sampler = current(), next = next(), image = alias();
        check(image.texture() == sampler.texture() && image.texture() != next.texture(), "Image aliases current side, not next");
        check(image.baseMipLevel() == 0 && image.mipLevels() == 1, "Storage image exposes one level at mip zero");
        check(sampler.mipLevels() > 1, "Fixture exercises mipmapped sampling view");
        check(alias() == image, "Borrowed image view is reused");
        check(invokeModel("colorImageView", new Class<?>[]{int.class}, 0) == null, "Unmarked target cannot become storage alias");
        check(invokeHelper("view", new Class<?>[]{String.class, String.class, String.class}, "custom", "image2D", "rgba16f") == null, "Custom name left to custom resolver");
        invokeModel("swap", new Class<?>[]{int.class}, 4);
        check(alias().texture() == next.texture(), "Swap moves borrowed image to alternate side");
        invokeModel("select", new Class<?>[]{int.class, boolean.class}, 4, false);
        check(alias() == image, "Explicit main selection restores cached view");
        invokeModel("select", new Class<?>[]{int.class, boolean.class}, 4, true);
        check(alias().texture() == next.texture(), "Explicit alternate selection");
        int allocated = TEXTURES.size();
        check(!configure(writable, 640, 360) && TEXTURES.size() == allocated, "Identical configuration retains allocations");
        check(first.stream().allMatch(t -> t.closes == 0 && t.views.stream().allMatch(v -> v.closes == 0)), "Borrowing never closes ownership");

        reject(IllegalArgumentException.class, () -> invokeHelper("view", new Class<?>[]{String.class, String.class, String.class}, "colorimg4", "uimage2D", "rgba16f"));
        reject(IllegalArgumentException.class, () -> invokeHelper("view", new Class<?>[]{String.class, String.class, String.class}, "colorimg4", "image2D", "rgba8"));
        View closedView = new View(target.getFirst(), 0, 1); closedView.closes = 1; overrideView = closedView.view;
        reject(IllegalStateException.class, ColorImageContract::alias); overrideView = null;
        Texture closedTexture = target.getFirst(); closedTexture.closes = 1; overrideView = new View(closedTexture, 0, 1).view;
        reject(IllegalStateException.class, ColorImageContract::alias); overrideView = null; closedTexture.closes = 0;

        ProgramSet sampledOnly = programs(null, true, false, List.of());
        check(configure(sampledOnly, 640, 360), "Removing storage capability recreates otherwise equivalent pair");
        closedOnce(first);
        reject(IllegalStateException.class, ColorImageContract::alias);
        check(configure(writable, 640, 360), "Enabling storage again recreates pair");
        GpuTextureView beforeResize = alias();
        check(configure(writable, 800, 450), "Window resize rebuilds model configuration");
        check(beforeResize.isClosed() && alias().texture() != beforeResize.texture(), "Resize cannot return stale alias");
        check(alias().getWidth(0) == 192 && alias().getHeight(0) == 108, "Fixed-size Photon image stays fixed during viewport resize");
        ProgramSet relative = programs(COMPUTE, true, true, List.of());
        check(configure(relative, 800, 450), "Relative target dimensions replace fixed pair");
        check(alias().getWidth(0) == 400 && alias().getHeight(0) == 225, "Relative image size follows viewport");
        invokeModel("close", new Class<?>[]{});
        check(invokeModel("colorImageView", new Class<?>[]{int.class}, 4) == null, "Destroyed model has no stale image");
        closedOnce(TEXTURES.subList(start, TEXTURES.size()));

        model = modelType.getConstructor().newInstance();
        rejectStorage = true; rejectedAllocations = 0;
        check(!configure(writable, 640, 360), "Unsupported storage allocation cleanly declines configuration");
        rejectStorage = false;
        check(rejectedAllocations == 1, "Storage allocation never retries with a different format");
        check((int) invokeHelper("allocationUsage", new Class<?>[]{int.class}, 0x15) == 0x15, "Failed allocation scope cleaned up");
        check(configure(writable, 640, 360), "Clean recovery after allocation failure");
        invokeModel("close", new Class<?>[]{});
        closedOnce(TEXTURES.subList(start, TEXTURES.size()));
    }

    private static ProgramSet programs(String compute, boolean mipmapped, boolean relative, List<ImageInformation> custom) throws Exception {
        Map<String, String> sources = new HashMap<>();
        sources.put("gbuffers_terrain.vsh", VERTEX);
        sources.put("gbuffers_terrain.fsh", "#version 450\nconst int colortex4Format=RGBA16F;\nconst bool colortex4Clear=false;\n"
                + (mipmapped ? "const bool colortex4MipmapEnabled=true;\n" : "")
                + "uniform sampler2D colortex4;\nvoid main(){vec4 c=texture(colortex4,vec2(0));}\n");
        if (mipmapped) {
            sources.put("deferred4.vsh", VERTEX);
            sources.put("deferred4.fsh", sources.get("gbuffers_terrain.fsh"));
        }
        if (compute != null) sources.put("deferred4.csh", compute);
        return programs(sources, "size.buffer.colortex4=" + (relative ? "0.5 0.5" : "192 108"), custom);
    }

    private static ProgramSet programs(Map<String,String> sources, String properties, List<ImageInformation> custom) throws Exception {
        var options = new ShaderPackOptions(new IncludeGraph(Path.of("."), ImmutableList.of(), false), Map.of());
        var directives = new ShaderProperties(properties, options, List.of());
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        ShaderPack pack = (ShaderPack) ((Unsafe) field.get(null)).allocateInstance(ShaderPack.class);
        for (var entry : Map.of("activeFeatures", EnumSet.allOf(FeatureFlags.class), "irisCustomImages", custom).entrySet()) {
            Field target = ShaderPack.class.getDeclaredField(entry.getKey()); target.setAccessible(true); target.set(pack, entry.getValue());
        }
        return new ProgramSet(AbsolutePackPath.fromAbsolutePath("/"), path -> sources.get(path.getPathString().substring(1)), directives, pack);
    }

    private static boolean configure(ProgramSet programs, int width, int height) throws Exception {
        return (boolean) invokeModel("configure", new Class<?>[]{ProgramSet.class, int.class, int.class, GpuFormat.class}, programs, width, height, GpuFormat.RGBA8_UNORM);
    }
    private static GpuTextureView current() throws Exception { return (GpuTextureView) invokeModel("currentView", new Class<?>[]{int.class}, 4); }
    private static GpuTextureView next() throws Exception { return (GpuTextureView) invokeModel("nextView", new Class<?>[]{int.class}, 4); }
    private static GpuTextureView alias() throws Exception { return (GpuTextureView) invokeHelper("view", new Class<?>[]{String.class, String.class, String.class}, "colorimg4", "image2D", "rgba16f"); }
    private static Object invokeModel(String name, Class<?>[] parameters, Object... args) throws Exception { return call(modelType.getMethod(name, parameters), model, args); }
    private static Object invokeHelper(String name, Class<?>[] parameters, Object... args) throws Exception { Method method=helperType.getDeclaredMethod(name, parameters); method.setAccessible(true); return call(method, null, args); }
    private static Object call(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception error) throw error; if (failure.getCause() instanceof Error error) throw error; throw failure; }
    }
    private static void closedOnce(List<Texture> textures) {
        for (Texture texture : textures) { check(texture.closes == 1, "Owner closes texture once"); for (View view : texture.views) check(view.closes == 1, "Owner closes each cached view once"); }
    }

    public static final class Gpu { public static GpuDevice getDevice() { return DEVICE; } }
    public static final class NativeFlags { public static void unregisterCustomPipelineSource(RenderPipeline ignored) { throw new AssertionError("Unexpected render pipeline creation"); } }
    public static final class Gbuffers {
        public static GpuTextureView colorImageView(int index) {
            if (overrideView != null) return overrideView;
            try { return (GpuTextureView) invokeModel("colorImageView", new Class<?>[]{int.class}, index); }
            catch (Exception error) { throw new AssertionError(error); }
        }
    }
    private static final GpuDevice DEVICE = proxy(GpuDevice.class, (object, method, args) -> switch (method.getName()) {
        case "createTexture" -> {
            int converted = (int) invokeHelper("allocationUsage", new Class<?>[]{int.class}, VulkanConst.textureUsageToVk((int) args[1], (GpuFormat) args[2]));
            if (rejectStorage && (converted & VK_IMAGE_USAGE_STORAGE_BIT) != 0) { rejectedAllocations++; throw new IllegalStateException("Simulated unsupported storage format"); }
            yield new Texture(args, converted).texture;
        }
        case "createTextureView" -> {
            Texture texture = TEXTURES.stream().filter(t -> t.texture == args[0]).findFirst().orElseThrow();
            View view = new View(texture, args.length == 1 ? 0 : (int) args[1], args.length == 1 ? texture.mips : (int) args[2]);
            texture.views.add(view); yield view.view;
        }
        default -> throw new AssertionError("Unexpected GPU operation " + method);
    });
    private static final class Texture {
        final String label; final GpuFormat format; final int width, height, mips, usage, nativeUsage; final GpuTexture texture;
        final List<View> views = new ArrayList<>(); int closes;
        Texture(Object[] args, int nativeUsage) {
            label = ((Supplier<?>) args[0]).get().toString(); usage = (int) args[1]; format = (GpuFormat) args[2]; width = (int) args[3]; height = (int) args[4]; mips = (int) args[6]; this.nativeUsage = nativeUsage;
            texture = proxy(GpuTexture.class, (object, method, a) -> switch (method.getName()) {
                case "getWidth" -> Math.max(1, width >> (int) a[0]); case "getHeight" -> Math.max(1, height >> (int) a[0]);
                case "getMipLevels" -> mips; case "getFormat" -> format; case "usage" -> usage; case "isClosed" -> closes != 0;
                case "close" -> { check(++closes == 1, "No double texture close"); yield null; }
                default -> throw new AssertionError("Unexpected texture call " + method);
            });
            TEXTURES.add(this);
        }
    }
    private static final class View {
        final GpuTextureView view; int closes;
        View(Texture texture, int base, int levels) {
            view = proxy(GpuTextureView.class, (object, method, args) -> switch (method.getName()) {
                case "texture" -> texture.texture; case "getWidth" -> Math.max(1, texture.width >> (base + (int) args[0]));
                case "getHeight" -> Math.max(1, texture.height >> (base + (int) args[0]));
                case "baseMipLevel" -> base; case "mipLevels" -> levels; case "isClosed" -> closes != 0;
                case "close" -> { check(++closes == 1, "No double view close"); yield null; }
                default -> throw new AssertionError("Unexpected view call " + method);
            });
        }
    }
    private static final class ModelLoader extends ClassLoader implements AutoCloseable {
        private final Path classes;
        ModelLoader(Path classes) { super(ColorImageContract.class.getClassLoader()); this.classes = classes; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!(name.startsWith(PACKAGE + "IrisVulkanTargetModel") || name.startsWith(PACKAGE + "IrisVulkanTargetSpec")
                    || name.startsWith(PACKAGE + "IrisVulkanColorImages") || name.equals(PACKAGE + "IrisVulkanShaderPruning"))) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) try {
                    byte[] bytes = Files.readAllBytes(classes.resolve(name.replace('.', '/') + ".class"));
                    ClassWriter writer = new ClassWriter(0);
                    new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(Map.of(
                            "com/mojang/blaze3d/systems/RenderSystem", SELF + "$Gpu",
                            PACKAGE.replace('.', '/') + "IrisNativeVulkan", SELF + "$NativeFlags",
                            PACKAGE.replace('.', '/') + "IrisVulkanGbufferTargets", SELF + "$Gbuffers"))), 0);
                    byte[] remapped = writer.toByteArray(); loaded = defineClass(name, remapped, 0, remapped.length);
                } catch (Exception error) { throw new ClassNotFoundException(name, error); }
                if (resolve) resolveClass(loaded); return loaded;
            }
        }
        @Override public void close() { }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) { case "hashCode" -> System.identityHashCode(object); case "equals" -> object == args[0]; case "toString" -> type.getSimpleName(); default -> throw new AssertionError(method); };
            return handler.invoke(object, method, args);
        });
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void reject(Class<? extends Throwable> expected, Operation action) {
        try { action.run(); throw new AssertionError("Expected " + expected.getSimpleName()); }
        catch (Throwable error) { check(expected.isInstance(error), "Expected " + expected.getSimpleName() + ", received " + error); }
    }
    private static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
