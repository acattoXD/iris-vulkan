package net.irisshaders.iris.vulkan;

import com.google.common.collect.ImmutableList;
import com.google.gson.GsonBuilder;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.IrisDefines;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.option.ProfileSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Optional;

/** Real compiled ShaderPack/ProgramSet/preflight: only environment services are facades. */
public final class PhotonPackAudit {
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        Path zip=Path.of(args[0]),out=Path.of(args[1]); Files.createDirectories(out);
        if(com.mojang.blaze3d.systems.RenderSystem.tryGetDevice()!=null)throw new AssertionError("CPU audit must not have a graphics device");
        Path production=Path.of(ShaderPack.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        for(Class<?> real:List.of(ProgramSet.class,IrisVulkanPackCapabilities.class,IrisVulkanCustomTextures.class,IrisVulkanComputeCompiler.class))
            if(!production.equals(Path.of(real.getProtectionDomain().getCodeSource().getLocation().toURI())))throw new AssertionError("Production class replaced: "+real);
        write(out.resolve("environment.json"),IrisDefines.createIrisReplacements());
        Map<String,Object> reports=new LinkedHashMap<>();
        try(var fs=FileSystems.newFileSystem(zip)) {
            Path root=fs.getPath("/shaders");
            ShaderPack defaults=new ShaderPack(root,ImmutableList.of(),true);
            reports.put("default",audit(defaults,out.resolve("default"),Map.of()));
            var field=ShaderPack.class.getDeclaredField("shaderProperties");field.setAccessible(true);
            var properties=(ShaderProperties)field.get(defaults);
            Map<String,String> high=new TreeMap<>();
            ProfileSet.fromTree(properties.getProfiles(),defaults.getShaderPackOptions().getOptionSet()).forEach((name,profile)->{
                if(name.equalsIgnoreCase("high"))high.putAll(profile.optionValues);
            });
            if(high.isEmpty())throw new AssertionError("Real Photon high profile was not parsed");
            ShaderPack selected=new ShaderPack(root,high,ImmutableList.of(),true);
            reports.put("high",audit(selected,out.resolve("high"),high));
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("profiles",reports);
        report.put("scope","Actual compiled ShaderPack constructor, custom texture reads, ProgramSet/preprocessing, typed alias routing and IrisVulkanPackCapabilities.inspect; no GPU/game execution");
        report.put("facades",List.of("Iris: logging, debug-disabled config and current-pack metadata","IrisRenderSystem: parsing feature predicates assumed true","StandardMacros: explicit Windows/NVIDIA Vulkan environment instead of live engine/device queries"));
        report.put("limitations",List.of("No graphics context/device or resource upload; device feature validation is unavailable","No shader runtime/rendering/dispatch or Photon performance claim","CPU feature environment is explicit, not measured from the current game; biome map is the offline production map","Overworld ProgramSet exported; Nether/End rendering not audited"));
        report.put("productionCodeLocation",production.toString());
        write(out.resolve("report.json"),report);
        System.out.println("PHOTON_REAL_PACK_AUDIT_COMPLETE "+out.toAbsolutePath());
    }

    private static Map<String,Object> audit(ShaderPack pack,Path output,Map<String,String> overrides) throws Exception {
        Files.createDirectories(output);Iris.currentPack=pack;
        ProgramSet programs=pack.getProgramSet(DimensionId.OVERWORLD);
        Set<String> exports=new LinkedHashSet<>();
        for(ProgramId id:ProgramId.values())if(programs.get(id).isPresent())export(programs.get(id).get(),output,exports);
        for(ProgramArrayId id:ProgramArrayId.values()) {
            for(ProgramSource source:programs.getComposite(id))if(source!=null)export(source,output,exports);
            for(ComputeSource[] group:programs.getCompute(id))export(group,output,exports);
        }
        export(programs.getSetup(),output,exports);export(programs.getShadowCompute(),output,exports);export(programs.getFinalCompute(),output,exports);
        if(!Files.isRegularFile(output.resolve("deferred4_a.csh")))throw new AssertionError("Active Photon Overworld deferred4_a kernel missing");
        System.out.println("PHOTON_EXPORT_READY "+output.toAbsolutePath()+" sources="+exports.size());
        var report=new LinkedHashMap<String,Object>();report.put("profileInfo",pack.getProfileInfo());report.put("overrides",overrides);
        report.put("sources",exports);report.put("images",pack.getIrisCustomImages());
        report.put("builtinComputeColorImages",IrisVulkanColorImages.declarations(programs));
        report.put("worldDevelopmentEnabled",IrisNativeVulkan.worldDevelopmentEnabled());report.put("storageDevelopmentEnabled",IrisNativeVulkan.storageDevelopmentEnabled());
        var options=new TreeMap<String,Object>();var optionSet=pack.getShaderPackOptions().getOptionSet();var values=pack.getShaderPackOptions().getOptionValues();
        optionSet.getStringOptions().keySet().forEach(name->options.put(name,values.getStringValueOrDefault(name)));
        optionSet.getBooleanOptions().keySet().forEach(name->options.put(name,values.getBooleanValueOrDefault(name)));
        report.put("effectiveOptions",options);
        var textures=new ArrayList<Map<String,Object>>();
        pack.getCustomTextureDataMap().forEach((stage,entries)->entries.forEach((name,data)->{
            var item=new LinkedHashMap<String,Object>();item.put("stage",stage.name());item.put("name",name);item.put("class",data.getClass().getSimpleName());
            if(data instanceof CustomTextureData.RawData3D volume){
                item.put("dimensions",List.of(volume.getSizeX(),volume.getSizeY(),volume.getSizeZ()));item.put("bytes",volume.getContent().length);
                item.put("format",volume.getInternalFormat().name());item.put("pixelFormat",volume.getPixelFormat().name());item.put("pixelType",volume.getPixelType().name());
                item.put("nativeStaticVolumeSupported",IrisVulkanCustomTextures.supportsStaticVolume(pack,stage,name));
            }
            if(data instanceof CustomTextureData.ResourceData resource)item.put("resource",resource.getNamespace()+":"+resource.getLocation());
            textures.add(item);
        }));report.put("customTextures",textures);
        var globals=new ArrayList<Map<String,Object>>();
        pack.getIrisCustomTextureDataMap().forEach((name,data)->{
            var item=new LinkedHashMap<String,Object>();item.put("name",name);item.put("class",data.getClass().getSimpleName());
            if(data instanceof CustomTextureData.RawData3D volume){
                item.put("dimensions",List.of(volume.getSizeX(),volume.getSizeY(),volume.getSizeZ()));item.put("bytes",volume.getContent().length);
                item.put("format",volume.getInternalFormat().name());item.put("pixelFormat",volume.getPixelFormat().name());item.put("pixelType",volume.getPixelType().name());
                item.put("nativeStaticVolumeSupported",IrisVulkanCustomTextures.supportsStaticVolume(pack,TextureStage.DEFERRED,name));
            }
            globals.add(item);
        });report.put("globalCustomTextures",globals);
        if(globals.stream().filter(texture->texture.get("class").equals("RawData3D")).count()!=4)
            throw new AssertionError("Photon must load all four raw 3D texture definitions through the real ShaderPack constructor");
        var aliases=new ArrayList<Map<String,Object>>();
        programs.getPackDirectives().getTextureMap().forEach((key,value)->aliases.add(Map.of("typedStageKey",key.toString(),"resolvedName",value)));
        report.put("typedStageAliases",aliases);
        var routes=new ArrayList<Map<String,Object>>();
        var sampler3D=java.util.regex.Pattern.compile("uniform\\s+sampler3D\\s+(\\w+)\\s*;");
        for(ProgramArrayId id:ProgramArrayId.values()) {
            TextureStage stage=switch(id){case Setup->TextureStage.SETUP;case Begin->TextureStage.BEGIN;case ShadowComposite->TextureStage.SHADOWCOMP;case Prepare->TextureStage.PREPARE;case Deferred->TextureStage.DEFERRED;case Composite->TextureStage.COMPOSITE_AND_FINAL;};
            for(ProgramSource source:programs.getComposite(id))if(source!=null)for(var code:List.of(source.getVertexSource(),source.getFragmentSource()))if(code.isPresent()){
                var matcher=sampler3D.matcher(IrisVulkanShaderPruning.removeUnusedUniforms(code.get()));
                while(matcher.find()){String name=matcher.group(1);routes.add(Map.of("program",source.getName(),"stage",stage.name(),"sampler",name,"supported",IrisVulkanCustomTextures.supportsStaticVolume(pack,stage,name,programs.getPackDirectives().getTextureMap())));}
            }
        }report.put("activeVolumeRoutes",routes);
        var result=IrisVulkanPackCapabilities.inspect(programs);report.put("resourcePreflightSupported",result.supported());report.put("preflightSummary",result.summary());report.put("unsupported",result.unsupported());
        write(output.resolve("inventory.json"),report);
        return report;
    }

    private static void export(ProgramSource source,Path output,Set<String> names)throws Exception {
        save(output,source.getName()+".vsh",source.getVertexSource(),names);save(output,source.getName()+".fsh",source.getFragmentSource(),names);
        save(output,source.getName()+".gsh",source.getGeometrySource(),names);save(output,source.getName()+".tcs",source.getTessControlSource(),names);save(output,source.getName()+".tes",source.getTessEvalSource(),names);
    }
    private static void export(ComputeSource[] sources,Path output,Set<String> names)throws Exception {
        if(sources!=null)for(ComputeSource source:sources)if(source!=null)save(output,source.getName()+".csh",source.getSource(),names);
    }
    private static void save(Path output,String name,Optional<String> code,Set<String> names)throws Exception {
        if(code.isEmpty())return;Path path=output.resolve(name).normalize();if(!path.startsWith(output))throw new AssertionError("Escaping source name");
        Files.createDirectories(path.getParent());
        if(!Files.isRegularFile(path) || !Files.readString(path).equals(code.get()))Files.writeString(path,code.get());
        names.add(name);
    }
    private static void write(Path file,Object data)throws Exception{Files.writeString(file,JSON.toJson(data));}
}
