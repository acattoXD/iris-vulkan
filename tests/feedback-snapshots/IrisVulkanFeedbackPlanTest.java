package net.irisshaders.iris.vulkan;

import java.util.*;

/** Validate read/write alias semantics independently of graphics-driver timing. */
public final class IrisVulkanFeedbackPlanTest {
    private static int checks;
    private static IrisVulkanFeedbackPlan.Program program(int[] writes, String... sources) {
        return new IrisVulkanFeedbackPlan.Program("fixture", writes, Arrays.asList(sources));
    }
    private static IrisVulkanFeedbackPlan plan(IrisVulkanFeedbackPlan.Program... programs) {
        return IrisVulkanFeedbackPlan.create(List.of(programs), sampler -> false);
    }
    private static void check(boolean condition, String reason) {
        checks++; if (!condition) throw new AssertionError(reason);
    }
    private static String read(String sampler) {
        return "uniform sampler2D " + sampler + "; vec4 fetchValue(){ return texture(" + sampler + ", vec2(0.5)); }";
    }
    public static void main(String[] args) {
        for(int target=0; target<IrisVulkanTargetIndices.LOGICAL_TARGET_COUNT; target++) {
            var readWrite=plan(program(new int[]{target}, read("colortex"+target)));
            check(readWrite.needs(target), "Missing self-read snapshot"+target);
            int other=(target+1)%IrisVulkanTargetIndices.LOGICAL_TARGET_COUNT;
            check(!readWrite.needs(other), "Snapshot unrelated target"+other);
            check(plan(program(new int[]{target},"uniform sampler2D colortex"+target+";")).needs(target)==(target==4),
                "An unused declaration alone is not a read; preserve producer fallback4");
        }
        String[] aliases={"gcolor","gdepth","gnormal","composite","gaux1","gaux2","gaux3","gaux4"};
        for(int target=0;target<aliases.length;target++) {
            check(plan(program(new int[]{target},read(aliases[target]))).needs(target), "Legacy alias"+aliases[target]);
        }
        var readOnly=plan(program(new int[]{0,3,6},read("gaux2"),read("gaux4")));
        check(readOnly.targets().isEmpty(),"Glass's read-only gaux2/gaux4 must keep live post-deferred views; no feedback copy needed");
        var custom=IrisVulkanFeedbackPlan.create(List.of(program(new int[]{7},read("gaux4"))),"gaux4"::equals);
        check(!custom.needs(7),"Custom gaux4 must take precedence over framebuffer7");
        var mixed=IrisVulkanFeedbackPlan.create(List.of(program(new int[]{7},read("gaux4"),read("colortex7"))),"gaux4"::equals);
        check(mixed.needs(7),"A custom alias does not replace a separate logical sampler of the same target");
        var late=plan(program(new int[]{0},"void main(){}"),program(new int[]{6},read("colortex6")));
        check(late.needs(6),"Late first-use pipeline needs a snapshot before compilation or any draw");
        for(int stage=0;stage<5;stage++) {
            String[] sources={"void main(){}","void main(){}","void main(){}","void main(){}","void main(){}"};
            sources[stage]=read("colortex2");
            check(plan(program(new int[]{2},sources)).needs(2),"Vertex/geometry/tessellation/fragment stage"+stage);
        }
        check(!plan(program(new int[]{6},"// colortex6\n/* uniform sampler2D colortex6; */\nvoid main(){}" )).needs(6),"Comment is not a framebuffer read");
        check(plan(program(new int[]{4},"void main(){}" )).needs(4),"Unknown producer albedo binding must retain target4 fallback");
        check(IrisVulkanFeedbackPlan.create(null,s->false).conservativeFallback(),"Unknown program list copies all");
        check(IrisVulkanFeedbackPlan.create(List.of(program(new int[]{1},"x")),null).conservativeFallback(),"Unknown sampler precedence copies all");
        check(plan(program(new int[]{1},"#define JOIN(a,b) a##b\nuniform sampler2D JOIN(colortex,1);")).conservativeFallback(),"Unexpanded shader macros copy all");
        check(plan(program(new int[]{1},"#include \"later.glsl\"")).conservativeFallback(),"Unknown included stage copies all");
        check(plan(program(new int[]{1},"uniform sampler2D colortex999;")).conservativeFallback(),"Unknown sampler contract copies all");
        check(plan(program(new int[]{33},"x")).conservativeFallback(),"Invalid output contract must not silently suppress snapshots");
        check(plan(program(new int[]{1},(String)null)).conservativeFallback(),"Missing stage copies all");
        for(int i=0;i<IrisVulkanTargetIndices.LOGICAL_TARGET_COUNT;i++) check(IrisVulkanFeedbackPlan.unknown("uncompiled unknown").needs(i),"Conservative fallback target"+i);
        // Reference full snapshot vs optimized snapshot across both boundaries and
        // repeated same-target writes. Reads must observe the boundary, not a prior draw.
        var hazard=plan(program(new int[]{6},read("colortex6")));
        int[] live={10,11,12,13,14,15,16,17}, frozen=live.clone(), optimized=new int[live.length];
        for(int boundary=0;boundary<2;boundary++) {
            for(int i=0;i<live.length;i++) { frozen[i]=live[i]; if(hazard.needs(i)) optimized[i]=live[i]; }
            live[6]+=20;
            check(optimized[6]==frozen[6],"Boundary snapshot must survive an earlier draw in the same stage");
            live[6]+=30;
            check(optimized[6]==frozen[6],"Repeated self reads use the same logical boundary");
        }
        check(!readOnly.needs(5),"Read-only glass/cloud-depth target is not a snapshot dependency");
        check(plan().targets().isEmpty(),"World-only built-in fallback without a target read needs no snapshot");
        check(!plan(program(new int[]{6},"layout(binding=0) uniform highp sampler2D colortex6; void main(){}" )).needs(6),"Unused qualified sampler declaration");
        check(plan(program(new int[]{6},"uniform sampler2D colortex6[2];" )).needs(6),"Array declarations stay conservative");
        check(plan(program(new int[]{6},"uniform sampler2D unrelated, colortex6;" )).needs(6),"Multiple declarators stay conservative");
        check(plan(program(new int[]{6},"uniform sampler2D colortex6; void forward(sampler2D s){} void main(){forward(colortex6);}" )).needs(6),"Passing a sampler to a function is a use");
        check(plan(program(new int[]{6},"uniform sampler2D colortex6; void main(){ivec2 s=textureSize(colortex6,0);}" )).needs(6),"Texture metadata queries remain conservative");
        System.out.println("PASS "+checks+" feedback hazard/precedence/stage/lazy-program/boundary checks");
    }
}
