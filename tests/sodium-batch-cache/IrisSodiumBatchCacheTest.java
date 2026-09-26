package net.irisshaders.iris.vulkan;

import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Executes retained mixin methods on real Sodium regions and batch.clear implementations, without GPU allocations. */
public final class IrisSodiumBatchCacheTest {
    private static final String REGION = "net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion";
    private static final String MANAGER = REGION + "Manager";
    private static final String MIXIN = "net/irisshaders/iris/compat/sodium/mixin/MixinRenderRegion";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String UPLOAD = "(L" + REGION + ";Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V";
    private static final Unsafe UNSAFE = unsafe();

    public static void main(String[] args) throws Exception {
        var configuration = JsonParser.parseString(Files.readString(Path.of(args[0]).resolve("common/src/main/resources/mixins.iris.compat.sodium.json"))).getAsJsonObject();
        for (var name : configuration.getAsJsonArray("client")) require(!name.getAsString().equals("MixinRenderRegionManager"), "Removed redundant manager redirect must not remain registered");
        for (int index = 1; index < args.length; index++) try (JarFile jar = new JarFile(args[index])) {
            String version;
            try (var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                version = JsonParser.parseReader(reader).getAsJsonObject().get("version").getAsString();
            }
            ClassNode manager = read(jar.getInputStream(jar.getJarEntry(MANAGER + ".class")));
            MethodNode upload = manager.methods.stream().filter(method -> method.name.equals("uploadResults") && method.desc.equals(UPLOAD)).findFirst().orElseThrow();
            int allCalls = calls(upload, "clearAllCachedBatches"), passCalls = calls(upload, "clearCachedBatchFor");
            require(passCalls > 0, "Actual upload path must still invalidate changed pass batches");
            if (version.startsWith("0.9.2")) require(allCalls == 0, "0.9.2 must reproduce the legacy missing invocation target");
            if (version.contains("beta.3")) require(allCalls == 1, "Beta.3 legacy invocation must be present");
            RegionLoader loader = new RegionLoader(jar);
            Class<?> regionType = loader.loadClass(REGION.replace('/', '.'));
            Object region = UNSAFE.allocateInstance(regionType);
            Class<?> passType = loader.loadClass("net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass");
            Object passA = UNSAFE.allocateInstance(passType), passB = UNSAFE.allocateInstance(passType);
            Class<?> batchType = loader.loadClass("net.caffeinemc.mods.sodium.client.gpu.device.batch.GLDrawBatch");
            Object regularA = UNSAFE.allocateInstance(batchType), regularB = UNSAFE.allocateInstance(batchType);
            Object shadowA = UNSAFE.allocateInstance(batchType), shadowB = UNSAFE.allocateInstance(batchType);
            Object[] batches = {regularA, regularB, shadowA, shadowB};
            Map<Object, Object> regular = new HashMap<>(Map.of(passA, regularA, passB, regularB));
            Map<Object, Object> shadow = new HashMap<>(Map.of(passA, shadowA, passB, shadowB));
            field(regionType, "cachedBatches").set(region, regular);
            field(regionType, "shadowCachedBatches").set(region, shadow);
            Class<?> listType = loader.loadClass("net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList");
            field(regionType, "renderList").set(region, UNSAFE.allocateInstance(listType));
            field(regionType, "shadowRenderList").set(region, UNSAFE.allocateInstance(listType));

            seed(batches);
            regionType.getMethod("clearAllCachedBatches").invoke(region);
            requireCleared(batches); // Region HEAD hook + unchanged vanilla method, no manager redirect.
            seed(batches);
            regionType.getMethod("swapToShadowRenderList").invoke(region);
            regionType.getMethod("swapToShadowRenderList").invoke(region); // scoped swap is idempotent.
            regionType.getMethod("clearAllCachedBatches").invoke(region);
            requireCleared(batches);
            regionType.getMethod("swapToRegularRenderList").invoke(region);
            require(field(regionType, "cachedBatches").get(region) == regular, "Regular cache identity restored");

            seed(batches);
            regionType.getMethod("clearCachedBatchFor", passType).invoke(region, passA);
            requireCleared(regularA, shadowA, shadowB);
            require(!cleared(regularB), "Vanilla per-pass invalidation preserves unrelated active batches");
            regionType.getMethod("swapToShadowRenderList").invoke(region);
            seed(batches);
            regionType.getMethod("clearCachedBatchFor", passType).invoke(region, passA);
            requireCleared(shadowA, regularA, regularB);
            require(!cleared(shadowB), "Shadow-active per-pass invalidation preserves unrelated active batches");
            regionType.getMethod("swapToRegularRenderList").invoke(region);
            regionType.getMethod("clearAllCachedBatches").invoke(region);
            requireCleared(batches);
            // Actual final clear implementation is safe when the retained HEAD hook
            // and vanilla body both clear the active cache.
            batchType.getMethod("clear").invoke(regularA);
            requireCleared(regularA);
            System.out.println("IRIS_REGION_BATCH_CACHE_PASS " + version + " uploadDescriptorPresent=true clearAllCalls=" + allCalls
                    + " clearPassCalls=" + passCalls + " retained region hooks clear both cache scopes; original batch clear is idempotent");
        }
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(REGION) && call.name.equals(name)) count++;
        return count;
    }
    private static void seed(Object... batches) throws Exception {
        for (Object batch : batches) {
            Class<?> base = batch.getClass().getSuperclass();
            field(base, "size").setInt(batch, 7); field(base, "isFilled").setBoolean(batch, true); field(base, "maxElementCount").setInt(batch, 23);
        }
    }
    private static boolean cleared(Object batch) throws Exception {
        Class<?> base = batch.getClass().getSuperclass();
        return field(base, "size").getInt(batch) == 0 && !field(base, "isFilled").getBoolean(batch) && field(base, "maxElementCount").getInt(batch) == 0;
    }
    private static void requireCleared(Object... batches) throws Exception { for (Object batch : batches) require(cleared(batch), "Stale regular/shadow draw commands remain"); }
    private static Field field(Class<?> type, String name) throws Exception { var field = type.getDeclaredField(name); field.setAccessible(true); return field; }
    private static Unsafe unsafe() { try { return (Unsafe) field(Unsafe.class, "theUnsafe").get(null); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static ClassNode read(InputStream input) throws Exception { try (input) { ClassNode result = new ClassNode(); new ClassReader(input).accept(result, 0); return result; } }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        return null;
    }

    /** Minimal method/field merge of the retained mixin, avoiding any game/Mixin bootstrap. */
    private static final class RegionLoader extends ClassLoader {
        private final JarFile jar;
        RegionLoader(JarFile jar) { super(IrisSodiumBatchCacheTest.class.getClassLoader()); this.jar = jar; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("net.caffeinemc.mods.sodium.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) try {
                    String path = name.replace('.', '/') + ".class";
                    var entry = jar.getJarEntry(path);
                    if (entry == null) throw new ClassNotFoundException(name);
                    byte[] bytes;
                    try (var input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                    if (path.equals(REGION + ".class")) bytes = merge(bytes);
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (Exception failure) { throw new ClassNotFoundException(name, failure); }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
        private byte[] merge(byte[] bytes) throws Exception {
            ClassNode target = new ClassNode(); new ClassReader(bytes).accept(target, 0);
            ClassNode source = read(getParent().getResourceAsStream(MIXIN + ".class"));
            ClassNode remapped = new ClassNode(); source.accept(new ClassRemapper(remapped, new SimpleRemapper(Map.of(MIXIN, REGION))));
            target.interfaces.add("net/irisshaders/iris/mixinterface/ShadowRenderRegion");
            for (FieldNode addition : remapped.fields) {
                var existing = target.fields.stream().filter(value -> value.name.equals(addition.name)).findFirst();
                if (existing.isPresent()) { require(existing.get().desc.equals(addition.desc), "Shadow field descriptor changed: " + addition.name); existing.get().access &= ~Opcodes.ACC_FINAL; }
                else target.fields.add(addition);
            }
            for (MethodNode addition : remapped.methods) if (!addition.name.equals("<init>")) target.methods.add(addition);
            for (String[] entry : new String[][]{{"clearAllCachedBatches", "iris$clearAllBatches"}, {"clearCachedBatchFor", "iris$clearBatchFor"}}) {
                MethodNode handler = remapped.methods.stream().filter(method -> method.name.equals(entry[1])).findFirst().orElseThrow();
                AnnotationNode injection = handler.visibleAnnotations.stream().filter(annotation -> annotation.desc.endsWith("/Inject;")).findFirst().orElseThrow();
                require(((List<?>) value(injection, "method")).contains(entry[0]), "Retained injection targets the real region method");
                Object atValue = value(injection, "at");
                AnnotationNode at = (AnnotationNode) (atValue instanceof List<?> points ? points.getFirst() : atValue);
                require("HEAD".equals(value(at, "value")), "Retained region invalidation must run before vanilla clearing");
                MethodNode method = target.methods.stream().filter(value -> value.name.equals(entry[0])).findFirst().orElseThrow();
                InsnList callback = new InsnList(); callback.add(new VarInsnNode(Opcodes.ALOAD, 0)); callback.add(new InsnNode(Opcodes.ACONST_NULL));
                callback.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, REGION, entry[1], "(" + CALLBACK + ")V", false));
                method.instructions.insert(callback);
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); target.accept(writer); return writer.toByteArray();
        }
    }
}
