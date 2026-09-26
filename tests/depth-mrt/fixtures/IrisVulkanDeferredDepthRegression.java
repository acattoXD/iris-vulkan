package net.irisshaders.iris.vulkan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Executes the compiled native frame callbacks with only their GPU side effects replaced. */
public final class IrisVulkanDeferredDepthRegression {
    private static final String OWNER = "net/irisshaders/iris/vulkan/IrisNativeVulkan";
    private static final String FIXTURE = IrisVulkanDeferredDepthRegression.class.getName().replace('.', '/');
    private static final float[][] depths = new float[3][3];
    private static final List<String> events = new ArrayList<>();
    private static float[] mainDepth;
    private static boolean deferredChecked;

    public static void main(String[] args) throws Exception {
        // Find the actual FramePasses implementation created by production code.
        ClassNode owner = read(OWNER);
        String callbacks = null;
        for (MethodNode method : owner.methods) if (method.name.equals("createWorldPipeline")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.name.equals("<init>") && call.owner.startsWith(OWNER + "$")) callbacks = call.owner;
        }
        if (callbacks == null) throw new AssertionError("Production FramePasses implementation not found");
        ClassNode compiled = read(callbacks);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String generated = FIXTURE + "$Callbacks";
        writer.visit(52, Opcodes.ACC_PUBLIC, generated, null, "java/lang/Object", null);
        for (String name : List.of("beforeTranslucents", "beforeHand", "finishWorld")) {
            MethodNode method = compiled.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof FieldInsnNode field) {
                    check(field.owner.equals(OWNER) && field.name.equals("finalPassRenderer"), "Unexpected callback field " + field.name);
                    method.instructions.remove(field);
                } else if (instruction instanceof MethodInsnNode call) {
                    check(List.of("snapshotOpaqueDepths", "snapshotDepth", "renderDeferred", "snapshotWorldInputs", "renderFinalPassIfReady").contains(call.name),
                            "Unexpected callback side effect " + call.owner + "." + call.name);
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = FIXTURE;
                    call.itf = false;
                }
            }
            method.accept(writer);
        }
        MethodNode hand = owner.methods.stream().filter(m -> m.name.equals("captureSolidHandDepth")).findFirst().orElseThrow();
        for (var instruction : hand.instructions.toArray()) if (instruction instanceof MethodInsnNode call) {
            check(call.owner.equals("net/irisshaders/iris/vulkan/IrisVulkanGbufferTargets") && call.name.equals("captureSolidHandDepth"),
                "Unexpected solid-hand capture side effect");
            call.owner = FIXTURE;
        }
        hand.accept(writer);
        writer.visitEnd();
        byte[] callbackBytes = writer.toByteArray();
        Class<?> replay = new ClassLoader(IrisVulkanDeferredDepthRegression.class.getClassLoader()) {
            Class<?> define() { return defineClass(generated.replace('/', '.'), callbackBytes, 0, callbackBytes.length); }
        }.define();

        for (float[] depth : depths) Arrays.fill(depth, 1.0f);
        mainDepth = new float[]{0.35f, 0.35f, 1.0f}; // Two floor pixels and sky.
        replay.getMethod("beforeTranslucents").invoke(null);
        check(deferredChecked, "Deferred callback was not reached");
        check(events.equals(List.of("opaqueDepths", "deferred", "worldInputs")), "Opaque callback order: " + events);

        // Translucency is rendered before the later real pre-hand snapshot.
        mainDepth = new float[]{0.2f, 0.35f, 1.0f};
        replay.getMethod("beforeHand").invoke(null);
        check(Arrays.equals(depths[2], mainDepth), "Later beforeHand must refresh depthtex2");
        check(depths[0][0] == 0.35f && depths[1][0] == 0.35f, "Refreshing depth2 must not alias the other opaque snapshots");
        mainDepth = new float[]{0.05f, 0.35f, 1.0f}; // Hand replaces the first pixel.
        replay.getMethod("captureSolidHandDepth").invoke(null);
        check(depths[1][0] == 0.05f && depths[1][1] == 0.35f, "Solid-hand merge updates hand pixels and preserves untouched opaque depth");
        check(depths[0][0] == 0.35f && depths[2][0] == 0.2f, "Solid-hand output has independent depth0/depth2 lifetimes");
        replay.getMethod("finishWorld").invoke(null);
        check(isHand(0) && !isHand(1) && !isHand(2), "Final hand mask must distinguish hand, terrain, and sky");
        check(depths[1][0] == 0.05f && depths[2][0] == 0.2f, "Final depth0 refresh must not overwrite merged depth1 or pre-hand depth2");
        check(events.equals(List.of("opaqueDepths", "deferred", "worldInputs", "depth2", "solidHand", "depth0", "final")), "Full depth lifecycle callback order: " + events);
        System.out.println("PASS: actual compiled FramePasses seed equal opaque depth0/1/2 before deferred; Sildur avoids 6.5x false hand-shadow scaling; pre-hand refresh, solid-hand merge and final depth update retain independent lifetimes");
    }

    public static void snapshotDepth(int index) {
        System.arraycopy(mainDepth, 0, depths[index], 0, mainDepth.length);
        events.add("depth" + index);
    }
    public static void snapshotOpaqueDepths() {
        for (float[] target : depths) System.arraycopy(mainDepth, 0, target, 0, mainDepth.length);
        events.add("opaqueDepths");
    }
    public static void captureSolidHandDepth() {
        float[] merged = new float[mainDepth.length];
        for (int i = 0; i < merged.length; i++) merged[i] = mainDepth[i] < depths[2][i] ? mainDepth[i] : depths[1][i];
        depths[1] = merged;
        events.add("solidHand");
    }
    public static void renderDeferred() {
        events.add("deferred");
        for (float[] target : depths) check(Arrays.equals(target, mainDepth), "All three opaque snapshots must be seeded before deferred");
        // Sildur deferred.fsh: if (depthtex2 > depthtex0) fragpos *= 6.5.
        for (int pixel = 0; pixel < mainDepth.length; pixel++)
            check(!isHand(pixel), "Opaque/sky pixel " + pixel + " incorrectly gets Sildur's 6.5x hand-shadow scale");
        deferredChecked = true;
    }
    public static void snapshotWorldInputs() { events.add("worldInputs"); }
    public static void renderFinalPassIfReady() { events.add("final"); }
    private static boolean isHand(int pixel) { return depths[2][pixel] > depths[0][pixel]; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static ClassNode read(String name) throws Exception {
        try (var input = IrisVulkanDeferredDepthRegression.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new AssertionError("Missing compiled class " + name);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
