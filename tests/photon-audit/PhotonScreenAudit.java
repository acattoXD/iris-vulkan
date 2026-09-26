package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.google.gson.GsonBuilder;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Bounded real first-deferred screen route attempt; missing live context is reported, never fabricated. */
public final class PhotonScreenAudit {
    public static void main(String[] args)throws Exception {
        Path output=Path.of(args[1]).resolve("screen-pass");Files.createDirectories(output);
        var report=new LinkedHashMap<String,Object>();
        report.put("scope","Real default/high Photon first deferred screen-pass planner and custom-uniform initialization; no GPU/game execution or synthetic uniform fields");
        try(var fs=FileSystems.newFileSystem(Path.of(args[0]))) {
            var pack=new ShaderPack(fs.getPath("/shaders"),ImmutableList.of(),true);Iris.currentPack=pack;
            var programs=pack.getProgramSet(DimensionId.OVERWORLD);
            report.put("profileInfo",pack.getProfileInfo());
            boolean uniformsReady=false;
            try {
                var custom=IrisVulkanUniformSnapshot.createCustomUniforms(programs,new FrameUpdateNotifier());
                IrisVulkanUniformSnapshot.registerActiveCustomUniforms(custom);uniformsReady=true;
                report.put("customUniforms","created by actual production initializer");
            } catch(Throwable failure) {
                report.put("customUniformInitializationBlocker",trace(failure));
            }
            var sources=programs.getComposite(ProgramArrayId.Deferred);
            ProgramSource first=java.util.Arrays.stream(sources).filter(s->s!=null&&s.isValid()).findFirst().orElseThrow();
            report.put("program",first.getName());
            try {
                var formats=IrisVulkanTargetFormat.resolveTargetFormats(programs.getPackDirectives().getRenderTargetDirectives(),null,
                    IrisVulkanGbufferTargets.COLOR_TARGET_COUNT,IrisVulkanGbufferTargets.FALLBACK_SCENE_TARGET);
                var method=IrisVulkanScreenPassPlanner.class.getDeclaredMethod("createPass",ProgramSet.class,ProgramSource.class,
                    TextureStage.class,String.class,IrisVulkanScreenPassGraph.Kind.class,boolean.class,List.class);
                method.setAccessible(true);
                var node=(IrisVulkanScreenPassGraph.Node)method.invoke(null,programs,first,TextureStage.DEFERRED,"deferred/0",
                    IrisVulkanScreenPassGraph.Kind.DEFERRED,false,formats);
                report.put("plannerStatus",node.status().name());report.put("plannerFailure",node.failureReason());report.put("samplers",node.samplers());
                if(node.vertexSource()!=null)Files.writeString(output.resolve("deferred-planned.vsh"),node.vertexSource());
                if(node.fragmentSource()!=null)Files.writeString(output.resolve("deferred-planned.fsh"),node.fragmentSource());
                if(!uniformsReady)report.put("preparationAndShaderc","NOT RUN: real custom-uniform initialization needs live context; fields/values were not fabricated");
                else report.put("preparationAndShaderc","NOT RUN: continuation requires evaluating successful live-context boundary before compiling");
            } catch(Throwable failure) {report.put("plannerBlocker",trace(failure));}
        }
        report.put("runtimeCompatibleClaim",false);
        Files.writeString(output.resolve("attempt.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static String trace(Throwable error){var text=new StringWriter();error.printStackTrace(new PrintWriter(text));return text.toString();}
}
