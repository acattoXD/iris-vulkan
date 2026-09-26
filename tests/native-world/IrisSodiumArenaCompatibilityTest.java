package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionArenas;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/** Checks the grouped arena-stride redirects against both publisher JARs. */
public final class IrisSodiumArenaCompatibilityTest {
    public static void main(String[] args) throws Exception {
        ClassNode mixin = readMixin();
        var hooks = mixin.methods.stream().filter(m -> m.name.equals("iris$useExtendedStride") || m.name.equals("iris$useCurrentExtendedStride")).toList();
        require(hooks.size() == 2, "Both version-specific alternatives are present");
        for (MethodNode hook : hooks) {
            AnnotationNode group = annotation(hook, "/Group;");
            require("iris$arenaVertexType".equals(value(group, "name")) && Integer.valueOf(1).equals(value(group, "min")) && Integer.valueOf(1).equals(value(group, "max")),
                "Both alternatives require exactly one successful injection as a group");
            require(Integer.valueOf(0).equals(value(annotation(hook, "/Redirect;"), "require")), "Individual optional alternative does not defeat group selection");
        }
        for (String file : args) {
            int fieldMatches = 0, invocationMatches = 0;
            try (JarFile jar = new JarFile(file)) {
                var entry = jar.getJarEntry("net/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion$DeviceResources.class");
                ClassNode target = new ClassNode();
                try (var input = jar.getInputStream(entry)) { new ClassReader(input).accept(target, 0); }
                for (MethodNode constructor : target.methods) if (constructor.name.equals("<init>"))
                    for (var instruction : constructor.instructions) {
                        if (instruction instanceof FieldInsnNode field && field.owner.equals("net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats") && field.name.equals("COMPACT")
                                && field.desc.equals("Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;")) fieldMatches++;
                        if (instruction instanceof MethodInsnNode call && call.owner.equals("net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats") && call.name.equals("getCurrent")
                                && call.desc.equals("()Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;")) invocationMatches++;
                    }
            }
            require(fieldMatches + invocationMatches == 1, "Exactly one arena stride source in " + file);
            System.out.println(Path.of(file).getFileName() + ": FIELD=" + fieldMatches + ", INVOKE=" + invocationMatches);
        }
        var settings = WorldRenderingSettings.INSTANCE;
        var original = settings.getVertexFormat();
        try {
            var extended = FormatAnalyzer.createFormat(true, true, true, true);
            require(extended.getVertexFormat().getVertexSize() == 36, "Full Iris vertex ABI has 36-byte stride");
            var instance = new MixinRenderRegionArenas();
            for (ChunkVertexType expected : List.of(extended, ChunkMeshFormats.COMPACT)) {
                settings.setVertexFormat(expected);
                for (MethodNode hook : hooks) {
                    var method = MixinRenderRegionArenas.class.getDeclaredMethod(hook.name);
                    method.setAccessible(true);
                    require(method.invoke(instance) == expected, "Both handlers obtain the current shader/vanilla vertex type, without cached stride");
                }
            }
        } finally { settings.setVertexFormat(original); }
        System.out.println("IRIS_SODIUM_ARENA_COMPAT_PASS: grouped exact-one target and live extended/compact vertex type on both Sodium versions");
    }

    private static ClassNode readMixin() throws Exception {
        try (var input = IrisSodiumArenaCompatibilityTest.class.getClassLoader().getResourceAsStream("net/irisshaders/iris/compat/sodium/mixin/MixinRenderRegionArenas.class")) {
            ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static AnnotationNode annotation(MethodNode method, String suffix) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
        return all.stream().filter(a -> a.desc.endsWith(suffix)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode node, String key) {
        if (node.values != null) for (int i = 0; i < node.values.size(); i += 2)
            if (node.values.get(i).equals(key)) return node.values.get(i + 1);
        return null;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
