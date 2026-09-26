package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPassAccessor;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.renderer.RenderPipelines;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.lang.reflect.*;
import java.util.*;

/** Replays both cloud binders; old code fails with empty textures and borrows stale ones. */
public final class CloudTextureContract {
    private static int checks;
    private static final List<String> ALBEDO = List.of("gtexture", "tex", "texture", "u_MainSampler", "colortex0", "gcolor");
    private static final Method TEXTURES = method(IrisVulkanRenderPassBindings.class, "bindTextureAliases", RenderPass.class, VulkanRenderPass.class, RenderPipeline.class, List.class);
    private static final Method UNIFORMS = method(IrisVulkanRenderPassBindings.class, "bindUniformAliases", RenderPass.class, VulkanRenderPass.class, RenderPipeline.class);
    private static final Method MAKE_TEXTURE = method(TextureBindingContract.class, "texture", String.class, int.class, int.class, FilterMode.class);
    private static final Method FIND = method(IrisVulkanRenderPassBindings.class, "findTextureBinding", String.class, Map.class, ShaderKey.class);
    private static final Method PRIMARY = method(IrisVulkanRenderPassBindings.class, "primaryTexture", Map.class, ShaderKey.class);

    public static void main(String[] args) throws Exception {
        boolean original = args.length > 0 && args[0].equals("--original");
        TextureViewAndSampler white = texture("white", 1, 1), skin = texture("skin", 64, 64), atlas = texture("atlas", 4096, 2048);
        Field whiteField = original ? null : field(IrisVulkanRenderPassBindings.class, "cloudWhiteBinding");
        Object oldWhite = whiteField == null ? null : whiteField.get(null);
        if (whiteField != null) {
            Constructor<?> constructor = whiteField.getType().getDeclaredConstructor(GpuTextureView.class, GpuSampler.class);
            constructor.setAccessible(true);
            whiteField.set(null, constructor.newInstance(white.view(), white.sampler()));
        }
        @SuppressWarnings("unchecked") var keys = (Map<RenderPipeline, ShaderKey>) field(IrisNativeVulkan.class, "preparedWorldShaderKeys").get(null);
        List<RenderPipeline> created = new ArrayList<>();
        try {
            for (RenderPipeline producer : List.of(RenderPipelines.FLAT_CLOUDS, RenderPipelines.CLOUDS)) {
                var builder = RenderPipeline.builder().withLocation(producer.getLocation())
                    .withVertexShader("core/clouds").withFragmentShader("core/clouds").withPrimitiveTopology(producer.getPrimitiveTopology());
                producer.getBindGroupLayouts().forEach(builder::withBindGroupLayout);
                var extra = BindGroupLayout.builder().withUniform("iris_CloudInfo", UniformType.UNIFORM_BUFFER);
                for (String name : ALBEDO) extra.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
                RenderPipeline pipeline = builder.withBindGroupLayout(extra.build()).build();
                created.add(pipeline); keys.put(pipeline, ShaderKey.CLOUDS);
                HashMap<String, Object> values = new HashMap<>();
                GpuBufferSlice slice = new Buffer().slice();
                for (var uniform : BindGroupLayout.flattenUniforms(producer.getBindGroupLayouts()))
                    if (uniform.type() != UniformType.COMBINED_IMAGE_SAMPLER) values.put(uniform.name(), slice);
                RenderPass pass = (RenderPass) Proxy.newProxyInstance(CloudTextureContract.class.getClassLoader(), new Class<?>[]{RenderPass.class, VKOnly_RenderPassAccessor.class}, (proxy, m, a) -> {
                    if (m.getName().equals("iris$getUniforms")) return values;
                    if (m.getName().equals("setUniform")) { values.put((String) a[0], a.length == 3 ? new TextureViewAndSampler((GpuTextureView)a[1], (GpuSampler)a[2]) : a[1]); return null; }
                    if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (m.getName().equals("equals")) return proxy == a[0];
                    throw new AssertionError("Unexpected pass call: " + m);
                });
                invoke(UNIFORMS, pass, null, pipeline);
                check(values.get("iris_CloudInfo") == slice, "Cloud uniform alias retained");
                if (original) {
                    try { invoke(TEXTURES, pass, null, pipeline, List.of()); throw new AssertionError("Control accepted missing texture"); }
                    catch (IllegalStateException expected) { check(expected.getMessage().contains("Missing Vulkan texture binding"), "Original missing-texture error reproduced"); }
                    values.put("Sampler0", skin);
                    Object binding = invoke(FIND, "gtexture", values, ShaderKey.CLOUDS);
                    check(bindingView(binding) == skin.view(), "Original also borrows stale skin");
                    continue;
                }
                for (boolean stale : List.of(false, true)) {
                    if (stale) {
                        for (String name : ALBEDO) values.put(name, skin);
                        values.put("Sampler0", skin); values.put("u_BlockTex", atlas);
                    }
                    invoke(UNIFORMS, pass, null, pipeline);
                    invoke(TEXTURES, pass, null, pipeline, List.of());
                    for (String name : ALBEDO) {
                        var actual = (TextureViewAndSampler) values.get(name);
                        check(actual.view() == white.view() && actual.sampler() == white.sampler(), "Cloud albedo uses white: " + name);
                    }
                    check(values.get("CloudFaces") == slice, "Texel-buffer geometry remains bound");
                    check(bindingView(invoke(PRIMARY, values, ShaderKey.CLOUDS)) == white.view(), "Cloud texture size source cannot inherit stale terrain/skin");
                }
                for (ShaderKey key : List.of(ShaderKey.CLOUDS, ShaderKey.CLOUDS_SODIUM))
                    for (String name : List.of("gtexture", "tex", "texture", "u_MainSampler", "gcolor", "colortex0", "lightmap", "u_LightTex", "iris_overlay"))
                        check(bindingView(invoke(FIND, name, values, key)) == white.view(), "Absent cloud material inputs match GL white");
                check(bindingView(invoke(FIND, "gtexture", values, ShaderKey.ENTITIES_CUTOUT)) == skin.view(), "Textured entity still uses own albedo");
                check(bindingView(invoke(FIND, "gtexture", values, ShaderKey.SODIUM_TERRAIN_SOLID)) == atlas.view(), "Terrain still uses own atlas");
                Field globals = field(IrisVulkanCustomTextures.class, "globalTextures");
                Object previous = globals.get(null);
                globals.set(null, Map.of("gtexture", new IrisVulkanCustomTextures.Binding(skin.view().texture(), skin.view(), skin.sampler(), false)));
                try { check(bindingView(invoke(FIND, "gtexture", values, ShaderKey.CLOUDS)) == skin.view(), "Pack custom texture retains precedence"); }
                finally { globals.set(null, previous); }
            }
        } finally {
            created.forEach(keys::remove);
            if (whiteField != null) whiteField.set(null, oldWhite);
        }
        if (!original) verifyUploadLifetime();
        System.out.println("PASS: " + checks + " complete cloud resource replay checks (" + (original ? "original failure/stale texture reproduced" : "patched") + "); no GPU");
    }
    private static void verifyUploadLifetime() throws Exception {
        ClassNode bindings = node("net/irisshaders/iris/vulkan/IrisVulkanRenderPassBindings.class");
        MethodNode prepare = bindings.methods.stream().filter(m -> m.name.equals("preparePackResources")).findFirst().orElseThrow();
        boolean whiteConstructed = false;
        for (var instruction : prepare.instructions) if (instruction instanceof MethodInsnNode call
            && call.name.equals("<init>") && call.owner.endsWith("NativeImageBackedSingleColorTexture") && call.desc.equals("(IIII)V")) {
            var previous = call.getPrevious();
            for (int i=0; i<4; i++, previous=previous.getPrevious()) check(previous instanceof IntInsnNode value && value.operand == 255, "Opaque white RGBA upload components");
            whiteConstructed = true;
        }
        check(whiteConstructed, "White texture is created during pack resource preparation");
        for (var method : bindings.methods) if (method.name.equals("findTextureBinding") || method.name.equals("bindTextureAliases"))
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call)
                check(!call.name.equals("createTexture") && !call.name.equals("writeToTexture") && !(call.name.equals("<init>") && call.owner.endsWith("NativeImageBackedSingleColorTexture")), "No texture allocation/upload during draw binding");
        MethodNode close = bindings.methods.stream().filter(m -> m.name.equals("closePackResources")).findFirst().orElseThrow();
        boolean closesWhite = false, clearsBinding = false;
        for (var instruction : close.instructions) {
            if (instruction instanceof FieldInsnNode f && f.name.equals("cloudWhite") && f.getOpcode()==org.objectweb.asm.Opcodes.GETSTATIC
                && f.getNext() instanceof MethodInsnNode call && call.name.equals("close")) closesWhite = true;
            if (instruction instanceof FieldInsnNode f && f.name.equals("cloudWhiteBinding") && f.getOpcode()==org.objectweb.asm.Opcodes.PUTSTATIC
                && f.getPrevious().getOpcode()==org.objectweb.asm.Opcodes.ACONST_NULL) clearsBinding = true;
        }
        check(closesWhite && clearsBinding, "Pack teardown retires white texture and clears borrowed binding");
    }
    private static ClassNode node(String path) throws Exception {
        ClassNode result = new ClassNode();
        try (var bytes = CloudTextureContract.class.getResourceAsStream("/" + path)) { new ClassReader(bytes).accept(result, 0); }
        return result;
    }
    private static GpuTextureView bindingView(Object binding) throws Exception { return (GpuTextureView) invoke(method(binding.getClass(), "view"), binding); }
    private static TextureViewAndSampler texture(String name, int width, int height) throws Exception { return (TextureViewAndSampler) invoke(MAKE_TEXTURE, name, width, height, FilterMode.NEAREST); }
    private static Object invoke(Method m, Object... args) throws Exception {
        try { return Modifier.isStatic(m.getModifiers()) ? m.invoke(null, args) : m.invoke(args[0]); }
        catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw e; }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static Method method(Class<?> type, String name, Class<?>... types) { try { Method m = type.getDeclaredMethod(name, types); m.setAccessible(true); return m; } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static final class Buffer implements com.mojang.renderpearl.api.buffers.GpuBuffer {
        public long size() { return 256; } public int usage() { return USAGE_UNIFORM | USAGE_UNIFORM_TEXEL_BUFFER; }
        public boolean isClosed() { return false; } public void close() { throw new AssertionError("Borrowed buffer closed"); }
        public GpuBufferSlice.MappedView map(long o, long n, boolean r, boolean w) { throw new AssertionError("GPU map"); }
    }
}
