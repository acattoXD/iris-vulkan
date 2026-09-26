package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class IrisVulkanPipelineWarmupTest {
    public static void main(String[] args) throws Exception {
        for (int[] size : List.of(new int[]{1, 1}, new int[]{960, 540}, new int[]{1280, 720}, new int[]{2560, 1351})) {
            GpuTextureView color = view(size[0], size[1]), depth = view(size[0], size[1]);
            var descriptor = IrisVulkanPipelineWarmup.descriptor(color, depth);
            check(descriptor.renderArea() != null && descriptor.renderArea().x() == 0 && descriptor.renderArea().y() == 0
                    && descriptor.renderArea().width() == size[0] && descriptor.renderArea().height() == size[1],
                    "Real warmup descriptor must specify the current color-view render area");
            check(descriptor.colorAttachments().size() == 1, "Warmup descriptor added an attachment");
            check(descriptor.colorAttachments().getFirst().textureView() == color
                    && descriptor.depthAttachment().textureView() == depth, "Warmup changed attachment identity");
            check(descriptor.colorAttachments().getFirst().clearValue().isEmpty()
                    && descriptor.depthAttachment().clearValue().isEmpty(), "Warmup attachments must remain LOAD-only");
        }
        var warmup = new IrisVulkanPipelineWarmup();
        Map<RenderPipeline, Object> compiled = new IdentityHashMap<>();
        AtomicInteger compilations = new AtomicInteger();
        var requests = IrisVulkanPipelineWarmup.variants(false);
        check(requests.size() == 21, "Expected the existing 19 world requests plus two measured variants");
        check(new HashSet<>(requests).size() == requests.size(), "Duplicate world pipeline/phase request");
        checkWorldVariant(requests, RenderPipelines.ARMOR_CUTOUT_NO_CULL_GLINT, ShaderKey.ENTITIES_CUTOUT_GLINT_ARMOR);
        checkWorldVariant(requests, RenderPipelines.CUTOUT_BLOCK, ShaderKey.TERRAIN_CUTOUT);
        warmup.runOnce(requests, request -> {
            compiled.computeIfAbsent(adapt(request), ignored -> { compilations.incrementAndGet(); return new Object(); });
            return true;
        }, (request, failure) -> { throw new AssertionError(failure); });
        int firstCompiles = compilations.get();
        for (int frame = 0; frame < 1000; frame++) {
            warmup.runOnce(requests, request -> { throw new AssertionError("Warmup repeated in a later frame"); },
                    (request, failure) -> { throw new AssertionError(failure); });
            for (var request : requests) check(compiled.containsKey(adapt(request)), "Later draw missed the real MRT pipeline identity");
        }
        check(warmup.attempted() && warmup.warmed() == requests.size() && compilations.get() == firstCompiles, "Warmup statistics changed");
        check(requests.stream().anyMatch(request -> request.pipeline() == RenderPipelines.ENTITY_CUTOUT_CULL), "Measured entity variant omitted");
        check(requests.stream().anyMatch(request -> request.pipeline() == RenderPipelines.TRANSLUCENT_PARTICLE), "Death/effect particles omitted");

        var expectedShadow = List.of(RenderPipelines.ENTITY_CUTOUT, RenderPipelines.ENTITY_CUTOUT_CULL,
                RenderPipelines.ENTITY_CUTOUT_DISSOLVE, RenderPipelines.ENTITY_TRANSLUCENT,
                RenderPipelines.ENTITY_TRANSLUCENT_CULL, RenderPipelines.ENTITY_SOLID,
                RenderPipelines.ITEM_CUTOUT, RenderPipelines.ITEM_TRANSLUCENT,
                RenderPipelines.ARMOR_CUTOUT_NO_CULL, RenderPipelines.EYES, RenderPipelines.GLINT);
        var shadowRequests = IrisVulkanPipelineWarmup.variants(true);
        check(shadowRequests.stream().map(IrisVulkanPipelineWarmup.Variant::pipeline).toList().equals(expectedShadow),
                "World-only additions changed the preexisting shadow descriptor list/order");
        check(shadowRequests.stream().allMatch(request -> request.phase() == WorldRenderingPhase.ENTITIES),
                "Shadow warmup phase changed");
        var shadow = new IrisVulkanPipelineWarmup();
        shadow.runOnce(shadowRequests, request -> {
            if (IrisVulkanShadowDrawPolicy.shouldSkip(true, request.pipeline())) return false;
            check(request.pipeline() != RenderPipelines.GLINT, "Decorative glint reached the shadow compiler");
            return true;
        }, (request, failure) -> { throw new AssertionError(failure); });
        check(shadow.attempted() && shadow.warmed() == 10 && shadow.failures() == 0, "Unexpected physical shadow variant count");
        shadow.runOnce(shadowRequests, request -> { throw new AssertionError("Shadow warmup repeated"); },
                (request, failure) -> { throw new AssertionError(failure); });

        var failed = new IrisVulkanPipelineWarmup();
        AtomicInteger failures = new AtomicInteger();
        failed.runOnce(requests, request -> { throw new IllegalStateException("Optional variant unsupported"); },
                (request, failure) -> failures.incrementAndGet());
        failed.runOnce(requests, request -> { throw new AssertionError("Failed warmup retried each frame"); },
                (request, failure) -> { throw new AssertionError(failure); });
        check(failed.attempted() && failed.warmed() == 0 && failed.failures() == requests.size()
                && failures.get() == requests.size(), "Failed warmup was reported as successful");

        var mixed = new IrisVulkanPipelineWarmup();
        AtomicInteger attempted = new AtomicInteger(), reported = new AtomicInteger();
        mixed.runOnce(requests, request -> {
            int index = attempted.getAndIncrement();
            if (index % 3 == 0) throw new IllegalStateException("Optional variant unsupported");
            return index % 3 == 1;
        }, (request, failure) -> reported.incrementAndGet());
        check(mixed.attempted() && attempted.get() == 21 && mixed.warmed() == 7
                && mixed.failures() == 7 && reported.get() == 7,
                "Failed/skipped variants must not abort later work or count as warmed");
        mixed.runOnce(requests, request -> { throw new AssertionError("Mixed warmup retried"); },
                (request, failure) -> { throw new AssertionError(failure); });

        // Production integration must retain the actual setPipeline path and must never draw or clear.
        ClassNode source = new ClassNode();
        try (var input = IrisVulkanPipelineWarmupTest.class.getResourceAsStream("IrisVulkanPipelineWarmup.class")) {
            new ClassReader(input).accept(source, 0);
        }
        int setPipeline = 0;
        for (var method : source.methods) for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
            check(!call.name.startsWith("draw") && !call.name.startsWith("clear") && !call.name.startsWith("setUniform")
                    && !call.name.startsWith("writeTo"), "Warmup must not draw, clear attachments, or upload uniforms");
            if (call.name.equals("setPipeline")) {
                check(call.owner.equals("com/mojang/renderpearl/api/commands/RenderPass") && call.getOpcode() == Opcodes.INVOKEINTERFACE,
                        "Warmup bypassed the genuine render-pass pipeline adaptation");
                setPipeline++;
            }
        }
        check(setPipeline == 1, "Missing actual render-pass setPipeline call");
        System.out.println("PASS: real LOAD-only descriptors at four sizes; " + requests.size()
                + " unique world requests include exact armor-glint/cutout-block descriptors in ENTITIES with measured shader keys; "
                + "real MRT cache identities reused across 1000 later frames; unchanged 11 shadow requests/10 physical variants; "
                + "successful, failed, skipped, and mixed run-once behavior; production RenderPearl setPipeline without draws, clears, or uniform uploads");
    }

    private static void checkWorldVariant(List<IrisVulkanPipelineWarmup.Variant> requests, RenderPipeline pipeline, ShaderKey expected) {
        check(requests.stream().filter(request -> request.pipeline() == pipeline).count() == 1,
                "Measured descriptor must occur exactly once: " + pipeline.getLocation());
        var request = requests.stream().filter(candidate -> candidate.pipeline() == pipeline).findFirst().orElseThrow();
        check(request.phase() == WorldRenderingPhase.ENTITIES, "Measured descriptor warmed in the wrong phase");
        check(shaderKey(request) == expected, "Measured descriptor mapped to the wrong shader key");
    }

    private static ShaderKey shaderKey(IrisVulkanPipelineWarmup.Variant request) {
        return IrisVulkanPhaseContext.mapShaderKey(IrisPipelines.getPipeline(null, request.pipeline()), request.phase());
    }

    private static RenderPipeline adapt(IrisVulkanPipelineWarmup.Variant request) {
        ShaderKey key = shaderKey(request);
        return IrisVulkanWorldPipelineStates.adapt(request.pipeline(), key, null,
                List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT), new int[]{0, 4});
    }
    private static GpuTextureView view(int width, int height) {
        return new GpuTextureView() {
            @Override public GpuTexture texture() { throw new AssertionError("Warmup need not access the backing texture"); }
            @Override public int baseMipLevel() { return 0; }
            @Override public int mipLevels() { return 1; }
            @Override public int getWidth(int level) { return width; }
            @Override public int getHeight(int level) { return height; }
            @Override public void close() { }
            @Override public boolean isClosed() { return false; }
        };
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
