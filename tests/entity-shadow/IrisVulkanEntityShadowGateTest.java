package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.IrisMixinPlugin;
import net.irisshaders.iris.mixin.MixinEntityRenderDispatcher;
import net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import sun.misc.Unsafe;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Executes real native shadow lifecycle and real submission suppression condition without a GPU. */
public final class IrisVulkanEntityShadowGateTest {
    private static Unsafe unsafe;
    private static Method submitCondition;
    private static int checks, shadowRenders, prepareCalls;

    public static void main(String[] args) throws Exception {
        var access = Unsafe.class.getDeclaredField("theUnsafe"); access.setAccessible(true); unsafe = (Unsafe) access.get(null);
        var directory = Files.createTempDirectory("iris-shadow-gate-");
        System.setProperty("iris.test.gameDir", directory.toString());
        try {
            var plugin = new IrisMixinPlugin();
            for (boolean vulkan : new boolean[]{false, true}) {
                IrisMixinPlugin.usingVulkan = vulkan;
                check(plugin.shouldApplyMixin("net.minecraft.client.renderer.entity.EntityRenderDispatcher", MixinEntityRenderDispatcher.class.getName()),
                        "Shared shadow suppression hook must load with Vulkan=" + vulkan);
                check(plugin.shouldApplyMixin("net.minecraft.client.renderer.SubmitNodeStorage", "net.irisshaders.iris.mixin.vulkan.VKOnly_MixinSubmitNodeStorage_Shadow") == vulkan,
                        "Shadow-caster exclusion remains Vulkan-only");
            }
            verifyActualInvocation();
            submitCondition = MixinEntityRenderDispatcher.class.getDeclaredMethod("iris$maybeSuppressEntityShadow",
                    SubmitNodeCollector.class, PoseStack.class, float.class, List.class);
            submitCondition.setAccessible(true);
            Iris.pipelineManager = (PipelineManager) unsafe.allocateInstance(PipelineManager.class);
            install(null); check(allowVanillaShadow(), "No pipeline preserves vanilla shadows");
            install(unsafe.allocateInstance(VanillaRenderingPipeline.class)); check(allowVanillaShadow(), "Vanilla pipeline preserves vanilla shadows");

            ProgramSet mineek = readMineek(Path.of(args[0]));
            check(mineek.get(ProgramId.Shadow).isEmpty(), "Actual Mineek pack must have no shadow program");
            var world = world(mineek);
            install(world);
            check(!world.usesShadowMaps() && allowVanillaShadow(), "Mineek keeps vanilla shadows without a native shadow renderer");
            world.renderShadows(null, null, null);
            check(prepareCalls == 1 && shadowRenders == 0 && allowVanillaShadow(), "No-shadow lifecycle calls prepare and keeps vanilla shadows afterward");
            world.setShadowRenderer(renderer());
            check(allowVanillaShadow(), "Unused installed renderer cannot suppress no-shadow pack decals");

            var withShadows = readMineek(Path.of(args[0]));
            @SuppressWarnings("unchecked") var sources = (Map<ProgramId, ProgramSource>) get(withShadows, "gbufferPrograms");
            sources.put(ProgramId.Shadow, new ProgramSource("shadow", "#version 120\nvoid main(){gl_Position=ftransform();}",
                    null, null, null, "#version 120\nvoid main(){gl_FragColor=vec4(1);}", withShadows, properties(""), null));
            world = world(withShadows); install(world);
            check(world.usesShadowMaps() && allowVanillaShadow(), "A missing renderer does not claim usable pack shadows");
            world.setShadowRenderer(renderer());
            check(!allowVanillaShadow(), "Installed pack shadows suppress vanilla submission BEFORE first shadow render");
            world.renderShadows(null, null, null);
            check(shadowRenders == 1 && !allowVanillaShadow(), "Installed pack shadows suppress submission AFTER rendering");
            set(world, "shadowsRendered", false);
            check(!allowVanillaShadow(), "Next-frame reset cannot re-enable the vanilla decal");
            set(world, "directives", new PackDirectives(PackRenderTargetDirectives.BASELINE_SUPPORTED_RENDER_TARGETS, properties("shadow.enabled=false\n")));
            check(!world.usesShadowMaps() && allowVanillaShadow(), "Explicitly disabled pack shadows preserve vanilla decals");
            System.out.println("IRIS_ENTITY_SHADOW_GATE_PASS: " + checks + " checks; actual plugin/filter, actual26.2 invocation, real hook/pipeline lifecycle, Mineek preservation and early/late pack suppression");
        } finally {
            Files.delete(directory);
            System.clearProperty("iris.test.gameDir");
        }
    }

    private static NativeVulkanWorldRenderingPipeline world(ProgramSet programs) throws Exception {
        var world = (NativeVulkanWorldRenderingPipeline) unsafe.allocateInstance(NativeVulkanWorldRenderingPipeline.class);
        set(world, "programSet", programs); set(world, "directives", programs.getPackDirectives());
        set(world, "framePasses", new NativeVulkanWorldRenderingPipeline.FramePasses() {
            public void beginFrame() {} public void beforeHand() {} public void beforeTranslucents() {}
            public void afterShadows() { prepareCalls++; } public void finishWorld() {} public void finishGame() {} public void close() {}
        });
        return world;
    }
    private static NativeVulkanWorldRenderingPipeline.NativeShadowPass renderer() {
        return new NativeVulkanWorldRenderingPipeline.NativeShadowPass() {
            public void render(net.irisshaders.iris.mixin.LevelRendererAccessor level, net.minecraft.client.Camera camera,
                               net.minecraft.client.renderer.state.level.CameraRenderState state) { shadowRenders++; }
            public void close() {}
        };
    }
    private static ShaderProperties properties(String text) {
        return new ShaderProperties(text, new ShaderPackOptions(new IncludeGraph(Path.of("."), ImmutableList.of(), false), Map.of()), List.of());
    }
    private static ProgramSet readMineek(Path path) throws Exception {
        var contents = new HashMap<String, String>();
        try (var zip = new ZipFile(path.toFile())) {
            for (var entry : zip.stream().filter(e -> !e.isDirectory()).toList())
                contents.put("/" + entry.getName(), new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
        }
        var pack = (ShaderPack) unsafe.allocateInstance(ShaderPack.class); set(pack, "activeFeatures", Set.of());
        return new ProgramSet(AbsolutePackPath.fromAbsolutePath("/shaders"), p -> contents.get(p.toString()),
                properties(contents.getOrDefault("/shaders/shaders.properties", "")), pack);
    }
    private static void install(Object pipeline) throws Exception { set(Iris.pipelineManager, "pipeline", pipeline); }
    private static boolean allowVanillaShadow() throws Exception { return (boolean) submitCondition.invoke(null, null, null, 0.5f, List.of()); }
    private static Object get(Object instance, String name) throws Exception { var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
    private static void set(Object instance, String name, Object value) throws Exception { var field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value); }
    private static void verifyActualInvocation() throws Exception {
        var node = new ClassNode();
        try (var stream = IrisVulkanEntityShadowGateTest.class.getClassLoader().getResourceAsStream("net/minecraft/client/renderer/entity/EntityRenderDispatcher.class")) {
            new ClassReader(stream).accept(node, 0);
        }
        long count = node.methods.stream().filter(m -> m.name.equals("submit")).flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(i -> i instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/renderer/SubmitNodeCollector")
                        && call.name.equals("submitShadow") && call.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V")).count();
        check(count == 1, "Required suppression target must match the actual26.2 entity submission invocation");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
