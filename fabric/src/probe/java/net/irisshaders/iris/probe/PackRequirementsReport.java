package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanDeviceAccessor;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.vulkan.IrisVulkanScreenPassGraph;
import net.irisshaders.iris.vulkan.IrisVulkanScreenPassPlanner;
import net.irisshaders.iris.vulkan.IrisVulkanShaderResources;
import net.irisshaders.iris.vulkan.IrisVulkanShaderSourceMap;
import net.irisshaders.iris.vulkan.IrisVulkanShadowSampling;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.irisshaders.iris.vulkan.IrisVulkanUniformSnapshot;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runtime inspection of the pack after Iris has applied the selected options,
 * includes, dimension, feature macros, and program enable expressions. Never
 * launches a client, compiles a GPU pipeline, or replaces the loaded pack.
 * Call only in the isolated audit probe, where shader execution is disabled.
 */
public final class PackRequirementsReport {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern UNIFORM = Pattern.compile(
            "(?m)^\\h*(?:layout\\h*\\([^)]*\\)\\h*)?uniform\\h+(?:(?:lowp|mediump|highp|coherent|volatile|restrict|readonly|writeonly)\\h+)*([A-Za-z_]\\w*)\\h+([^;{}]+);"
    );
    private static final Pattern DECLARATOR = Pattern.compile("([A-Za-z_]\\w*)\\s*((?:\\[[^]]*]\\s*)*)(?:=[\\s\\S]*)?");
    private static final Pattern INPUT = Pattern.compile(
            "(?m)^\\h*(?:layout\\h*\\([^)]*\\)\\h*)?(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise)\\h+)*(?:in|attribute)\\h+(?:(?:lowp|mediump|highp)\\h+)?([A-Za-z_]\\w*)\\h+([A-Za-z_]\\w*)\\h*((?:\\[[^]]*]\\h*)*);"
    );
    private static final Pattern RESOURCE_BLOCK = Pattern.compile(
            "(?m)^\\h*(?:layout\\h*\\([^)]*\\)\\h*)?(?:(?:readonly|writeonly|coherent|volatile|restrict)\\h+)*(uniform|buffer)\\h+([A-Za-z_]\\w*)\\h*\\{"
    );

    private PackRequirementsReport() { }

    public static void write(Path run, ProgramSet programs) throws IOException {
        Path root = run.resolve("active-programs");
        Files.createDirectories(root);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pack", Iris.getCurrentPackName());
        report.put("dimension", String.valueOf(Iris.getCurrentDimension()));
        report.put("profile", programs.getPack().getProfileInfo());
        report.put("evidenceScope", "Active preprocessed ProgramSet and CPU shader transformations. Declarations are reported before GPU dead-code elimination; this is not rendering validation.");
        CompilationAudit compilation = CompilationAudit.create();
        report.put("nativeCompileAudit", compilation.summary());

        var options = programs.getPack().getShaderPackOptions().getOptionValues();
        Map<String, Object> selectedOptions = new LinkedHashMap<>();
        options.getOptionSet().getBooleanOptions().keySet().stream().sorted().forEach(name ->
                selectedOptions.put(name, options.getBooleanValueOrDefault(name)));
        options.getOptionSet().getStringOptions().keySet().stream().sorted().forEach(name ->
                selectedOptions.put(name, options.getStringValueOrDefault(name)));
        report.put("selectedOptions", selectedOptions);

        List<Object> active = new ArrayList<>();
        List<Object> computes = new ArrayList<>();
        Set<Integer> drawTargets = new LinkedHashSet<>();
        for (ProgramId id : ProgramId.values()) {
            var source = programs.get(id);
            if (source.isPresent()) active.add(program(root, source.get(), id.getGroup().name(), drawTargets));
        }
        for (ProgramArrayId id : ProgramArrayId.values()) {
            for (ProgramSource source : programs.getComposite(id)) {
                if (source != null && source.isValid()) active.add(program(root, source, id.name(), drawTargets));
            }
            for (ComputeSource[] group : programs.getCompute(id)) addComputes(root, computes, group, id.name());
        }
        addComputes(root, computes, programs.getSetup(), "setup");
        addComputes(root, computes, programs.getShadowCompute(), "shadow");
        addComputes(root, computes, programs.getFinalCompute(), "final");
        report.put("activeProgramCount", active.size());
        report.put("activePrograms", active);
        report.put("activeComputeCount", computes.size());
        report.put("activeComputes", computes);
        report.put("drawTargetIndices", drawTargets);

        List<Object> targets = new ArrayList<>();
        programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings().forEach((index, settings) -> {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("index", index);
            target.put("format", settings.getInternalFormat().name());
            target.put("clear", settings.shouldClear());
            target.put("clearColor", settings.getClearColor().map(Object::toString).orElse("default"));
            target.put("declaredDrawTarget", drawTargets.contains(index));
            targets.add(target);
        });
        report.put("colorTargets", targets);

        var shadow = programs.getPackDirectives().getShadowDirectives();
        Map<String, Object> shadowSettings = new LinkedHashMap<>();
        shadowSettings.put("enabled", shadow.isShadowEnabled().toString());
        shadowSettings.put("resolution", shadow.getResolution());
        shadowSettings.put("distance", shadow.getDistance());
        shadowSettings.put("near", shadow.getNearPlane());
        shadowSettings.put("far", shadow.getFarPlane());
        shadowSettings.put("terrain", shadow.shouldRenderTerrain());
        shadowSettings.put("translucent", shadow.shouldRenderTranslucent());
        shadowSettings.put("entities", shadow.shouldRenderEntities());
        shadowSettings.put("blockEntities", shadow.shouldRenderBlockEntities());
        shadowSettings.put("depthSampling", shadow.getDepthSamplingSettings());
        shadowSettings.put("colorSampling", shadow.getColorSamplingSettings());
        report.put("shadow", shadowSettings);

        Map<String, Object> textures = new LinkedHashMap<>();
        programs.getPack().getCustomTextureDataMap().forEach((stage, names) -> {
            Map<String, Object> byName = new LinkedHashMap<>();
            names.forEach((name, data) -> byName.put(name, texture(data)));
            textures.put(stage.name(), byName);
        });
        Map<String, Object> globalTextures = new LinkedHashMap<>();
        programs.getPack().getIrisCustomTextureDataMap().forEach((name, data) -> globalTextures.put(name, texture(data)));
        report.put("stageCustomTextures", textures);
        report.put("globalCustomTextures", globalTextures);
        report.put("customNoise", texture(programs.getPack().getCustomNoiseTexture()));
        report.put("customImages", programs.getPack().getIrisCustomImages());
        report.put("bufferObjects", programs.getPack().getBufferObjects().toString());

        // Build the same typed, CPU-only uniform sources as the production renderer.
        // No values are evaluated here, so this is safe with the audit's world pipeline disabled.
        var custom = IrisVulkanUniformSnapshot.createCustomUniforms(programs, new FrameUpdateNotifier());
        IrisVulkanUniformSnapshot.registerActiveCustomUniforms(custom);
        try {
            report.put("worldShaderVariants", worldVariants(root, programs, compilation));
            report.put("screenShaderVariants", screenVariants(root, programs, compilation));
        } finally {
            IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(custom);
        }

        Files.writeString(run.resolve("active-program-requirements.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static Map<String, Object> program(Path root, ProgramSource source, String group,
                                             Set<Integer> targets) throws IOException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", source.getName());
        item.put("group", group);
        var directives = source.getDirectives();
        item.put("drawBuffers", directives.getDrawBuffers());
        item.put("unknownDrawBuffers", directives.hasUnknownDrawBuffers());
        item.put("viewport", directives.getViewportScale());
        item.put("mipmappedBuffers", directives.getMipmappedBuffers());
        item.put("explicitFlips", directives.getExplicitFlips());
        item.put("blend", directives.getBlendModeOverride().map(Object::toString).orElse("inherited"));
        item.put("bufferBlendOverrides", directives.getBufferBlendOverrides());
        Arrays.stream(directives.getDrawBuffers()).forEach(targets::add);
        Map<String, Object> stages = new LinkedHashMap<>();
        stage(root, source.getName(), "vsh", source.getVertexSource().orElse(null), stages);
        stage(root, source.getName(), "fsh", source.getFragmentSource().orElse(null), stages);
        stage(root, source.getName(), "gsh", source.getGeometrySource().orElse(null), stages);
        stage(root, source.getName(), "tcs", source.getTessControlSource().orElse(null), stages);
        stage(root, source.getName(), "tes", source.getTessEvalSource().orElse(null), stages);
        item.put("stages", stages);
        return item;
    }

    private static void addComputes(Path root, List<Object> result, ComputeSource[] sources, String group) throws IOException {
        for (ComputeSource source : sources) {
            if (source == null || !source.isValid()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", source.getName());
            item.put("group", group);
            item.put("workGroups", String.valueOf(source.getWorkGroups()));
            item.put("workGroupsRelative", String.valueOf(source.getWorkGroupRelative()));
            item.put("indirectPointer", String.valueOf(source.getIndirectPointer()));
            stage(root, source.getName(), "csh", source.getSource().orElse(null), item);
            result.add(item);
        }
    }

    private static void stage(Path root, String name, String stage, String source, Map<String, Object> target) throws IOException {
        if (source == null) return;
        Files.createDirectories(root);
        Path file = root.resolve(name + "." + stage);
        Files.writeString(file, source);
        Map<String, Object> item = declarations(source);
        item.put("source", file.toString());
        item.put("characters", source.length());
        target.put(stage, item);
    }

    private static Map<String, Object> declarations(String source) {
        source = COMMENTS.matcher(source).replaceAll("");
        Map<String, Object> result = new LinkedHashMap<>();
        List<Object> uniforms = new ArrayList<>();
        List<Object> samplers = new ArrayList<>();
        List<Object> images = new ArrayList<>();
        var matcher = UNIFORM.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            // GLSL uniform initializers in active shaderpack sources do not contain commas.
            for (String declaration : matcher.group(2).split(",")) {
                var name = DECLARATOR.matcher(declaration.trim());
                if (!name.matches()) continue;
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", name.group(1));
                field.put("type", type);
                field.put("array", name.group(2).trim());
                long occurrences = Pattern.compile("\\b" + Pattern.quote(name.group(1)) + "\\b").matcher(source).results().count();
                field.put("referencesOutsideDeclaration", Math.max(0, occurrences - 1));
                if (type.contains("sampler")) samplers.add(field);
                else if (type.contains("image") || type.startsWith("subpassInput")) images.add(field);
                else uniforms.add(field);
            }
        }
        List<Object> inputs = new ArrayList<>();
        var input = INPUT.matcher(source);
        while (input.find()) inputs.add(Map.of("name", input.group(2), "type", input.group(1), "array", input.group(3).trim()));
        List<Object> blocks = new ArrayList<>();
        var block = RESOURCE_BLOCK.matcher(source);
        while (block.find()) blocks.add(Map.of("kind", block.group(1), "name", block.group(2)));
        result.put("uniforms", uniforms);
        result.put("samplers", samplers);
        result.put("images", images);
        result.put("inputs", inputs);
        result.put("blocks", blocks);
        return result;
    }

    private static List<Object> worldVariants(Path root, ProgramSet programs, CompilationAudit compilation) throws IOException {
        List<Object> result = new ArrayList<>();
        var previousFormat = WorldRenderingSettings.INSTANCE.getVertexFormat();
        // The audit renders with shaders disabled, so its active world pipeline has
        // not selected the native renderer's material-bearing terrain format.
        WorldRenderingSettings.INSTANCE.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));
        try {
        var sourceMap = new IrisVulkanShaderSourceMap(programs);
        for (ShaderKey key : ShaderKey.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key.name());
            item.put("resolvedProgram", sourceMap.resolvedProgramName(key));
            item.put("directSource", sourceMap.hasDirectSource(key));
            try {
                var source = sourceMap.getSource(key);
                var directives = sourceMap.getDirectives(key);
                int count = directives == null ? 1 : Math.max(1, directives.getDrawBuffers().length);
                item.put("drawBuffers", directives == null ? new int[] {0} : directives.getDrawBuffers());
                stage(root.resolve("transformed"), key.getName(), "vsh", source.vertex(), item);
                stage(root.resolve("transformed"), key.getName(), "fsh", source.fragment(), item);
                String vertex = IrisVulkanShadowSampling.patch(programs, source.vertex(), false);
                String fragment = IrisVulkanShadowSampling.patch(programs, source.fragment(), true);
                if (key.isShadow()) vertex = IrisVulkanShadowRenderer.patchVertexDepth(vertex);
                var base = worldBasePipeline(key, count);
                item.put("baseVertexFormat", String.valueOf(base.getVertexFormatBinding(0)));
                var prepared = IrisVulkanShaderResources.prepareGbufferPass(base,
                        key, vertex, fragment, count);
                item.put("resources", prepared.resources());
                stage(root.resolve("prepared"), key.getName(), "vsh", prepared.vertex(), item);
                stage(root.resolve("prepared"), key.getName(), "fsh", prepared.fragment(), item);
                item.put("preparation", "accepted by current CPU resource transformation");
                compilation.check("world/" + key.getName(), prepared, item);
            } catch (RuntimeException exception) {
                item.put("preparation", "failed");
                item.put("failure", exception.toString());
            }
            result.add(item);
        }
        return result;
        } finally {
            WorldRenderingSettings.INSTANCE.setVertexFormat(previousFormat);
        }
    }

    private static List<Object> screenVariants(Path root, ProgramSet programs, CompilationAudit compilation) throws IOException {
        List<Object> result = new ArrayList<>();
        IrisVulkanScreenPassGraph graph = IrisVulkanScreenPassPlanner.create(programs);
        try {
            for (var node : graph.nodes()) {
                if (node.vertexSource() == null && node.fragmentSource() == null && node.failureReason().contains("invalid program source")) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", node.sourceName());
                item.put("stage", node.kind().name());
                item.put("drawBuffers", node.drawBuffers());
                item.put("plannerStatus", node.status().name());
                item.put("plannerFailure", node.failureReason());
                if (node.ready()) {
                    stage(root.resolve("screen-transformed"), node.sourceName(), "vsh", node.vertexSource(), item);
                    stage(root.resolve("screen-transformed"), node.sourceName(), "fsh", node.fragmentSource(), item);
                    try {
                        var prepared = IrisVulkanShaderResources.prepareScreenPass(basePipeline("screen_" + node.sourceName(),
                                Math.max(1, node.drawBuffers().length)), node.vertexSource(), node.fragmentSource(), node.collapseOutputs());
                        item.put("resources", prepared.resources());
                        item.put("preparation", "accepted by current CPU resource transformation");
                        stage(root.resolve("screen-prepared"), node.sourceName(), "vsh", prepared.vertex(), item);
                        stage(root.resolve("screen-prepared"), node.sourceName(), "fsh", prepared.fragment(), item);
                        compilation.check("screen/" + node.sourceName(), prepared, item);
                    } catch (RuntimeException exception) {
                        item.put("preparation", "failed");
                        item.put("failure", exception.toString());
                    }
                }
                result.add(item);
            }
        } finally {
            graph.destroy();
        }
        return result;
    }

    private static RenderPipeline basePipeline(String name, int colors) {
        return basePipeline(name, colors, DefaultVertexFormat.POSITION_TEX);
    }

    private static RenderPipeline worldBasePipeline(ShaderKey key, int colors) {
        VertexFormat format = key.getVertexFormat();
        if (key.patch == Patch.SODIUM) {
            format = WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat();
        } else if (format == IrisVertexFormats.ENTITY) {
            format = RenderPipelines.ENTITY_CUTOUT.getVertexFormatBinding(0);
        } else if (format == IrisVertexFormats.TERRAIN) {
            format = RenderPipelines.SOLID_BLOCK.getVertexFormatBinding(0);
        } else if (format == IrisVertexFormats.GLYPH) {
            format = RenderPipelines.TEXT.getVertexFormatBinding(0);
        }
        return basePipeline(key.getName(), colors, format == null ? DefaultVertexFormat.POSITION_TEX : format);
    }

    private static RenderPipeline basePipeline(String name, int colors, VertexFormat vertexFormat) {
        var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("iris_probe", "requirements/" + name))
                .withVertexShader("core/screenquad").withFragmentShader("core/blit_screen")
                .withVertexBinding(0, vertexFormat)
                .withPrimitiveTopology(PrimitiveTopology.QUADS);
        for (int i = 0; i < colors; i++) builder.withColorTargetState(i, ColorTargetState.DEFAULT);
        return builder.build();
    }

    private static final class CompilationAudit {
        private final GlslCompiler compiler;
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final List<Object> failures = new ArrayList<>();
        private int attempted, passed;

        private CompilationAudit(GlslCompiler compiler) {
            this.compiler = compiler;
            summary.put("requested", compiler != null);
            summary.put("scope", "Actual native device GlslCompiler.createIntermediary for each prepared vertex/fragment stage; no linked-pipeline or draw validation. World variants use the corresponding native vertex format with empty pipeline defines.");
            summary.put("attemptedStages", 0);
            summary.put("passedStages", 0);
            summary.put("failedStages", failures);
        }

        static CompilationAudit create() {
            if (!Boolean.getBoolean("iris.vulkan.probe.compileAudit")) return new CompilationAudit(null);
            if (!(IrisBackend.getBackend(RenderSystem.getDevice()) instanceof VulkanDevice device)) {
                throw new IllegalStateException("Native shader compilation audit requires the active Vulkan device");
            }
            return new CompilationAudit(((VKOnly_VulkanDeviceAccessor) device).iris$getGlslCompiler());
        }

        Map<String, Object> summary() { return summary; }

        void check(String label, IrisVulkanShaderResources.Prepared prepared, Map<String, Object> report) {
            if (compiler == null) return;
            Map<String, Object> results = new LinkedHashMap<>();
            results.put("vertex", stage(label, ShaderType.VERTEX, prepared.vertex()));
            results.put("fragment", stage(label, ShaderType.FRAGMENT, prepared.fragment()));
            report.put("nativeCompilation", results);
        }

        private Map<String, Object> stage(String label, ShaderType type, String source) {
            Map<String, Object> result = new LinkedHashMap<>();
            long started = System.nanoTime();
            attempted++;
            try (var module = compiler.createIntermediary("iris_probe/" + label + "/" + type.name().toLowerCase(), source, type)) {
                result.put("status", "PASS");
                passed++;
            } catch (ShaderCompileException | RuntimeException failure) {
                result.put("status", "FAIL");
                result.put("error", failure.toString());
                failures.add(Map.of("variant", label, "stage", type.name(), "error", failure.toString()));
            } finally {
                result.put("durationMillis", (System.nanoTime() - started) / 1_000_000L);
                summary.put("attemptedStages", attempted);
                summary.put("passedStages", passed);
            }
            return result;
        }
    }

    private static Object texture(CustomTextureData data) {
        if (data == null) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", data.getClass().getSimpleName());
        if (data instanceof CustomTextureData.PngData png) item.put("bytes", png.getContent().length);
        if (data instanceof CustomTextureData.ResourceData resource) item.put("resource", resource.getNamespace() + ":" + resource.getLocation());
        if (data instanceof CustomTextureData.RawData raw) {
            item.put("bytes", raw.getContent().length);
            item.put("format", raw.getInternalFormat().name());
            item.put("pixelFormat", raw.getPixelFormat().name());
            item.put("pixelType", raw.getPixelType().name());
        }
        if (data instanceof CustomTextureData.RawData1D raw) item.put("width", raw.getSizeX());
        if (data instanceof CustomTextureData.RawData2D raw) {
            item.put("width", raw.getSizeX()); item.put("height", raw.getSizeY());
        }
        if (data instanceof CustomTextureData.RawData3D raw) {
            item.put("width", raw.getSizeX()); item.put("height", raw.getSizeY()); item.put("depth", raw.getSizeZ());
        }
        return item;
    }
}
