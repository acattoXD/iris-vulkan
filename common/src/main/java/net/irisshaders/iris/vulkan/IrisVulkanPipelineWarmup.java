package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Primes real draw-path pipeline identities once, before the first world/shadow draws. */
public final class IrisVulkanPipelineWarmup {
    record Variant(RenderPipeline pipeline, WorldRenderingPhase phase) { }

    private static final List<RenderPipeline> ENTITIES = List.of(
            RenderPipelines.ENTITY_CUTOUT, RenderPipelines.ENTITY_CUTOUT_CULL,
            RenderPipelines.ENTITY_CUTOUT_DISSOLVE, RenderPipelines.ENTITY_TRANSLUCENT,
            RenderPipelines.ENTITY_TRANSLUCENT_CULL, RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_TRANSLUCENT,
            RenderPipelines.ARMOR_CUTOUT_NO_CULL,
            RenderPipelines.EYES, RenderPipelines.GLINT);
    private static final List<Variant> WORLD = worldVariants();
    private static final List<Variant> SHADOW = ENTITIES.stream()
            .map(pipeline -> new Variant(pipeline, WorldRenderingPhase.ENTITIES)).toList();
    private boolean attempted;
    private int warmed;
    private int failures;

    public void warmup(ProgramSet programs, boolean shadow) {
        if (attempted) return;
        ProgramFallbackResolver resolver = new ProgramFallbackResolver(programs);
        long started = System.nanoTime();
        runOnce(variants(shadow), variant -> prime(variant, resolver, shadow), (variant, failure) ->
                Iris.logger.warn("Optional native {} warmup deferred {}: {}", shadow ? "shadow" : "world",
                        variant.pipeline().getLocation(), failure.getMessage()));
        Iris.logger.info("Native Vulkan {} warmup finished: {} pipelines ready, {} failures, {} ms; later draws retain normal validation.",
                shadow ? "shadow" : "world", warmed, failures, (System.nanoTime() - started) / 1_000_000L);
    }

    private static boolean prime(Variant variant, ProgramFallbackResolver resolver, boolean shadow) {
        if (IrisVulkanShadowDrawPolicy.shouldSkip(shadow, variant.pipeline())) return false;
        try (var phase = IrisVulkanPhaseContext.enter(variant.phase())) {
            var key = IrisNativeVulkan.worldShaderKey(variant.pipeline());
            if (key == null || key.isShadow() != shadow) return false;
            var source = resolver.resolve(key.getProgram());
            if (source.isEmpty() || !source.get().isValid()) return false;
            var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            // LOAD-only attachments, no draws or uniform uploads. The normal render-pass
            // hooks select the same MRT/shadow adaptation and compiler cache key used later.
            var descriptor = descriptor(main.getColorTextureView(), main.getDepthTextureView());
            try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
                pass.setPipeline(IrisNativeVulkan.compiledFor(variant.pipeline()));
            }
            return true;
        }
    }

    static RenderPassDescriptor descriptor(GpuTextureView color, GpuTextureView depth) {
        return RenderPassDescriptor.builder(() -> "Iris native pipeline warmup")
                .withColorAttachment(color).withDepthAttachment(depth)
                .withRenderArea(new RenderPass.RenderArea(0, 0, color.getWidth(0), color.getHeight(0))).build();
    }

    /** Failed optional variants are not retried every frame; their normal draw path still validates them. */
    void runOnce(List<Variant> variants, Function<Variant, Boolean> compile, BiConsumer<Variant, RuntimeException> failed) {
        if (attempted) return;
        for (Variant variant : variants) {
            try {
                if (compile.apply(variant)) warmed++;
            } catch (RuntimeException failure) {
                failures++;
                failed.accept(variant, failure);
            }
        }
        attempted = true;
    }

    static List<Variant> variants(boolean shadow) { return shadow ? SHADOW : WORLD; }
    int warmed() { return warmed; }
    int failures() { return failures; }
    public boolean attempted() { return attempted; }

    private static List<Variant> worldVariants() {
        List<Variant> variants = new ArrayList<>();
        for (RenderPipeline pipeline : ENTITIES) variants.add(new Variant(pipeline, WorldRenderingPhase.ENTITIES));
        // These exact world descriptors incurred measured first-use compilation stalls.
        variants.add(new Variant(RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT, WorldRenderingPhase.ENTITIES));
        variants.add(new Variant(RenderPipelines.CUTOUT_BLOCK, WorldRenderingPhase.ENTITIES));
        variants.add(new Variant(RenderPipelines.OPAQUE_PARTICLE, WorldRenderingPhase.PARTICLES));
        variants.add(new Variant(RenderPipelines.TRANSLUCENT_PARTICLE, WorldRenderingPhase.PARTICLES));
        for (WorldRenderingPhase phase : List.of(WorldRenderingPhase.HAND_SOLID, WorldRenderingPhase.HAND_TRANSLUCENT)) {
            for (RenderPipeline pipeline : List.of(RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_TRANSLUCENT,
                    RenderPipelines.ENTITY_TRANSLUCENT)) variants.add(new Variant(pipeline, phase));
        }
        return List.copyOf(variants);
    }
}
