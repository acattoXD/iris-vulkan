package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix4f;
import org.lwjgl.util.shaderc.Shaderc;

import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

/** Shared GLINT keeps world depth, but follows the exact hand projection/depth while held. */
public final class IrisVulkanHandGlintTest {
    public static void main(String[] args) throws Exception {
        var inputs = new net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder.Builder();
        inputs.uniform1i(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME, "iris_NativeHandDraw", () -> 99);
        inputs.uniformMatrix(net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME, "iris_ProjMat", Matrix4f::new);
        var typeProvider = new net.irisshaders.iris.uniforms.custom.CustomUniforms.Builder().build(inputs.build());
        // Supply type metadata without initializing a Fabric launcher. Production
        // hand values must bypass even this intentionally wrong cached value 99.
        IrisVulkanUniformSnapshot.registerActiveCustomUniforms(typeProvider);
        String original = "#version 450\nlayout(std140) uniform iris_Projection { mat4 iris_ProjMat; };\n"
            + "layout(location=0) in vec3 iris_Position;\nvoid main() { gl_Position = iris_ProjMat * vec4(iris_Position, 1); if (iris_Position.x < 0) return; }\n";
        String glint = IrisVulkanShaderResources.patchWorldClipDepth(
            IrisVulkanShaderResources.patchWorldProjectionUniforms(original, ShaderKey.GLINT), ShaderKey.GLINT);
        String hand = IrisVulkanShaderResources.patchWorldClipDepth(original, ShaderKey.HAND_CUTOUT);
        String world = IrisVulkanShaderResources.patchWorldClipDepth(original, ShaderKey.ENTITIES_CUTOUT);
        require(glint.contains("if (iris_NativeHandDraw != 0)"), "Shared GLINT compression is draw-local, never unconditional");
        require(!world.contains("iris_NativeHandDraw") && !world.contains("0.4375"), "Ordinary world depth is unaffected");
        var assignments = Pattern.compile("gl_Position\\.z = [^;]+;");
        var handAssignments = assignments.matcher(hand).results().map(match -> match.group()).toList();
        var glintAssignments = assignments.matcher(glint).results().map(match -> match.group()).toList();
        require(handAssignments.equals(glintAssignments), "Base-hand and glint depth arithmetic must match exactly for EQUAL testing");
        Object loose = method(IrisVulkanShaderResources.class, "patchLooseUniforms", String.class).invoke(null, glint);
        @SuppressWarnings("unchecked")
        List<IrisVulkanUniformSnapshot.Field> fields = (List<IrisVulkanUniformSnapshot.Field>) method(loose.getClass(), "fields").invoke(loose);
        require(fields.contains(new IrisVulkanUniformSnapshot.Field("iris_NativeHandDraw", "int")), "The dynamic flag must enter the actual std140 resource ABI");
        String noLoose = (String) method(loose.getClass(), "source").invoke(loose);
        String compiledSource = (String) method(IrisVulkanShaderResources.class, "injectUniformBlock", String.class, List.class).invoke(null, noLoose, fields);
        long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize();
        try {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            long result = Shaderc.shaderc_compile_into_spv(compiler, compiledSource, Shaderc.shaderc_glsl_vertex_shader, "hand-glint.vert", "main", options);
            try { require(Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success,
                "Dynamic hand/world glint GLSL must compile: " + Shaderc.shaderc_result_get_error_message(result)); }
            finally { Shaderc.shaderc_result_release(result); }
        } finally { Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler); }

        var worldProjection = new Matrix4f().setPerspective(1.1f, 16f / 9f, 512f, 0.05f, true);
        var handProjection = new Matrix4f().setPerspective(0.8f, 16f / 9f, 512f, 0.05f, true);
        CapturedRenderingState.INSTANCE.setGbufferProjection(worldProjection);
        CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f());
        var requested = List.of(new IrisVulkanUniformSnapshot.Field("iris_NativeHandDraw", "int"),
            new IrisVulkanUniformSnapshot.Field("iris_ProjMat", "mat4"));
        IrisVulkanPhaseContext.clearHandMatrices();
        var first = IrisVulkanUniformSnapshot.capture(requested).data();
        try {
            IrisVulkanPhaseContext.captureHandMatrices(handProjection, new Matrix4f());
            var held = IrisVulkanUniformSnapshot.capture(requested).data();
            require(held.getInt(0) == 1, "Held GLINT reads hand state in the current draw");
            require(new Matrix4f(held.slice(16, 64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()).equals(IrisVulkanProjection.toShaderpack(handProjection)), "Held glint and base hand share the scoped hand projection");
        } finally { IrisVulkanPhaseContext.clearHandMatrices(); }
        var after = IrisVulkanUniformSnapshot.capture(requested).data();
        require(first.getInt(0) == 0 && after.getInt(0) == 0, "World glint before and after the hand has no compressed depth");
        require(new Matrix4f(after.slice(16, 64).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()).equals(IrisVulkanProjection.toShaderpack(worldProjection)), "World glint projection is restored");
        require(IrisVulkanShadowDrawPolicy.shouldSkip(true, RenderPipelines.GLINT), "Shadow overlay suppression remains intact");
        IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(typeProvider);
        System.out.println("IRIS_HAND_GLINT_PASS: shared pipeline has scoped hand flag, identical base-hand depth arithmetic, native uniform ABI/SPIR-V, and same-frame hand/world projection restoration");
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
