package net.irisshaders.iris.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.compat.sodium.mixin.MixinArenaAggregator;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/** Executes the real Sodium 0.9.2 constructor and stride resolver without allocating GPU buffers. */
public final class IrisSodiumArenaAllocatorTest {
    private static final String AGGREGATOR = "net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator";
    private static final String FORMAT = "net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats";
    private static final String TYPE = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;";
    private static final MixinArenaAggregator MIXIN = new MixinArenaAggregator();
    private static final Method CALLBACK;
    static {
        try { CALLBACK = MixinArenaAggregator.class.getDeclaredMethod("iris$registerCurrentGeometryStride"); CALLBACK.setAccessible(true); }
        catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    /** The transformed constructor calls the actual production redirect, not a copied implementation. */
    public static ChunkVertexType invokeProductionRedirect() {
        try { return (ChunkVertexType) CALLBACK.invoke(MIXIN); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    public static void main(String[] args) throws Exception {
        verifyOptionalTarget();
        try (JarFile beta = new JarFile(args[0])) {
            require(beta.getJarEntry(AGGREGATOR.replace('.', '/') + ".class") == null, "beta3 has no aggregator; the optional mixin must not require it");
        }
        var settings = WorldRenderingSettings.INSTANCE;
        var original = settings.getVertexFormat();
        try (JarFile release = new JarFile(args[1])) {
            verifyReloadPath(release);
            settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
            var unpatched = new ArenaLoader(release, false).loadClass(AGGREGATOR);
            Object oldInstance = construct(unpatched);
            require(stride(resolve(oldInstance, 20)) == 20, "Actual unpatched allocator registers compact stride20");
            reject(oldInstance, 36);
            var patched = new ArenaLoader(release, true).loadClass(AGGREGATOR);
            Object full = construct(patched);
            Object geometry = resolve(full, 36), index = resolve(full, 4);
            require(stride(geometry) == 36 && stride(index) == 4 && geometry != index, "Extended geometry and indices retain distinct correctly-sized allocator types");
            reject(full, 12);
            int formats = 0;
            for (int mask = 0; mask < 16; mask++) {
                ChunkVertexType format = FormatAnalyzer.createFormat((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
                settings.setVertexFormat(format);
                Object allocator = construct(patched);
                int expected = format.getVertexFormat().getVertexSize();
                require(stride(resolve(allocator, expected)) == expected && stride(resolve(allocator, 4)) == 4,
                    "Actual allocator accepts Iris attribute mask " + mask + " at stride " + expected);
                formats++;
            }
            settings.clearReloadRequired();
            settings.setVertexFormat(FormatAnalyzer.createFormat(false, false, false, false));
            require(settings.isReloadRequired(), "Disabling extended vertices requests renderer reconstruction");
            require(stride(resolve(full, 36)) == 36, "Changing settings cannot reinterpret existing36-byte allocations");
            reject(full, 20);
            Object afterReload = construct(patched);
            require(stride(resolve(afterReload, 20)) == 20, "A reconstructed allocator after reload uses the new20-byte format");
            reject(afterReload, 36);
            settings.clearReloadRequired();
            settings.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
            require(settings.isReloadRequired(), "Re-enabling extended vertices requests renderer reconstruction");
            require(stride(resolve(construct(patched), 36)) == 36, "Re-enabled shader builds a fresh36-byte allocator");
            System.out.println("IRIS_SODIUM_ALLOCATOR_PASS: real0.9.2 unpatched constructor reproduces Unsupported stride36; productionredirect fixes all" + formats + " Iris formats, preservesindex4/invalidstride checks and immutable allocation lifetime across reloads; actualrenderer destruction/reconstruction chain verified; beta3target optional");
        } finally { settings.setVertexFormat(original); }
    }

    private static Object construct(Class<?> type) throws Exception {
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        // Constructor only records the staging buffer and creates CPU-side DataTypes.
        return constructor.newInstance(new Object[]{null});
    }
    private static Object resolve(Object allocator, int stride) throws Exception {
        var method = allocator.getClass().getDeclaredMethod("getDataTypeForStride", int.class);
        method.setAccessible(true); return method.invoke(allocator, stride);
    }
    private static int stride(Object dataType) throws Exception {
        var field = dataType.getClass().getSuperclass().getDeclaredField("stride");
        field.setAccessible(true); return field.getInt(dataType);
    }
    private static void reject(Object allocator, int stride) throws Exception {
        try { resolve(allocator, stride); throw new AssertionError("Unexpectedly accepted stride " + stride); }
        catch (InvocationTargetException failure) {
            require(failure.getCause() instanceof IllegalArgumentException && failure.getCause().getMessage().equals("Unsupported stride: " + stride),
                "The real resolver must retain its explicit unsupported-stride failure");
        }
    }
    private static void verifyOptionalTarget() throws Exception {
        ClassNode node = new ClassNode();
        try (var input = IrisSodiumArenaAllocatorTest.class.getClassLoader().getResourceAsStream("net/irisshaders/iris/compat/sodium/mixin/MixinArenaAggregator.class")) {
            new ClassReader(input).accept(node, 0);
        }
        var annotations = new java.util.ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
        require(annotations.stream().anyMatch(a -> a.desc.endsWith("/Pseudo;")), "Absent beta3 target must be optional");
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(CALLBACK.getName())).findFirst().orElseThrow();
        var all = new java.util.ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        AnnotationNode redirect = all.stream().filter(a -> a.desc.endsWith("/Redirect;")).findFirst().orElseThrow();
        require(value(redirect, "method").equals(List.of("<init>")), "Redirect targets actual allocator initialization");
        AnnotationNode at = (AnnotationNode) value(redirect, "at");
        require("FIELD".equals(value(at, "value")) && ("L" + FORMAT + ";COMPACT:" + TYPE).equals(value(at, "target")), "Redirect targets the actual registration field access");
    }

    private static void verifyReloadPath(JarFile release) throws Exception {
        String sodium = "net/caffeinemc/mods/sodium/client/";
        require(call(method(read(null, "net/irisshaders/iris/pipeline/PipelineManager"), "preparePipeline"),
            "net/minecraft/client/renderer/extract/LevelExtractor", "allChanged") >= 0, "Pipeline changes request extractor invalidation");
        require(call(method(read(null, "net/irisshaders/iris/pipeline/NativeVulkanWorldRenderingPipeline"), "beginLevelRendering"),
            "net/minecraft/client/renderer/extract/LevelExtractor", "allChanged") >= 0, "Cached native pipelines also honor vertex-format invalidation");
        ClassNode extractor = read(null, "net/minecraft/client/renderer/extract/LevelExtractor");
        require(extractor.methods.stream().anyMatch(m -> call(m, "net/minecraft/client/renderer/LevelRenderer", "invalidateCompiledGeometry") >= 0),
            "Extractor invalidation reaches actual renderer geometry rebuild");
        ClassNode hook = read(release, "net/caffeinemc/mods/sodium/mixin/core/render/world/LevelRendererMixin");
        MethodNode rebuild = hook.methods.stream().filter(m -> call(m, sodium + "render/SodiumWorldRenderer", "reload") >= 0).findFirst().orElseThrow();
        var annotations = new java.util.ArrayList<AnnotationNode>();
        if (rebuild.visibleAnnotations != null) annotations.addAll(rebuild.visibleAnnotations);
        if (rebuild.invisibleAnnotations != null) annotations.addAll(rebuild.invisibleAnnotations);
        require(annotations.stream().anyMatch(a -> value(a, "method") instanceof List<?> names && names.contains("invalidateCompiledGeometry")),
            "Sodium handles that exact invalidation method");
        ClassNode renderer = read(release, sodium + "render/SodiumWorldRenderer");
        require(call(method(renderer, "reload"), renderer.name, "initRenderer") >= 0, "Sodium reload reinitializes renderer");
        MethodNode initialize = method(renderer, "initRenderer");
        int destroy = call(initialize, renderer.name, "deleteRendererState");
        int recreate = call(initialize, sodium + "render/chunk/RenderSectionManager", "<init>");
        require(destroy >= 0 && recreate > destroy, "Destroy old renderer before constructing new section manager");
        require(call(method(renderer, "deleteRendererState"), sodium + "render/chunk/RenderSectionManager", "destroy") >= 0, "Old section manager is destroyed");
        ClassNode sections = read(release, sodium + "render/chunk/RenderSectionManager");
        require(call(method(sections, "destroy"), sodium + "render/chunk/region/RenderRegionManager", "delete") >= 0, "Destroy section manager releases regions");
        require(call(method(sections, "<init>"), sodium + "render/chunk/region/RenderRegionManager", "<init>") >= 0, "New section manager builds new region manager");
        ClassNode regions = read(release, sodium + "render/chunk/region/RenderRegionManager");
        require(call(method(regions, "delete"), AGGREGATOR.replace('.', '/'), "delete") >= 0, "Old region manager deletes its aggregator");
        require(call(method(regions, "<init>"), AGGREGATOR.replace('.', '/'), "<init>") >= 0, "New region manager constructs a fresh stride-specific aggregator");
    }

    private static ClassNode read(JarFile jar, String name) throws Exception {
        try (var input = jar == null ? IrisSodiumArenaAllocatorTest.class.getClassLoader().getResourceAsStream(name + ".class")
                : jar.getInputStream(jar.getJarEntry(name + ".class"))) {
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static int call(MethodNode method, String owner, String name) {
        for (int i = 0; i < method.instructions.size(); i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return i;
        return -1;
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; annotation.values != null && i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
    private static final class ArenaLoader extends ClassLoader {
        private final JarFile jar;
        private final boolean patch;
        ArenaLoader(JarFile jar, boolean patch) { super(IrisSodiumArenaAllocatorTest.class.getClassLoader()); this.jar = jar; this.patch = patch; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(AGGREGATOR) && !name.startsWith(AGGREGATOR + "$")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> result = findLoadedClass(name);
                if (result == null) {
                    try (var input = jar.getInputStream(jar.getJarEntry(name.replace('.', '/') + ".class"))) {
                        byte[] bytes = input.readAllBytes();
                        if (patch && name.equals(AGGREGATOR)) bytes = patchConstructor(bytes);
                        result = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
                }
                if (resolve) resolveClass(result);
                return result;
            }
        }
        private byte[] patchConstructor(byte[] bytes) {
            ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); int matches = 0;
            for (MethodNode method : node.methods) if (method.name.equals("<init>"))
                for (var instruction : method.instructions.toArray())
                    if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(FORMAT) && field.name.equals("COMPACT") && field.desc.equals(TYPE)) {
                        method.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "net/irisshaders/iris/vulkan/IrisSodiumArenaAllocatorTest", "invokeProductionRedirect", "()" + TYPE, false));
                        matches++;
                    }
            require(matches == 1, "Exactly one geometry datatype registration must change");
            ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
