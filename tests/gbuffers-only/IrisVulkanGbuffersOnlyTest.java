package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import sun.misc.Unsafe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Replays actual compiled presentation decisions, replacing only GPU/Minecraft endpoints. */
public final class IrisVulkanGbuffersOnlyTest {
    private static final String VULKAN = "net/irisshaders/iris/vulkan/";
    private static int checks;
    private static Path replayArchive;
    public static final List<String> events = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        ProgramSet programs = readPack(Path.of(args[0]));
        var empty = IrisVulkanScreenPassPlanner.create(programs);
        check(empty.nodes().isEmpty(), "Actual gbuffers-only pack must produce no synthetic pack postpasses");
        check(!empty.hasRunnablePasses() && !empty.hasReadyFinalPass(), "Graph correctly distinguishes absent shader passes");

        // A successful renderer's frame/presentation lifecycle must remain available without shaders here.
        var readyType = replay(VULKAN + "IrisVulkanFinalPassRenderer", Readiness.class, List.of("hasRunnablePasses"));
        var ready = (Readiness) readyType.getConstructor().newInstance();
        ready.graph = empty;
        Flags.world = true;
        check((boolean) readyType.getMethod("hasRunnablePasses").invoke(ready), "World renderer must retain an empty postprocess graph");
        Flags.world = false;
        check(!(boolean) readyType.getMethod("hasRunnablePasses").invoke(ready), "Non-world empty diagnostics remain inactive");

        var executorType = replay(VULKAN + "IrisVulkanScreenPassExecutor", Executor.class, List.of("render"));
        var executor = (Executor) executorType.getConstructor().newInstance();
        Flags.world = true;
        executor.graph = empty;
        render(executorType, executor);
        check(events.equals(List.of("allocate", "copy:0", "finish")), "Empty native world must present colortex0 exactly once: " + events);
        executor.deferredRendered = true;
        render(executorType, executor);
        check(events.equals(List.of("allocate", "copy:0", "finish")), "Previously completed deferred phase cannot suppress presentation: " + events);

        executor.graph = graph(node(IrisVulkanScreenPassGraph.Kind.PREPARE, "prepare/0/prepare"), null);
        render(executorType, executor);
        check(events.contains("copy:0"), "Prepare-only world must still present colortex0");
        executor.graph = graph(node(IrisVulkanScreenPassGraph.Kind.DEFERRED, "deferred/0/deferred"), null);
        executor.deferredRendered = true;
        render(executorType, executor);
        check(events.contains("copy:0"), "Deferred already executed before translucents must still present colortex0");
        executor.graph = graph(node(IrisVulkanScreenPassGraph.Kind.COMPOSITE, "composite/0/composite"), null);
        render(executorType, executor);
        check(events.contains("copy:0"), "Composite-only pack keeps no-final fallback");

        executor.graph = graph(null, node(IrisVulkanScreenPassGraph.Kind.FINAL, "final/final"));
        render(executorType, executor);
        check(events.contains("final") && !events.contains("copy:0"), "Declared final output must not be overwritten by a fallback");
        executor.graph = graph(null, new IrisVulkanScreenPassGraph.Node(IrisVulkanScreenPassGraph.Kind.FINAL, "bad-final", "final",
                null, null, null, null, null, true, null, "", "", IrisVulkanScreenPassGraph.Status.SKIPPED, "unsupported fixture"));
        render(executorType, executor);
        check(!events.contains("copy:0"), "Unavailable declared final is not silently replaced by absent-final fallback");

        executor.graph = empty;
        executor.drawMode = IrisNativeVulkan.ScreenPassDrawMode.OFF;
        render(executorType, executor);
        check(!events.contains("copy:0") && events.contains("finish"), "Explicitly disabled drawing never presents");
        executor.drawMode = IrisNativeVulkan.ScreenPassDrawMode.SHADERPACK;
        executor.mode = IrisNativeVulkan.ScreenPassMode.BUILD_ONLY;
        render(executorType, executor);
        check(!events.contains("copy:0") && events.contains("finish"), "Build-only mode never presents");
        executor.mode = IrisNativeVulkan.ScreenPassMode.ALL;
        Flags.world = false;
        render(executorType, executor);
        check(events.equals(List.of("snapshot", "finish")), "Empty non-world graph retains no-draw behavior: " + events);
        Flags.world = true;

        var worldType = replay("net/irisshaders/iris/pipeline/NativeVulkanWorldRenderingPipeline", World.class,
                List.of("usesShadowMaps", "renderShadows"));
        var world = (World) worldType.getConstructor().newInstance();
        world.programSet = programs; world.directives = programs.getPackDirectives();
        check(!(boolean) worldType.getMethod("usesShadowMaps").invoke(world), "Absent shadow shader must not require a shadow renderer");
        events.clear();
        worldType.getMethod("renderShadows", net.irisshaders.iris.mixin.LevelRendererAccessor.class,
                net.minecraft.client.Camera.class, net.minecraft.client.renderer.state.level.CameraRenderState.class)
                .invoke(world, null, null, null);
        check(events.equals(List.of("prepare")) && !world.shadowsRendered, "No-shadow world still advances prepare exactly once");
        empty.destroy(); empty.destroy();
        if (args.length > 1) checkPreviousRelease(Path.of(args[1]), empty);
        System.out.println("IRIS_GBUFFERS_ONLY_PASS: " + checks + " checks; actual pack/planner, compiled presentation branches/readiness and no-shadow lifecycle; CPU endpoints only");
    }

    private static void checkPreviousRelease(Path archive, IrisVulkanScreenPassGraph empty) throws Exception {
        replayArchive = archive;
        try {
            Flags.world = true;
            var readyType = replay(VULKAN + "IrisVulkanFinalPassRenderer", Readiness.class, List.of("hasRunnablePasses"));
            var ready = (Readiness) readyType.getConstructor().newInstance(); ready.graph = empty;
            check(!(boolean) readyType.getMethod("hasRunnablePasses").invoke(ready), "Previous release must reproduce empty-world renderer rejection");
            var type = replay(VULKAN + "IrisVulkanScreenPassExecutor", Executor.class, List.of("render"));
            var executor = (Executor) type.getConstructor().newInstance(); executor.graph = empty;
            render(type, executor);
            check(events.equals(List.of("finish")), "Previous release must reproduce empty-graph early exit: " + events);
            executor.graph = graph(node(IrisVulkanScreenPassGraph.Kind.DEFERRED, "deferred/0/deferred"), null);
            executor.deferredRendered = true;
            render(type, executor);
            check(events.equals(List.of("allocate", "finish")), "Previous release must reproduce missing presentation after earlier deferred draw: " + events);
            System.out.println("Previous release negative control SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(archive))));
        } finally { replayArchive = null; }
    }

    private static ProgramSet readPack(Path path) throws Exception {
        Map<String, String> sources = new HashMap<>();
        try (var zip = new ZipFile(path.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList())
                sources.put("/" + entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
        }
        check(sources.keySet().stream().anyMatch(p -> p.endsWith("gbuffers_terrain.fsh")), "Expected actual mineek terrain shader");
        var options = new ShaderPackOptions(new IncludeGraph(Path.of("."), ImmutableList.of(), false), Map.of());
        var properties = new ShaderProperties(sources.getOrDefault("/shaders/shaders.properties", ""), options, List.of());
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
        var pack = (ShaderPack) ((Unsafe) unsafeField.get(null)).allocateInstance(ShaderPack.class);
        var features = ShaderPack.class.getDeclaredField("activeFeatures"); features.setAccessible(true); features.set(pack, Set.of());
        var programs = new ProgramSet(AbsolutePackPath.fromAbsolutePath("/shaders"), p -> sources.get(p.toString()), properties, pack);
        check(programs.get(ProgramId.Final).isEmpty() && programs.get(ProgramId.Shadow).isEmpty(), "Real program parser confirms absent final and shadow");
        System.out.println("Pack SHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path))));
        return programs;
    }

    private static IrisVulkanScreenPassGraph.Node node(IrisVulkanScreenPassGraph.Kind kind, String label) {
        return new IrisVulkanScreenPassGraph.Node(kind, label, label, new int[]{0}, List.of(), Map.of(), Set.of(), null,
                kind == IrisVulkanScreenPassGraph.Kind.FINAL, new IrisVulkanScreenPassGraph.PipelineHandle(() -> { throw new AssertionError("GPU pipeline construction forbidden"); }),
                "", "", IrisVulkanScreenPassGraph.Status.READY, "");
    }
    private static IrisVulkanScreenPassGraph graph(IrisVulkanScreenPassGraph.Node stage, IrisVulkanScreenPassGraph.Node last) {
        return new IrisVulkanScreenPassGraph(List.of(), stage != null && stage.kind() == IrisVulkanScreenPassGraph.Kind.PREPARE ? List.of(stage) : List.of(),
                stage != null && stage.kind() == IrisVulkanScreenPassGraph.Kind.DEFERRED ? List.of(stage) : List.of(),
                stage != null && stage.kind() == IrisVulkanScreenPassGraph.Kind.COMPOSITE ? List.of(stage) : List.of(), last);
    }
    private static void render(Class<?> type, Executor executor) throws Exception { events.clear(); type.getMethod("render").invoke(executor); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }

    private static Class<?> replay(String source, Class<?> base, List<String> methods) throws Exception {
        String generated = Type.getInternalName(base) + "Replay";
        Map<String, String> mapping = new HashMap<>();
        mapping.put(source, generated);
        mapping.put(VULKAN + "IrisNativeVulkan", Type.getInternalName(Flags.class));
        mapping.put(VULKAN + "IrisVulkanGbufferTargets", Type.getInternalName(Targets.class));
        mapping.put(VULKAN + "IrisVulkanUniformSnapshot", Type.getInternalName(Snapshot.class));
        mapping.put(VULKAN + "IrisVulkanScreenPassExecutor$LogicalStageResult", Type.getInternalName(Logical.class));
        mapping.put("net/minecraft/client/Minecraft", Type.getInternalName(Client.class));
        mapping.put("net/minecraft/client/renderer/GameRenderer", Type.getInternalName(Renderer.class));
        mapping.put("com/mojang/blaze3d/pipeline/RenderTarget", Type.getInternalName(Target.class));
        mapping.put("com/mojang/blaze3d/textures/GpuTexture", Type.getInternalName(Texture.class));
        mapping.put("com/mojang/blaze3d/textures/GpuTextureView", Type.getInternalName(View.class));
        mapping.put("com/mojang/blaze3d/systems/RenderSystem", Type.getInternalName(SystemSink.class));
        mapping.put("com/mojang/blaze3d/systems/RenderSystem$AutoStorageIndexBuffer", Type.getInternalName(Indices.class));
        mapping.put("com/mojang/blaze3d/systems/GpuDevice", Type.getInternalName(Device.class));
        mapping.put("com/mojang/blaze3d/systems/CommandEncoder", Type.getInternalName(Encoder.class));
        mapping.put("com/mojang/blaze3d/buffers/GpuBuffer", Type.getInternalName(Buffer.class));
        Remapper remapper = new Remapper() { @Override public String map(String name) { return mapping.getOrDefault(name, name); } };
        var node = new ClassNode();
        try (var archive = replayArchive == null ? null : new ZipFile(replayArchive.toFile());
             var input = archive == null ? IrisVulkanGbuffersOnlyTest.class.getClassLoader().getResourceAsStream(source + ".class")
                     : archive.getInputStream(archive.getEntry(source + ".class"))) {
            if (input == null) throw new AssertionError("Missing compiled production " + source);
            new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, generated, null, Type.getInternalName(base), null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(base), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        for (String name : methods) {
            MethodNode method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            method.accept(new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC, name, remapper.mapMethodDesc(method.desc), null, null), remapper));
        }
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(IrisVulkanGbuffersOnlyTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    public static class Readiness { public IrisVulkanScreenPassGraph graph; }
    public static class World {
        public ProgramSet programSet;
        public PackDirectives directives;
        public boolean shadowsRendered;
        public NativeVulkanWorldRenderingPipeline.NativeShadowPass shadowRenderer;
        public NativeVulkanWorldRenderingPipeline.FramePasses framePasses = new NativeVulkanWorldRenderingPipeline.FramePasses() {
            public void beginFrame() {} public void beforeTranslucents() {} public void beforeHand() {}
            public void afterShadows() { events.add("prepare"); } public void finishWorld() {} public void finishGame() {} public void close() {}
        };
        public void checkAlive() {}
    }
    public static class Executor {
        public IrisVulkanScreenPassGraph graph;
        public IrisNativeVulkan.ScreenPassMode mode = IrisNativeVulkan.ScreenPassMode.ALL;
        public IrisNativeVulkan.ScreenPassDrawMode drawMode = IrisNativeVulkan.ScreenPassDrawMode.SHADERPACK;
        public String debugStopAfterPass;
        public boolean deferredRendered, stoppedAfterDeferred, loggedBuildOnlyFrame, loggedDrawDisabledFrame, loggedDebugStop;
        public View finalSourceViewForMode(Target main, Texture texture) { return new View(); }
        public void preflightScreenPasses(Encoder encoder, View depth) { events.add("preflight"); }
        public Logical renderLogicalStage(Encoder encoder, List<IrisVulkanScreenPassGraph.Node> nodes, String flip, View depth, Buffer indices, IndexType type) {
            boolean ready = nodes.stream().anyMatch(IrisVulkanScreenPassGraph.Node::ready);
            if (ready) events.add("logical:" + flip);
            return new Logical(ready, ready, false);
        }
        public boolean hasExecutableFinalPass(View view) { return graph.hasReadyFinalPass(); }
        public boolean renderSelectedFinalPassIfAvailable(Encoder encoder, View depth, View input, View output, Buffer indices, IndexType type) {
            if (graph.hasReadyFinalPass()) events.add("final");
            return graph.hasReadyFinalPass();
        }
        public void copyTargetToMain(Encoder encoder, Texture color, int target) { events.add("copy:" + target); }
        public View stageFinalInput(Encoder encoder, View depth, View source, Buffer indices, IndexType type) { return source; }
    }
    public record Logical(boolean rendered, boolean renderedColor, boolean stopped) {}
    public static final class Flags {
        public static boolean world = true;
        public static boolean worldDevelopmentEnabled() { return world; }
        public static boolean debugOutputDepth() { return false; }
        public static int debugOutputTarget() { return -1; }
    }
    public static final class Snapshot { public static void beginFrame() { events.add("snapshot"); } }
    public static final class Targets {
        public static void finishFrame() { events.add("finish"); }
        public static void ensureForFinalPass(Encoder encoder, Texture texture) { events.add("allocate"); }
        public static View currentView(int target) { return new View(); }
        public static void applyExplicitFlips(String flip) { events.add("flip:" + flip); }
    }
    public static final class Client {
        public final Renderer gameRenderer = new Renderer();
        public static Client getInstance() { return new Client(); }
    }
    public static final class Renderer { public Target mainRenderTarget() { return new Target(); } }
    public static final class Target {
        public Texture getColorTexture() { return new Texture(); }
        public View getDepthTextureView() { return new View(); }
        public View getColorTextureView() { return new View(); }
    }
    public static final class Texture {
        public boolean isClosed() { return false; }
        public int usage() { return com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC; }
    }
    public static final class View { public boolean isClosed() { return false; } }
    public static final class SystemSink {
        public static Device getDevice() { return new Device(); }
        public static Indices getSequentialBuffer(PrimitiveTopology topology) { return new Indices(); }
    }
    public static final class Device { public Encoder createCommandEncoder() { return new Encoder(); } }
    public static final class Indices { public Buffer getBuffer(int count) { return new Buffer(); } public IndexType type() { return IndexType.SHORT; } }
    public static final class Encoder {}
    public static final class Buffer {}
}
