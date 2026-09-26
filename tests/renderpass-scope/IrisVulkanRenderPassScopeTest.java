package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.resources.Identifier;
import sun.misc.Unsafe;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes real apply/register/unregister with recording GPU sinks, not a duplicate ownership predicate. */
public final class IrisVulkanRenderPassScopeTest {
    public static void main(String[] args) throws Exception {
        var description = pipeline("minecraft:atlas_fixture", true);
        var layout = new VulkanBindGroupLayout(17, List.of());
        var owned = compiled(description, layout, 1);
        var vanilla = compiled(description, layout, 1);
        require(owned != vanilla && owned.equals(vanilla), "Fixture must cover distinct, value-equal compiled records sharing one description");
        // Reproduce resourcesFor(info) becoming stale when preparation returns a shared original.
        var resources = IrisVulkanShaderResources.class.getDeclaredField("RESOURCE_SETS"); resources.setAccessible(true);
        @SuppressWarnings("unchecked") var metadata = (Map<RenderPipeline, IrisVulkanShaderResources.ResourceSet>) resources.get(null);
        metadata.put(description, new IrisVulkanShaderResources.ResourceSet(Set.of(), Set.of("Globals"), Set.of(), List.of(), Set.of(), Set.of()));
        var originalProjection = blankSlice();
        RenderSystem.defaultProjection = blankSlice();
        IrisVulkanRenderPassBindings.registerPipeline(owned);
        try {
            var backend = new VulkanRenderPass(vanilla);
            backend.uniforms.put("Projection", originalProjection);
            var pass = new RenderPass(backend);
            resetCounters();
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(pass.uniformWrites == 0 && pass.textureWrites == 0 && RenderSystem.defaultBindingCalls == 0 && IrisVulkanStoragePipeline.bindCalls == 0,
                    "Unowned vanilla pipeline must be untouched despite shared source metadata and missing Globals");
            require(backend.uniforms.get("Projection") == originalProjection, "Unowned caller's explicit projection must not be overwritten");

            backend = new VulkanRenderPass(owned); pass = new RenderPass(backend); resetCounters();
            try {
                IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
                throw new AssertionError("Owned missing Globals was silently ignored");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("uniform binding for Globals"), "Actual owned requirement must retain useful failure detail");
                require(expected.getMessage().contains(description.getLocation().toString()), "Owned binding failure must identify its pipeline");
            }
            require(RenderSystem.defaultBindingCalls == 1 && IrisVulkanStoragePipeline.bindCalls == 1, "Owned apply still reaches required binding work");

            IrisVulkanRenderPassBindings.unregisterPipeline(owned);
            backend = new VulkanRenderPass(owned); pass = new RenderPass(backend); resetCounters();
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(RenderSystem.defaultBindingCalls == 0 && IrisVulkanStoragePipeline.bindCalls == 0, "Unregistered retired pipeline must lose ownership even while source metadata remains");
        } finally { IrisVulkanRenderPassBindings.unregisterPipeline(owned); metadata.remove(description); }

        var simple = compiled(pipeline("iris:custom_screen_fixture", false), new VulkanBindGroupLayout(23, List.of()), 2);
        IrisVulkanRenderPassBindings.registerPipeline(simple);
        try {
            var backend = new VulkanRenderPass(simple); var pass = new RenderPass(backend); resetCounters();
            IrisVulkanRenderPassBindings.prepareScreenPassResources(pass, "scope-test-screen", TextureStage.COMPOSITE_AND_FINAL, null);
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(RenderSystem.defaultBindingCalls == 1 && IrisVulkanStoragePipeline.bindCalls == 1, "Registered custom screen pass retains screen/default and storage dispatch");
            backend = new VulkanRenderPass(simple); pass = new RenderPass(backend); resetCounters();
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(RenderSystem.defaultBindingCalls == 1 && IrisVulkanStoragePipeline.bindCalls == 1, "Registered advanced/world pass still invokes the storage descriptor sink");
        } finally { IrisVulkanRenderPassBindings.unregisterPipeline(simple); }

        var aliases = compiled(pipeline("iris:alias_fixture", "iris_DynamicTransforms"), new VulkanBindGroupLayout(29, List.of()), 3);
        IrisVulkanRenderPassBindings.registerPipeline(aliases);
        try {
            var backend = new VulkanRenderPass(aliases); var pass = new RenderPass(backend);
            var first = blankSlice(); var second = blankSlice();
            backend.uniforms.put("DynamicTransforms", first);
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(backend.uniforms.get("iris_DynamicTransforms") == first, "Owned draw must receive its engine transform alias");
            backend.uniforms.put("DynamicTransforms", second);
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(backend.uniforms.get("iris_DynamicTransforms") == second, "Second owned draw must refresh alias from current engine binding");
        } finally { IrisVulkanRenderPassBindings.unregisterPipeline(aliases); }

        var invalid = compiled(pipeline("iris:invalid_fixture", false), new VulkanBindGroupLayout(31, List.of()), 0);
        IrisVulkanRenderPassBindings.registerPipeline(invalid);
        try {
            var backend = new VulkanRenderPass(invalid); var pass = new RenderPass(backend); resetCounters();
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            backend.pipeline = null;
            IrisVulkanRenderPassBindings.apply(pass, backend, List.of());
            require(RenderSystem.defaultBindingCalls == 0 && IrisVulkanStoragePipeline.bindCalls == 0, "Invalid/null compiled pipelines do no binding work");
        } finally { IrisVulkanRenderPassBindings.unregisterPipeline(invalid); }
        System.out.println("IRIS_RENDERPASS_SCOPE_PASS: real apply identity ownership, shared metadata rejection, untouched vanilla bindings, strict owned failure, custom/storage dispatch, retirement and invalid states");
    }
    private static RenderPipeline pipeline(String id, boolean globals) {
        return pipeline(id, globals ? "Globals" : null);
    }
    private static RenderPipeline pipeline(String id, String uniform) {
        var builder = RenderPipeline.builder().withLocation(Identifier.parse(id)).withVertexShader("core/position")
                .withFragmentShader("core/position").withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (uniform != null) builder.withBindGroupLayout(BindGroupLayout.builder().withUniform(uniform, UniformType.UNIFORM_BUFFER).build());
        return builder.build();
    }
    private static VulkanRenderPipeline compiled(RenderPipeline info, VulkanBindGroupLayout layout, long handle) {
        return new VulkanRenderPipeline(info, null, handle, 0, 11, layout, 13, 14);
    }
    private static GpuBufferSlice blankSlice() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return (GpuBufferSlice) ((Unsafe) field.get(null)).allocateInstance(GpuBufferSlice.class);
    }
    private static void resetCounters() { RenderSystem.defaultBindingCalls = 0; IrisVulkanStoragePipeline.bindCalls = 0; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
