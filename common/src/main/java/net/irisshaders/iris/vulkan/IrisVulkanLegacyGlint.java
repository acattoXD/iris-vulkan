package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.resources.Identifier;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Preserve a pack's legacy ArmorGlint pass when 26.3 combines foil with its base mesh. */
public final class IrisVulkanLegacyGlint {
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*");
    private static final Pattern GLINT_CALL = Pattern.compile("\\bmc_(?:hasGlint|sampleGlint)\\s*\\(");
    private static final Pattern FUNCTION_DECLARATION = Pattern.compile("\\b(?:bool|vec3)\\s*$");
    private static final Map<ProgramSet, EnumMap<ShaderKey, Boolean>> POLICIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<RenderPipeline, RenderPipeline> OVERLAYS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static long replayCount;

    private IrisVulkanLegacyGlint() { }

    public static boolean active() { return REPLAYING.get(); }
    public static long replayCount() { return replayCount; }

    public static RenderPipeline overlayFor(RenderPipeline original, PreparedRenderType prepared) {
        if (!original.getShaderDefines().flags().contains("GLINT")) return null;
        if (active() || IrisVulkanShadowRenderer.active()) return null;
        var world = IrisVulkanPhaseContext.pipeline();
        if (world == null || !world.shouldOverrideShaders()) return null;
        ShaderKey key = IrisVulkanPhaseContext.mapPipeline(original);
        if (key == null || key == ShaderKey.GLINT || !key.isGlint()) return null;
        boolean hasGlintTexture = prepared.textures().stream().anyMatch(texture -> texture.name().equals("GlintSampler"));
        if (!hasGlintTexture) return null;
        ProgramSet programs = world.getProgramSet();
        boolean ownsIntegratedGlint = POLICIES.computeIfAbsent(programs, ignored -> new EnumMap<>(ShaderKey.class))
                .computeIfAbsent(key, ignored -> new ProgramFallbackResolver(programs).resolve(key.getProgram())
                        .map(source -> consumesGlintApi(source.getVertexSource().orElse(null), source.getFragmentSource().orElse(null)))
                        .orElse(false));
        if (!shouldReplay(key, false, hasGlintTexture, ownsIntegratedGlint)) return null;
        return OVERLAYS.computeIfAbsent(original, IrisVulkanLegacyGlint::overlayPipeline);
    }

    public static boolean shouldReplay(ShaderKey key, boolean shadow, boolean hasGlintTexture, boolean consumesApi) {
        return !shadow && key != null && key != ShaderKey.GLINT && key.isGlint() && hasGlintTexture && !consumesApi;
    }

    /** Inspect authored sources before Iris injects its compatibility function definitions. */
    public static boolean consumesGlintApi(String... sources) {
        for (String source : sources) {
            if (source == null) continue;
            String code = COMMENTS.matcher(source).replaceAll(" ");
            var calls = GLINT_CALL.matcher(code);
            while (calls.find()) {
                // A prototype/fallback function declaration alone does not draw foil.
                if (!FUNCTION_DECLARATION.matcher(code.substring(0, calls.start())).find()) return true;
            }
        }
        return false;
    }

    /** Nested replay is excluded even if another draw wrapper recursively submits geometry. */
    public static void replay(RenderPipeline overlay, Consumer<RenderPipeline> draw) {
        if (overlay == null || active()) return;
        REPLAYING.set(true);
        try {
            draw.accept(overlay);
            replayCount++;
        } finally {
            REPLAYING.remove();
        }
    }

    public static RenderPipeline overlayPipeline(RenderPipeline original) {
        VertexFormat[] formats = original.getVertexFormatBindings().toArray(VertexFormat[]::new);
        for (int slot = 0; slot < formats.length; ++slot) {
            if (formats[slot] != null && (formats[slot].contains("UV3") || formats[slot].contains("iris_UV3"))) {
                formats[slot] = overlayVertexFormat(formats[slot]);
            }
        }
        RenderPipeline overlay = new OverlayPipeline(original, formats);
        IrisPipelines.copyPipeline(RenderPipelines.GLINT, overlay);
        return overlay;
    }

    /** Reinterpret the same bytes: special foil uses UV3, while its base texture keeps UV0. */
    public static VertexFormat overlayVertexFormat(VertexFormat original) {
        if (!original.contains("UV3") && !original.contains("iris_UV3")) return original;
        VertexFormat.Builder builder = VertexFormat.builder(original.getStepRate());
        var elements = original.getElements();
        for (int first = 0; first < elements.size();) {
            var element = elements.get(first);
            int end = first + 1;
            while (end < elements.size() && elements.get(end).name().equals(element.name())) end++;
            int columns = end - first;
            int stride = columns > 1 ? elements.get(first + 1).offset() - element.offset()
                    : (end < elements.size() ? elements.get(end).offset() : original.getVertexSize()) - element.offset();
            String name = switch (element.name()) {
                case "UV0", "iris_UV0" -> "iris_LegacyGlintBaseUV";
                case "UV3", "iris_UV3" -> "UV0";
                default -> element.name();
            };
            builder.addAttribute(name, element.offset(), stride, element.format(), columns);
            first = end;
        }
        return builder.build();
    }

    private static final class OverlayPipeline extends RenderPipeline {
        OverlayPipeline(RenderPipeline original, VertexFormat[] formats) {
            super(Identifier.fromNamespaceAndPath("iris", "vulkan/legacy_glint/" + original.getLocation().getNamespace() + "/" + original.getLocation().getPath()),
                    RenderPipelines.GLINT.getShaders(), RenderPipelines.GLINT.getShaderDefines(), RenderPipelines.GLINT.getBindGroupLayouts(),
                    RenderPipelines.GLINT.getColorTargetStates().toArray(ColorTargetState[]::new), RenderPipelines.GLINT.getDepthStencilState(),
                    RenderPipelines.GLINT.getPolygonMode(), RenderPipelines.GLINT.isCull(), formats, original.getPrimitiveTopology(),
                    RenderPipelines.GLINT.pushConstantSize(), original.getSortKey());
        }
    }
}
