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
        writer.visitEnd();
        byte[] callbackBytes = writer.toByteArray();
        Class<?> replay = new ClassLoader(IrisVulkanDeferredDepthRegression.class.getClassLoader()) {
            Class<?> define() { return defineClass(generated.replace('/', '.'), callbackBytes, 0, callbackBytes.length); }
        }.define();

        for (float[] depth : depths) Arrays.fill(depth, 1.0f);
        mainDepth = new float[]{1.0f, 0.85f, 1.0f}; // Hand will cover sky; terrain and sky stay untouched.
        replay.getMethod("beforeHand").invoke(null);
        check(Arrays.equals(depths[2], mainDepth), "depthtex2 must snapshot opaque world before hand submission");
        mainDepth = new float[]{0.55f, 0.85f, 1.0f}; // Solid hand, in legacy compressed window depth.
        replay.getMethod("beforeTranslucents").invoke(null);
        check(deferredChecked, "Deferred callback was not reached");
        check(events.equals(List.of("opaqueDepths", "depth0", "depth1", "deferred", "worldInputs")), "Opaque hand/deferred callback order: " + events);

        // World translucency and translucent hand execute after deferred lighting.
        mainDepth = new float[]{0.55f, 0.7f, 1.0f};
        replay.getMethod("finishWorld").invoke(null);
        check(depths[0][1] == 0.7f && depths[1][1] == 0.85f && depths[2][1] == 0.85f, "Translucency changes only final depth0");
        check(depths[1][0] == 0.55f && depths[2][0] == 1.0f, "Final depth0 refresh preserves solid hand in depth1 and pre-hand sky in depth2");
        System.out.println("PASS: compiled native FramePasses expose solid hand depth before deferred lighting; depth2 retains the opaque world, depth0/1 include the hand, and final translucency changes only depth0");
    }

    public static void snapshotDepth(int index) {
        System.arraycopy(mainDepth, 0, depths[index], 0, mainDepth.length);
        events.add("depth" + index);
    }
    public static void snapshotOpaqueDepths() {
        for (float[] target : depths) System.arraycopy(mainDepth, 0, target, 0, mainDepth.length);
        events.add("opaqueDepths");
    }
    public static void renderDeferred() {
        events.add("deferred");
        check(Arrays.equals(depths[0], mainDepth) && Arrays.equals(depths[1], mainDepth), "Deferred must read the current solid hand depth");
        // Photon deferred4 shades colortex1 only when depthtex1 identifies geometry.
        check(depths[1][0] < 1.0f, "Photon must shade the hand gbuffer instead of the sky behind it");
        // Sildur deferred: depthtex2 > depthtex0 identifies only the hand.
        check(isHand(0) && !isHand(1) && !isHand(2), "Deferred hand mask distinguishes hand, terrain and sky");
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
