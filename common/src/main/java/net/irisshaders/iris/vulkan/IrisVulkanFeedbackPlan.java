package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Conservative world read/write hazards, available before any lazy GPU compilation. */
final class IrisVulkanFeedbackPlan {
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern UNRESOLVED_PREPROCESSOR = Pattern.compile("(?m)^\\h*#\\h*(?:define|undef|include|if|ifdef|ifndef|elif|else|endif)\\b");
    private static final Pattern TARGET = Pattern.compile("\\b(?:colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\\b");
    private static final Pattern SIMPLE_SAMPLER_DECLARATION = Pattern.compile(
        "\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w+\\s+"
            + "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])\\s*;");
    private final Set<Integer> targets;
    private final boolean all;
    private final String reason;
    private final int programCount;

    private IrisVulkanFeedbackPlan(Set<Integer> targets, boolean all, String reason, int programCount) {
        this.targets = Collections.unmodifiableSet(new TreeSet<>(targets));
        this.all = all; this.reason = reason; this.programCount = programCount;
    }

    static IrisVulkanFeedbackPlan fromPrograms(ProgramSet programs) {
        if (programs == null || programs.getPack() == null) return unknown("No complete program set");
        List<Program> inputs = new ArrayList<>();
        // All valid world programs, not just pipelines already drawn/warmed up. A
        // resolved fallback uses the same source/directives as one of these entries.
        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() != ProgramGroup.Gbuffers) continue;
            programs.get(id).ifPresent(source -> {
                List<String> stages = new ArrayList<>();
                source.getVertexSource().ifPresent(stages::add);
                source.getGeometrySource().ifPresent(stages::add);
                source.getTessControlSource().ifPresent(stages::add);
                source.getTessEvalSource().ifPresent(stages::add);
                source.getFragmentSource().ifPresent(stages::add);
                inputs.add(new Program(source.getName(), source.getDirectives().getDrawBuffers(), stages));
            });
        }
        return create(inputs, sampler -> IrisVulkanCustomTextures.supports(programs.getPack(),
            TextureStage.GBUFFERS_AND_SHADOW, sampler));
    }

    static IrisVulkanFeedbackPlan create(Collection<Program> programs, Predicate<String> customTexture) {
        if (programs == null || customTexture == null) return unknown("Unknown world programs or sampler precedence");
        Set<Integer> hazards = new TreeSet<>();
        for (Program program : programs) {
            if (program == null || program.outputs() == null || program.stages() == null || program.stages().isEmpty())
                return unknown("Incomplete world shader source");
            try { IrisVulkanTargetIndices.validateDrawBuffers(program.name(), program.outputs()); }
            catch (IllegalArgumentException error) { return unknown("Unknown draw-buffer contract"); }
            Set<Integer> writes = new TreeSet<>();
            for (int output : program.outputs()) writes.add(output);
            // Producer bindings can be absent on a newly encountered pipeline.
            // Sampler0/InSampler/tex/texture then conservatively fall back to target4.
            // Keep that target even when the untransformed source has no such name.
            if (writes.contains(IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET)) hazards.add(IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET);
            for (String stage : program.stages()) {
                if (stage == null) return unknown("Unknown shader stage");
                String source = COMMENTS.matcher(stage).replaceAll(" ");
                if (UNRESOLVED_PREPROCESSOR.matcher(source).find()) return unknown("Shader preprocessing is incomplete");
                // Shared headers declare many samplers that a program never
                // references. A declaration alone cannot read an attachment.
                // Remove only simple, recognized declarations from this scan;
                // array/multi-declarator/unknown forms stay conservative. This
                // does not rewrite the source passed to the shader compiler.
                source = SIMPLE_SAMPLER_DECLARATION.matcher(source).replaceAll(declaration ->
                    IrisVulkanTargetIndices.colorSamplerTarget(declaration.group(1)) >= 0 ? " " : declaration.group());
                var names = TARGET.matcher(source);
                while (names.find()) {
                    String sampler = names.group();
                    int target = IrisVulkanTargetIndices.colorSamplerTarget(sampler);
                    if (target < 0) return unknown("Unrecognized logical sampler " + sampler);
                    // The actual world binder resolves supported custom resources
                    // before framebuffer aliases. Do not confuse custom gaux4 with7.
                    if (writes.contains(target) && !customTexture.test(sampler)) hazards.add(target);
                }
            }
        }
        return new IrisVulkanFeedbackPlan(hazards, false, "All preprocessed world-stage sources", programs.size());
    }

    static IrisVulkanFeedbackPlan unknown(String reason) { return new IrisVulkanFeedbackPlan(Set.of(), true, reason, 0); }
    boolean needs(int target) { return all || targets.contains(target); }
    Set<Integer> targets() { return targets; }
    boolean conservativeFallback() { return all; }
    String reason() { return reason; }
    int programCount() { return programCount; }
    record Program(String name, int[] outputs, List<String> stages) {
        Program { outputs = outputs == null ? null : outputs.clone(); stages = stages == null ? null : new ArrayList<>(stages); }
        @Override public int[] outputs() { return outputs == null ? null : outputs.clone(); }
    }
}
