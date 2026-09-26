package net.irisshaders.iris.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.*;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Actual portal hooks, actual Minecraft geometry/types and native SPIR-V input rebinding; no GPU/game. */
public final class IrisPortalContractTest {
    static int checks;
    public static void main(String[] args) throws Exception {
        System.setProperty("iris.test.gameDir", Files.createTempDirectory("iris-portal-gate-").toString());
        testGateAndGeometry();
        testMissingInputs();
        testPack(Path.of(args[0]));
        System.out.println("IRIS_PORTAL_CONTRACT_PASS: " + checks + " checks; actual portal/gateway hooks, GL/Vulkan gates, full geometry/texture contracts, native SPIR-V rebinding and actual Complementary5025 mapping");
    }

    static void testGateAndGeometry() throws Exception {
        Field vanillaFaces = AbstractEndPortalRenderer.class.getDeclaredField("FACES"); vanillaFaces.setAccessible(true);
        Field mixinFaces = MixinTheEndPortalRenderer.class.getDeclaredField("FACES"); mixinFaces.setAccessible(true);
        mixinFaces.set(null, vanillaFaces.get(null)); // What the real Mixin @Shadow resolves in-game.
        Method portalRoute = method(MixinTheEndPortalRenderer.class, "iris$renderType", RenderType.class);
        Method gatewayRoute = method(MixinTheEndGatewayRenderer.class, "iris$renderType", Operation.class);
        Method geometry = method(MixinTheEndPortalRenderer.class, "iris$onRender", Collection.class, PoseStack.Pose.class, VertexConsumer.class, CallbackInfo.class);
        var plugin = new IrisMixinPlugin();
        for (boolean vulkan : new boolean[]{false, true}) {
            IrisMixinPlugin.usingVulkan = vulkan;
            for (Class<?> hook : List.of(MixinTheEndPortalRenderer.class, MixinTheEndGatewayRenderer.class))
                check(plugin.shouldApplyMixin("ignored", hook.getName()), "Portal hook is selected for backend Vulkan=" + vulkan);
            for (boolean pack : new boolean[]{false, true}) {
                Iris.packActive = pack;
                RenderType portal = (RenderType) portalRoute.invoke(null, RenderTypes.endPortal());
                int[] originalCalls = {0};
                Operation<RenderType> original = ignored -> { originalCalls[0]++; return RenderTypes.endGateway(); };
                RenderType gateway = (RenderType) gatewayRoute.invoke(null, original);
                check(originalCalls[0] == (pack ? 0 : 1), "Gateway calls vanilla route exactly when pack is absent");
                if (pack) {
                    for (RenderType route : List.of(portal, gateway)) {
                        check(route.pipeline() == RenderPipelines.ENTITY_SOLID, "Active portal/gateway uses actual entity-solid pipeline");
                        Set<String> attributes = new HashSet<>(); route.format().getElements().forEach(e -> attributes.add(e.name()));
                        check(attributes.containsAll(List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal")), "Entity-solid supplies complete portal attributes");
                        Field stateField = RenderType.class.getDeclaredField("state"); stateField.setAccessible(true); Object state = stateField.get(route);
                        Field texturesField = state.getClass().getDeclaredField("textures"); texturesField.setAccessible(true);
                        Map<?, ?> textures = (Map<?, ?>) texturesField.get(state); Object texture = textures.get("Sampler0");
                        Object location = method(texture.getClass(), "location").invoke(texture);
                        check(location.equals(AbstractEndPortalRenderer.END_PORTAL_LOCATION), "tex/Sampler0 is portal stars, not vanilla end sky");
                    }
                } else {
                    check(portal == RenderTypes.endPortal() && gateway == RenderTypes.endGateway(), "Disabled pack preserves both vanilla routes");
                }
                for (boolean gatewayShape : new boolean[]{false, true}) {
                    PoseStack pose = new PoseStack();
                    if (!gatewayShape) pose.mulPose(TheEndPortalRenderer.TRANSFORMATION);
                    Recorder first = draw(geometry, pose, 0);
                    if (!pack) { check(first.vertices.isEmpty(), "Disabled pack leaves vanilla geometry callback intact"); continue; }
                    check(first.vertices.size() == 24, "Six actual cube faces emit four vertices each");
                    float minimum = gatewayShape ? 0 : 0.375f, maximum = gatewayShape ? 1 : 0.75f;
                    check(close(first.vertices.stream().map(v -> v.y).min(Float::compare).orElseThrow(), minimum)
                        && close(first.vertices.stream().map(v -> v.y).max(Float::compare).orElseThrow(), maximum), "Portal slab/gateway cube geometry extents preserved");
                    int index = 0;
                    for (Direction face : Direction.values()) for (int corner = 0; corner < 4; corner++) {
                        Vertex vertex = first.vertices.get(index++);
                        check(vertex.r == 19 && vertex.g == 38 && vertex.b == 51 && vertex.a == 255, "Portal tint remains intended packed RGBA");
                        check(vertex.overlay == OverlayTexture.NO_OVERLAY && vertex.light == LightCoordsUtil.FULL_BRIGHT, "Portal overlay/light attributes complete and full bright");
                        check(close(vertex.nx, face.getStepX()) && close(vertex.ny, face.getStepY()) && close(vertex.nz, face.getStepZ()), "Face normals distinguish horizontal and vertical portal projection");
                        float u = corner >= 2 ? .2f : 0, v = corner == 1 || corner == 2 ? .2f : 0;
                        check(close(vertex.u, u) && close(vertex.v, v), "Portal UV spans the intended texture region");
                    }
                    Recorder later = draw(geometry, pose, 25), wrapped = draw(geometry, pose, 125);
                    for (int i = 0; i < first.vertices.size(); i++) {
                        check(close(later.vertices.get(i).u - first.vertices.get(i).u, .25f)
                            && close(later.vertices.get(i).v - first.vertices.get(i).v, .25f), "Portal UV animation moves with time");
                        check(close(later.vertices.get(i).u, wrapped.vertices.get(i).u)
                            && close(later.vertices.get(i).v, wrapped.vertices.get(i).v), "Portal animation repeats every100seconds");
                    }
                }
            }
        }
        Iris.packActive = false;
    }

    static Recorder draw(Method method, PoseStack pose, int seconds) throws Exception {
        SystemTimeUniforms.TIMER.reset(); SystemTimeUniforms.TIMER.beginFrame(0); SystemTimeUniforms.TIMER.beginFrame(seconds * 1_000_000_000L);
        Recorder recorder = new Recorder(); CallbackInfo callback = new CallbackInfo("portal", true);
        method.invoke(null, List.of(Direction.values()), pose.last(), recorder, callback);
        check(callback.isCancelled() == Iris.packActive, "Active hook replaces original geometry exactly once");
        return recorder;
    }

    static void testMissingInputs() throws Exception {
        Method patch = method(IrisVulkanShaderResources.class, "patchMissingVertexInputs", String.class, VertexFormat[].class);
        String source = "#version 450\nlayout(location=0) in vec3 iris_Position;\nlayout(location=1) in vec4 iris_Color;\nlayout(location=0) out vec4 testColor;\nvoid main(){gl_Position=vec4(iris_Position,1);testColor=iris_Color*vec4(0.2,0.3,0.4,0.5);}\n";
        VertexFormat position = IrisVulkanShaderResources.aliasVanillaVertexFormat(DefaultVertexFormat.POSITION);
        VertexFormat colored = IrisVulkanShaderResources.aliasVanillaVertexFormat(DefaultVertexFormat.POSITION_COLOR);
        try (GlslCompiler compiler = new GlslCompiler()) {
            try (var baseline = compiler.createIntermediary("portal-unadapted.vert", source, ShaderType.VERTEX)) {
                expectMissing(() -> baseline.rebind(List.of("iris_Position"), List.of()), "iris_Color");
            }
            for (var pipeline : List.of(RenderPipelines.END_PORTAL, RenderPipelines.END_GATEWAY)) {
                check(pipeline.getVertexFormatBinding(0).getElements().size() == 1, "Real vanilla portal/gateway contract is position-only");
                String patched = (String) patch.invoke(null, source, new VertexFormat[]{position});
                try (var module = compiler.createIntermediary("portal-default.vert", patched, ShaderType.VERTEX)) {
                    module.rebind(List.of("iris_Position"), List.of());
                    check(module.spirv().remaining() > 0, "Actual Vulkan frontend accepts missing-color portal vertex contract");
                    float[] color = constantOutput(module.spirv(), "testColor");
                    check(color != null && close(color[0], .2f) && close(color[1], .3f) && close(color[2], .4f) && close(color[3], .5f), "Compiled SPIR-V white default preserves nonwhite/nonopaque color modulator");
                }
            }
            String retained = (String) patch.invoke(null, source, new VertexFormat[]{colored});
            try (var module = compiler.createIntermediary("portal-color.vert", retained, ShaderType.VERTEX)) {
                module.rebind(List.of("iris_Position", "iris_Color"), List.of());
                expectMissing(() -> module.rebind(List.of("iris_Position"), List.of()), "iris_Color");
            }
            String unknown = source.replace("iris_Color", "iris_UnknownRequiredInput");
            unknown = (String) patch.invoke(null, unknown, new VertexFormat[]{position});
            try (var module = compiler.createIntermediary("portal-unknown.vert", unknown, ShaderType.VERTEX)) {
                expectMissing(() -> module.rebind(List.of("iris_Position"), List.of()), "iris_UnknownRequiredInput");
            }
            String unsupportedColor = source.replace("in vec4 iris_Color", "in vec3 iris_Color")
                .replace("testColor=iris_Color*", "testColor=vec4(iris_Color,1)*");
            unsupportedColor = (String) patch.invoke(null, unsupportedColor, new VertexFormat[]{position});
            try (var module = compiler.createIntermediary("portal-unsupported-color.vert", unsupportedColor, ShaderType.VERTEX)) {
                expectMissing(() -> module.rebind(List.of("iris_Position"), List.of()), "iris_Color");
            }
        }
    }

    // Evaluate only constant-vector arithmetic feeding this tiny shader's named
    // output. This checks the compiled semantic result, not the patch's spelling.
    static float[] constantOutput(ByteBuffer bytes, String output) {
        IntBuffer words = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        Map<Integer, float[]> values = new HashMap<>(); int variable = -1; float[] answer = null;
        for (int at = 5; at < words.limit();) {
            int header = words.get(at), count = header >>> 16, op = header & 65535;
            if (op == 5) {
                StringBuilder name = new StringBuilder();
                outer: for (int i = at + 2; i < at + count; i++) for (int j = 0; j < 4; j++) {
                    int ch = words.get(i) >>> (j * 8) & 255; if (ch == 0) break outer; name.append((char) ch);
                }
                if (name.toString().equals(output)) variable = words.get(at + 1);
            } else if (op == 43 && count == 4) values.put(words.get(at + 2), new float[]{Float.intBitsToFloat(words.get(at + 3))});
            else if (op == 44) {
                float[] value = new float[count - 3]; boolean known = true;
                for (int i = 0; i < value.length; i++) { float[] part = values.get(words.get(at + 3 + i)); if (part == null) {known = false; break;} value[i] = part[0]; }
                if (known) values.put(words.get(at + 2), value);
            } else if (op == 133) {
                float[] a = values.get(words.get(at + 3)), b = values.get(words.get(at + 4));
                if (a != null && b != null && a.length == b.length) { float[] value = a.clone(); for (int i = 0; i < value.length; i++) value[i] *= b[i]; values.put(words.get(at + 2), value); }
            } else if (op == 62 && words.get(at + 1) == variable) answer = values.get(words.get(at + 2));
            if (count < 1) throw new AssertionError("Invalid SPIR-V instruction"); at += count;
        }
        return answer;
    }

    static void testPack(Path path) throws Exception {
        try (ZipFile pack = new ZipFile(path.toFile())) {
            Properties properties = new Properties(); properties.load(pack.getInputStream(pack.getEntry("shaders/block.properties")));
            Set<String> materials = Set.of(properties.getProperty("block.5025").trim().split("\\s+"));
            check(materials.containsAll(List.of("end_portal", "end_gateway")), "Actual selected pack maps both surfaces to5025");
            String block = new String(pack.getInputStream(pack.getEntry("shaders/program/gbuffers_block.glsl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            check(block.contains("blockEntityId == 5025"), "Actual block shader recognizes portal material identity");
        }
    }
    interface Checked { void run() throws Exception; }
    static void expectMissing(Checked action, String name) throws Exception { try {action.run(); throw new AssertionError("Required input was silently dropped: " + name);} catch (ShaderCompileException expected) {check(expected.getMessage().contains(name), "Native strict binding names the missing input");} }
    static Method method(Class<?> owner, String name, Class<?>... args) throws Exception {Method method = owner.getDeclaredMethod(name,args);method.setAccessible(true);return method;}
    static boolean close(float a, float b) {return Math.abs(a-b)<.0001f;}
    static void check(boolean condition, String message) {checks++;if(!condition)throw new AssertionError(message);}
    static final class Vertex {float x,y,z,u,v,nx,ny,nz;int r,g,b,a,overlay,light;}
    static final class Recorder implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>(); Vertex current;
        public VertexConsumer addVertex(float x,float y,float z){current=new Vertex();current.x=x;current.y=y;current.z=z;vertices.add(current);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){current.r=r;current.g=g;current.b=b;current.a=a;return this;}
        public VertexConsumer setColor(int color){throw new AssertionError("Unexpected packed color path");}
        public VertexConsumer setUv(float u,float v){current.u=u;current.v=v;return this;}
        public VertexConsumer setUv1(int u,int v){current.overlay=u|(v<<16);return this;}
        public VertexConsumer setUv2(int u,int v){current.light=u|(v<<16);return this;}
        public VertexConsumer setNormal(float x,float y,float z){current.nx=x;current.ny=y;current.nz=z;return this;}
        public VertexConsumer setLineWidth(float width){return this;}
    }
}
