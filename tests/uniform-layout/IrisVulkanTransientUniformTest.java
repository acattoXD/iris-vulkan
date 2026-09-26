package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Production value writer plus a recording upload boundary, and the real engine lifetime contract. */
public final class IrisVulkanTransientUniformTest {
    public static void main(String[] args) throws Exception {
        verifyMinecraftUploadContract();
        var engine = new RecordingMemory();
        var before = IrisVulkanUniformSnapshot.uploadStats();
        List<IrisVulkanUniformSnapshot.Field> fields = List.of(field("probeExposure", "float"),
            field("probeColor", "vec3"), field("entityId", "int"), field("atlasSize", "ivec2"),
            field("gbufferModelView", "mat4"), field("gbufferPreviousModelView", "mat4"),
            field("iris_NativeEntityIds", "ivec3"), field("frameCounter", "int"));
        float[] source = {0};
        var inputs = new CustomUniformFixedInputUniformsHolder.Builder();
        inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "probeBrightness", () -> source[0]);
        inputs.uniform3f(UniformUpdateFrequency.PER_FRAME, "probeColor", () -> new Vector3f(source[0], 0.25f, 0.5f));
        var expressions = new CustomUniforms.Builder();
        expressions.addVariable("float", "probeExposure", "probeBrightness * 0.25 + 1", true);
        CustomUniforms custom = expressions.build(inputs.build());
        var counter = IrisVulkanUniformSnapshot.class.getDeclaredField("vulkanFrameCounter");
        counter.setAccessible(true);
        CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f().setPerspective(1.1f, 16f / 9f, 512f, 0.05f, true));
        List<byte[]> expectedDraws = new ArrayList<>();
        List<GpuBufferSlice> slices = new ArrayList<>();
        IrisVulkanUniformSnapshot.registerActiveCustomUniforms(custom);
        try {
            for (int frame = 1; frame <= 16; frame++) {
                counter.setInt(null, frame);
                source[0] = frame * 10;
                custom.beginFrame();
                CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f().rotateY(frame * 0.02f).translate(frame, 0, 0));
                for (int draw = 1; draw <= 4; draw++) {
                    CapturedRenderingState.INSTANCE.setCurrentEntity(frame * 100 + draw);
                    CapturedRenderingState.INSTANCE.setCurrentBlockEntity(draw + 1);
                    CapturedRenderingState.INSTANCE.setCurrentRenderedItem(draw + 2);
                    IrisVulkanUniformSnapshot.setPrimaryTextureSize(256 * draw, 128 * frame);
                    byte[] expected = bytes(IrisVulkanUniformSnapshot.capture(fields).data());
                    GpuBufferSlice uploaded = IrisVulkanUniformSnapshot.uploadTransient(fields, engine.memory, 256);
                    require(uploaded.offset() % 256 == 0 && uploaded.length() == expected.length, "Exact std140 range at aligned offset");
                    require(Arrays.equals(engine.uploads.get(uploaded.offset()), expected), "Uploaded draw matches retained capture byte for byte");
                    expectedDraws.add(expected);
                    slices.add(uploaded);
                    // Reuse/overwrite stack scratch after upload returned. Previously
                    // submitted draw bytes must not point at that scratch allocation.
                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        ByteBuffer poison = stack.malloc(16 * 1024);
                        MemoryUtil.memSet(MemoryUtil.memAddress(poison), 0x5a, poison.remaining());
                    }
                }
            }
            require(!Arrays.equals(expectedDraws.get(0), expectedDraws.get(1)), "Same-frame draw values differ; no value cache");
            for (int i = 0; i < slices.size(); i++)
                require(Arrays.equals(engine.uploads.get(slices.get(i).offset()), expectedDraws.get(i)), "Later draws cannot overwrite earlier uploads");
            var large = java.util.Collections.nCopies(5000, field("frameCounter", "int"));
            var largeSlice = IrisVulkanUniformSnapshot.uploadTransient(large, engine.memory, 256);
            require(Arrays.equals(engine.uploads.get(largeSlice.offset()), bytes(IrisVulkanUniformSnapshot.capture(large).data())),
                "Large uniform blocks preserve values and padding without overflowing MemoryStack");
            try (MemoryStack constrained = MemoryStack.stackPush()) {
                constrained.malloc(constrained.getPointer() - 2048);
                var fallback = IrisVulkanUniformSnapshot.uploadTransient(fields, engine.memory, 256);
                require(Arrays.equals(engine.uploads.get(fallback.offset()), bytes(IrisVulkanUniformSnapshot.capture(fields).data())),
                    "A deep caller stack leaves engine-upload headroom by using native scratch");
            }
            require(engine.buffer.closeCount == 0, "Caller must not close engine-owned transient buffers");
            var after = IrisVulkanUniformSnapshot.uploadStats();
            require(after.transientUploads() - before.transientUploads() == 66, "One upload per draw");
            require(after.stackUploads() - before.stackUploads() == 64, "Ordinary blocks use bounded stack scratch");
            require(after.nativeFallbackUploads() - before.nativeFallbackUploads() == 2, "Large block and constrained stack use explicit native fallback");
            require(after.dedicatedUploads() == before.dedicatedUploads(), "Transient graphics path creates no dedicated UBO");
        } finally {
            IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(custom);
        }
        System.out.println("IRIS_TRANSIENT_UNIFORMS_PASS: 66 fresh aligned uploads, exact bytes/padding, same-frame entities/atlases, matrix history/custom values, scratch lifetime, large blocks/deep stacks, no caller closes; real Minecraft upload copies synchronously and queues pooled retirement");
    }

    private static void verifyMinecraftUploadContract() throws Exception {
        ClassNode engine = bytecode("com/mojang/renderpearl/backend/vulkan/VulkanTransientMemory");
        MethodNode upload = engine.methods.stream().filter(method -> method.name.equals("upload")).findFirst().orElseThrow();
        int allocate = callIndex(upload, "allocateGpuMapped"), copy = callIndex(upload, "memCopy"), close = callIndex(upload, "close");
        require(allocate >= 0 && copy > allocate && close > copy, "Engine upload owns allocation and completes CPU copy before closing mapped view");
        int returned = -1;
        for (int i = 0; i < upload.instructions.size(); i++)
            if (upload.instructions.get(i).getOpcode() == Opcodes.ARETURN) { returned = i; break; }
        require(returned > close, "Engine upload cannot return before synchronous copy completes");
        MethodNode retire = engine.methods.stream().filter(method -> method.name.equals("endSubmit")).findFirst().orElseThrow();
        require(callIndex(retire, "rotate") >= 0 && callIndex(retire, "queueForDestroy") >= 0,
            "Engine retires backing allocations through submission destruction queue");
        ClassNode transientBuffer = bytecode("com/mojang/renderpearl/backend/vulkan/VulkanTransientMemory$TransientGpuBuffer");
        MethodNode closed = transientBuffer.methods.stream().filter(method -> method.name.equals("isClosed")).findFirst().orElseThrow();
        boolean submissionBound = false;
        for (var instruction : closed.instructions)
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("submitIndex")) submissionBound = true;
        require(submissionBound, "Transient handles are submission-scoped and must not be cached across submissions");
    }

    private static ClassNode bytecode(String name) throws Exception {
        try (var stream = IrisVulkanTransientUniformTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            ClassNode result = new ClassNode();
            new ClassReader(stream).accept(result, 0);
            return result;
        }
    }

    private static int callIndex(MethodNode method, String name) {
        for (int i = 0; i < method.instructions.size(); i++)
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals(name)) return i;
        return -1;
    }

    private static final class RecordingMemory {
        private final TestBuffer buffer = new TestBuffer();
        private final Map<Long, byte[]> uploads = new LinkedHashMap<>();
        private long cursor;
        private final TransientMemory memory = (TransientMemory) Proxy.newProxyInstance(TransientMemory.class.getClassLoader(),
            new Class<?>[]{TransientMemory.class}, (proxy, method, arguments) -> {
                if (!method.getName().equals("uploadGpu") || arguments.length != 3 || !(arguments[0] instanceof ByteBuffer data))
                    throw new AssertionError("Unexpected allocation API: " + method);
                long alignment = (Long) arguments[1];
                require(alignment == 256 && (Integer) arguments[2] == GpuBuffer.USAGE_UNIFORM, "Use device UBO alignment and uniform usage");
                cursor = (cursor + alignment - 1) & -alignment;
                long offset = cursor;
                byte[] copied = bytes(data);
                uploads.put(offset, copied);
                long allocated = (copied.length + alignment - 1) & -alignment;
                cursor += allocated;
                return new GpuBufferSlice(buffer, offset, allocated);
            });
    }

    private static final class TestBuffer implements GpuBuffer {
        private int closeCount;
        @Override public long size() { return 8 * 1024 * 1024; }
        @Override public int usage() { return GpuBuffer.USAGE_UNIFORM; }
        @Override public boolean isClosed() { return closeCount != 0; }
        @Override public void close() { closeCount++; }
        @Override public GpuBufferSlice.MappedView map(long offset, long size, boolean read, boolean write) { throw new AssertionError("Unexpected GPU map"); }
    }

    private static byte[] bytes(ByteBuffer source) { byte[] copy = new byte[source.remaining()]; source.duplicate().get(copy); return copy; }
    private static IrisVulkanUniformSnapshot.Field field(String name, String type) { return new IrisVulkanUniformSnapshot.Field(name, type); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
