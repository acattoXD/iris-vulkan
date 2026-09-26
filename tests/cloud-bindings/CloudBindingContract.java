package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPassAccessor;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;

/** Actual buffer-alias binder with recording buffers/pass; no game or GPU. */
public final class CloudBindingContract {
    private static int checks;
    private static final Method BIND;
    static {
        try {
            BIND = IrisVulkanRenderPassBindings.class.getDeclaredMethod("bindUniformAliases", RenderPass.class, VulkanRenderPass.class, RenderPipeline.class);
            BIND.setAccessible(true);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    public static void main(String[] args) throws Exception {
        boolean original = args.length > 0 && args[0].equals("--original");
        for (RenderPipeline nativePipeline : List.of(RenderPipelines.FLAT_CLOUDS, RenderPipelines.CLOUDS)) {
            var declarations = BindGroupLayout.flattenUniforms(nativePipeline.getBindGroupLayouts());
            check(declarations.stream().anyMatch(d -> d.name().equals("CloudInfo") && d.type() == UniformType.UNIFORM_BUFFER), "Actual cloud producer declares CloudInfo");
            check(declarations.stream().anyMatch(d -> d.name().equals("CloudFaces") && d.type() == UniformType.TEXEL_BUFFER), "Actual cloud face buffer remains a texel buffer");
            var builder = RenderPipeline.builder().withLocation(nativePipeline.getLocation())
                .withVertexShader("core/clouds").withFragmentShader("core/clouds")
                .withPrimitiveTopology(nativePipeline.getPrimitiveTopology());
            nativePipeline.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
            builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("iris_CloudInfo", UniformType.UNIFORM_BUFFER).build());
            RenderPipeline adapted = builder.build();
            HashMap<String, Object> values = new HashMap<>();
            for (var declaration : declarations) if (declaration.type() != UniformType.COMBINED_IMAGE_SAMPLER) {
                values.put(declaration.name(), new Buffer().slice());
            }
            RenderPass pass = (RenderPass) Proxy.newProxyInstance(CloudBindingContract.class.getClassLoader(),
                new Class<?>[]{RenderPass.class, VKOnly_RenderPassAccessor.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("iris$getUniforms")) return values;
                    if (method.getName().equals("setUniform")) { values.put((String) arguments[0], arguments[1]); return null; }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    throw new AssertionError("Unexpected pass call: " + method);
                });
            if (original) {
                expectMissing(pass, adapted);
                continue;
            }
            Object faces = values.get("CloudFaces"), cloud = values.get("CloudInfo");
            bind(pass, adapted);
            check(values.get("iris_CloudInfo") == cloud, "Alias borrows exact engine slice");
            check(values.get("CloudFaces") == faces, "Cloud face binding preserved");
            GpuBufferSlice nextFrame = new Buffer().slice(16, 48);
            values.put("CloudInfo", nextFrame);
            bind(pass, adapted);
            check(values.get("iris_CloudInfo") == nextFrame, "Refresh stale alias after producer buffer changes");
            values.remove("CloudInfo"); values.remove("iris_CloudInfo");
            expectMissing(pass, adapted);
            for (Object value : values.values()) if (value instanceof GpuBufferSlice slice) {
                check(!slice.buffer().isClosed(), "Binding does not close borrowed engine buffers");
            }
        }
        ClassNode cloudClass = new ClassNode();
        try (var bytes = CloudRenderer.class.getResourceAsStream("/net/minecraft/client/renderer/CloudRenderer.class")) {
            new ClassReader(bytes).accept(cloudClass, 0);
        }
        boolean producer = cloudClass.methods.stream().anyMatch(method -> {
            boolean cloudName = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode ldc && "CloudInfo".equals(ldc.cst)) cloudName = true;
                if (cloudName && instruction instanceof MethodInsnNode call && call.name.equals("setUniform")
                    && call.desc.equals("(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V")) return true;
            }
            return false;
        });
        check(producer, "Actual 26.3 CloudRenderer supplies CloudInfo before drawing");
        System.out.println("PASS: " + checks + " cloud binding checks (" + (original ? "original failure reproduced" : "patched live-slice replay") + "); no GPU");
    }
    private static void bind(RenderPass pass, RenderPipeline pipeline) throws Exception {
        try { BIND.invoke(null, pass, null, pipeline); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }
    private static void expectMissing(RenderPass pass, RenderPipeline pipeline) throws Exception {
        try { bind(pass, pipeline); throw new AssertionError("Missing cloud buffer accepted"); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains("iris_CloudInfo"), "Original missing-buffer diagnostic reproduced"); }
    }
    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks++;
    }
    private static final class Buffer implements GpuBuffer {
        private boolean closed;
        public long size() { return 256; }
        public int usage() { return USAGE_UNIFORM | USAGE_UNIFORM_TEXEL_BUFFER; }
        public boolean isClosed() { return closed; }
        public void close() { closed = true; }
        public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) { throw new AssertionError("No GPU mapping in this test"); }
    }
}
