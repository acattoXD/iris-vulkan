package net.irisshaders.iris.vulkan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Execute compiled scheduling/feature selection with only engine effects replaced. */
public final class IrisVulkanHandScheduleTest {
    private static final String PIPELINE = "net/irisshaders/iris/pipeline/NativeVulkanWorldRenderingPipeline";
    private static final String MIXIN = "net/irisshaders/iris/mixin/vulkan/VKOnly_MixinGameRenderer_WorldPasses";
    private static final String SELF = IrisVulkanHandScheduleTest.class.getName().replace('.', '/');
    private static final List<String> events = new ArrayList<>();
    private static FakePipeline currentPipeline;
    private static final Matrix4fStack matrixStack = new Matrix4fStack(8);
    private static Matrix4fc handProjection;
    private static WorldRenderingPhase phase = WorldRenderingPhase.NONE;

    public static void main(String[] args) throws Exception {
        Class<?> schedule = isolate(PIPELINE, "Schedule", Set.of("beginHand", "beginTranslucents", "checkAlive"),
            Set.of("renderingWorld", "beforeTranslucents", "handStarted", "destroyed", "framePasses"),
            Map.of(PIPELINE + "$FramePasses", SELF + "$Passes"));
        Object world = schedule.getConstructor().newInstance();
        set(world, "renderingWorld", true);
        set(world, "beforeTranslucents", true);
        set(world, "framePasses", new Passes() {
            public void beforeHand() { events.add("prehandDepth"); }
            public void renderSolidHand() { events.add("solidHand"); }
            public void beforeTranslucents() { events.add("deferred"); }
        });
        schedule.getMethod("beginTranslucents").invoke(world);
        schedule.getMethod("beginTranslucents").invoke(world);
        schedule.getMethod("beginHand").invoke(world); // Later vanilla 3D HUD boundary.
        check(events.equals(List.of("prehandDepth", "solidHand", "deferred")), "Solid hand and prehand depth run once, before deferred: " + events);
        check(!(boolean) get(world, "beforeTranslucents"), "The late hand sees the post-deferred stage");
        set(world, "renderingWorld", false);
        set(world, "beforeTranslucents", true);
        schedule.getMethod("beginTranslucents").invoke(world);
        check(events.size() == 3, "No hand stages outside world rendering");

        Map<String, String> types = Map.of(
            PIPELINE, SELF + "$FakePipeline",
            "net/irisshaders/iris/vulkan/IrisVulkanPhaseContext", SELF,
            "net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame", SELF + "$Frame",
            "com/mojang/renderpearl/api/commands/RenderPass", "java/lang/Object",
            "com/llamalad7/mixinextras/injector/wrapoperation/Operation", SELF + "$Operation");
        Class<?> split = isolate(MIXIN, "Split", Set.of("iris$splitHandFeatures"), Set.of("iris$earlyHand"), types);
        Object handler = split.getConstructor().newInstance();
        var execute = split.getMethod("iris$splitHandFeatures", Object.class, Frame.class, Operation.class);
        Operation vanilla = arguments -> { events.add("vanilla"); return null; };
        currentPipeline = new FakePipeline();
        events.clear();
        set(handler, "iris$earlyHand", true);
        execute.invoke(handler, new Object(), new Frame(), vanilla);
        check(events.equals(List.of("solid")), "Early hand executes only solid features: " + events);
        events.clear();
        set(handler, "iris$earlyHand", false);
        execute.invoke(handler, new Object(), new Frame(), vanilla);
        check(events.equals(List.of("translucent", "afterTerrain", "seeThrough", "onTop")), "Late hand must not draw solids twice: " + events);
        events.clear();
        currentPipeline.rendering = false;
        execute.invoke(handler, new Object(), new Frame(), vanilla);
        currentPipeline = null;
        execute.invoke(handler, new Object(), new Frame(), vanilla);
        check(events.equals(List.of("vanilla", "vanilla")), "Disabled native shaders preserve vanilla rendering");
        checkMatrixScopes();
        System.out.println("PASS: compiled production lifecycle draws solid hand once before deferred, splits late phases, refreshes late depth0, and restores hand/model-view/phase scopes after drawing failures");
    }

    private static void checkMatrixScopes() throws Exception {
        var types = new java.util.HashMap<String, String>();
        types.put(PIPELINE, SELF + "$FakePipeline");
        types.put("net/irisshaders/iris/vulkan/IrisVulkanPhaseContext", SELF);
        types.put("net/irisshaders/iris/vulkan/IrisVulkanPhaseContext$Scope", SELF + "$Scope");
        types.put("net/irisshaders/iris/vulkan/IrisVulkanGbufferTargets", SELF);
        types.put("net/minecraft/client/renderer/Projection", SELF + "$Projection");
        types.put("net/minecraft/client/renderer/state/level/CameraRenderState", SELF + "$Camera");
        types.put("net/minecraft/client/renderer/state/level/PlayerRenderState", "java/lang/Object");
        types.put("com/mojang/renderpearl/api/textures/GpuTextureView", "java/lang/Object");
        types.put("com/mojang/blaze3d/systems/RenderSystem", SELF);
        types.put("com/llamalad7/mixinextras/injector/wrapoperation/Operation", SELF + "$Operation");
        Class<?> scopeClass = isolate(MIXIN, "ScopeHandler",
            Set.of("iris$renderNativeHand", "iris$trackHandMatrixPush", "iris$trackHandMatrixPop"),
            Set.of("iris$earlyHand", "iris$handMatrixPushes", "hudProjection"), types);
        Object handler = scopeClass.getConstructor().newInstance();
        set(handler, "hudProjection", new Projection());
        var execute = scopeClass.getMethod("iris$renderNativeHand", Camera.class, Object.class, Object.class, Operation.class);
        var push = scopeClass.getMethod("iris$trackHandMatrixPush", Matrix4fStack.class, Operation.class);
        var pop = scopeClass.getMethod("iris$trackHandMatrixPop", Matrix4fStack.class, Operation.class);
        currentPipeline = new FakePipeline();
        Matrix4f originalMatrix = new Matrix4f().rotateY(0.6f);
        for (boolean early : new boolean[]{true, false}) for (boolean fails : new boolean[]{false, true}) {
            events.clear();
            set(handler, "iris$earlyHand", early);
            matrixStack.clear().set(originalMatrix);
            Operation engine = arguments -> {
                try {
                    check(phase == (early ? WorldRenderingPhase.HAND_SOLID : WorldRenderingPhase.HAND_TRANSLUCENT), "Whole hand scope uses its stage's shader family");
                    push.invoke(handler, matrixStack, (Operation) values -> ((Matrix4fStack) values[0]).pushMatrix());
                    matrixStack.rotateX(0.8f);
                    if (fails) throw new IllegalStateException("injected draw failure");
                    pop.invoke(handler, matrixStack, (Operation) values -> ((Matrix4fStack) values[0]).popMatrix());
                    return null;
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            try {
                execute.invoke(handler, new Camera(), new Object(), new Object(), engine);
                check(!fails, "Injected failure must propagate");
            } catch (InvocationTargetException failure) {
                check(fails && failure.getCause() instanceof IllegalStateException, "Only the injected draw failure is expected");
            }
            check(matrixStack.equals(originalMatrix), "Model-view is restored on normal and exceptional hand exits");
            check((int) get(handler, "iris$handMatrixPushes") == 0 && handProjection == null && phase == WorldRenderingPhase.NONE, "Hand matrix count, projection and rendering phase do not leak");
            check(events.equals(early ? List.of() : List.of("depth0")), "Only late hand refreshes depth0: " + events);
            try { matrixStack.popMatrix(); throw new AssertionError("Model-view push leaked"); }
            catch (IllegalStateException expected) { }
        }
    }

    public interface Passes { void beforeHand(); void renderSolidHand(); void beforeTranslucents(); }
    public interface Operation { Object call(Object... arguments); }
    public static final class FakePipeline {
        public boolean rendering = true;
        public boolean isRenderingWorld() { return rendering; }
    }
    public static FakePipeline pipeline() { return currentPipeline; }
    public static final class Camera { public final Matrix4f viewRotationMatrix = new Matrix4f(); }
    public static final class Projection { public Matrix4f getMatrix(Matrix4f target) { return target.identity(); } }
    public static Matrix4fStack getModelViewStack() { return matrixStack; }
    public static Matrix4fc handProjection() { return handProjection; }
    public static void captureHandMatrices(Matrix4fc projection, Matrix4fc modelView) { handProjection = projection; }
    public static void clearHandMatrices() { handProjection = null; }
    public static void snapshotDepth(int slot) { events.add("depth" + slot); }
    public static Scope enter(WorldRenderingPhase next) { WorldRenderingPhase previous = phase; phase = next; return new Scope(previous); }
    public static final class Scope implements AutoCloseable {
        private final WorldRenderingPhase previous;
        public Scope(WorldRenderingPhase previous) { this.previous = previous; }
        public void close() { phase = previous; }
    }
    public static final class Frame {
        public void executeSolid(Object pass) { events.add("solid"); }
        public void executeTranslucent(Object pass) { events.add("translucent"); }
        public void executeTranslucentAfterTerrain(Object pass) { events.add("afterTerrain"); }
        public void executeSeeThrough(Object pass) { events.add("seeThrough"); }
        public void executeAlwaysOnTop(Object pass) { events.add("onTop"); }
    }

    private static Class<?> isolate(String source, String suffix, Set<String> methods, Set<String> fields,
                                    Map<String, String> replacements) throws Exception {
        ClassNode node = new ClassNode();
        try (var input = IrisVulkanHandScheduleTest.class.getClassLoader().getResourceAsStream(source + ".class")) {
            if (input == null) throw new AssertionError("Missing " + source);
            new ClassReader(input).accept(node, 0);
        }
        node.access = Opcodes.ACC_PUBLIC;
        node.superName = "java/lang/Object";
        node.interfaces.clear();
        node.innerClasses.clear();
        node.nestHostClass = null;
        node.nestMembers = null;
        node.fields.removeIf(field -> !fields.contains(field.name));
        node.methods.removeIf(method -> !methods.contains(method.name));
        for (var field : node.fields) field.access = Opcodes.ACC_PUBLIC;
        for (var method : node.methods) method.access = Opcodes.ACC_PUBLIC;
        var constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        node.methods.add(constructor);
        String generated = SELF + "$" + suffix;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new ClassRemapper(writer, new Remapper() {
            @Override public String map(String name) { return name.equals(source) ? generated : replacements.getOrDefault(name, name); }
        }));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(IrisVulkanHandScheduleTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }
    private static void set(Object target, String name, Object value) throws Exception { target.getClass().getField(name).set(target, value); }
    private static Object get(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
