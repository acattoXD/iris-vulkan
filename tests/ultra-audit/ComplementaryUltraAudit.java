package net.irisshaders.iris.audit;

import com.google.common.collect.ImmutableList;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.parsing.BiomeCategories;
import net.irisshaders.iris.shaderpack.include.*;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.parsing.BooleanParser;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;

import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

/**
 * CPU audit of real production include/option/preprocessor paths. No game or graphics device.
 * The runtime Iris facade only supplies logging and a debug-disabled configuration.
 * This source inventory intentionally does not claim GPU compilation, reachability, or rendering proof.
 */
public final class ComplementaryUltraAudit {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]), output = Path.of(args[1]);
        Properties saved = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[2]))) { saved.load(reader); }
        Map<String,String> overrides = new TreeMap<>();
        saved.forEach((k,v) -> overrides.put(k.toString(),v.toString()));
        List<StringPair> environment = new ArrayList<>();
        for (String name : List.of("IS_IRIS", "IRIS_VULKAN", "MC_OS_WINDOWS", "MC_GL_VENDOR_NVIDIA", "MC_GL_RENDERER_OTHER", "IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS", "IRIS_HAS_TRANSLUCENCY_SORTING", "IRIS_INLINE_GLINT", "MC_NORMAL_MAP", "MC_SPECULAR_MAP")) environment.add(new StringPair(name,""));
        Map.of("MC_VERSION","260300", "MC_GL_VERSION","460", "MC_GLSL_VERSION","460", "IRIS_VERSION","11105", "MC_MIPMAP_LEVEL","4", "MC_RENDER_QUALITY","1.0", "MC_SHADOW_QUALITY","1.0", "MC_HAND_DEPTH","0.125", "MAX_COLOR_BUFFERS","32", "IRIS_TAG_SUPPORT","2").forEach((k,v)->environment.add(new StringPair(k,v)));
        for (var phase : WorldRenderingPhase.values()) environment.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        for (var category : BiomeCategories.values()) environment.add(new StringPair("CAT_"+category.name(),Integer.toString(category.ordinal())));
        for (int i=0;i<3;i++) environment.add(new StringPair("PPT_"+List.of("NONE","RAIN","SNOW").get(i),Integer.toString(i)));
        var dhBlocks=List.of("UNKNOWN","LEAVES","STONE","WOOD","METAL","DIRT","LAVA","DEEPSLATE","SNOW","SAND","TERRACOTTA","NETHER_STONE","WATER","GRASS","AIR","ILLUMINATED");
        for(int i=0;i<dhBlocks.size();i++) environment.add(new StringPair("DH_BLOCK_"+dhBlocks.get(i),Integer.toString(i)));
        // Explicit target inputs avoid querying or creating a live graphics device.
        if (args.length >= 4) {
            Map<String,String> target = new Gson().fromJson(Files.readString(Path.of(args[3])), new TypeToken<Map<String,String>>(){}.getType());
            for (var entry : target.entrySet()) {
                environment.removeIf(pair -> pair.key().equals(entry.getKey()));
                environment.add(new StringPair(entry.getKey(), entry.getValue()));
            }
        }
        List<String> availableFeatures = List.of("SEPARATE_HARDWARE_SAMPLERS", "HIGHER_SHADOWCOLOR", "CUSTOM_IMAGES",
            "PER_BUFFER_BLENDING", "COMPUTE_SHADERS", "TESSELLATION_SHADERS", "ENTITY_TRANSLUCENT", "REVERSED_CULLING",
            "BLOCK_EMISSION_ATTRIBUTE", "CAN_DISABLE_WEATHER", "SSBO", "FADE_VARIABLE", "TEXTURE_FILTERING");
        var starts = ImmutableList.<AbsolutePackPath>builder();
        for (String dim : List.of("", "world0", "world-1", "world1")) ShaderPackSourceNames.findPresentSources(starts,root,AbsolutePackPath.fromAbsolutePath("/"+dim),ShaderPackSourceNames.POTENTIAL_STARTS);
        var graph = new IncludeGraph(root,starts.build(),true);
        if (!graph.getFailures().isEmpty()) throw new IllegalStateException(graph.getFailures().toString());
        var options = new ShaderPackOptions(graph,overrides);
        var includes = new IncludeProcessor(options.getIncludes());
        Files.createDirectories(output);
        // ShaderPack advertises all usable flags while reading properties, then only
        // requested optional flags in GLSL. Match that distinction exactly.
        List<StringPair> propertyEnvironment = new ArrayList<>(environment);
        availableFeatures.forEach(name -> propertyEnvironment.add(new StringPair("IRIS_FEATURE_" + name, "")));
        String properties = PropertiesPreprocessor.preprocessSource(Files.readString(root.resolve("shaders.properties")),options,propertyEnvironment);
        Files.writeString(output.resolve("preprocessed.properties"),properties);
        Properties parsed = new Properties(); parsed.load(new StringReader(properties));
        for (String feature : parsed.getProperty("iris.features.optional", "").split("\\s+")) {
            if (availableFeatures.contains(feature)) environment.add(new StringPair("IRIS_FEATURE_" + feature, ""));
        }
        if (Boolean.parseBoolean(parsed.getProperty("supportsColorCorrection", "false"))) {
            var spaces = List.of("SRGB", "DCI_P3", "DISPLAY_P3", "REC2020", "ADOBE_RGB");
            for (int i = 0; i < spaces.size(); i++) environment.add(new StringPair("COLOR_SPACE_" + spaces.get(i), Integer.toString(i)));
        }
        Set<String> disabled = new TreeSet<>();
        parsed.forEach((k,v)->{String key=k.toString(); if(key.startsWith("program.")&&key.endsWith(".enabled")&&!BooleanParser.parse(v.toString(),options.getOptionValues())) disabled.add(key.substring(8,key.length()-8));});
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("scope","Production IncludeGraph, ShaderPackOptions, PropertiesPreprocessor, and JcppProcessor with explicit Windows Vulkan feature environment; CPU source inventory only, not a game/GPU run.");
        report.put("environment",environment);
        report.put("propertyEnvironment",propertyEnvironment);
        report.put("overrides",overrides);
        Map<String,Object> selected = new TreeMap<>();
        options.getOptionSet().getStringOptions().keySet().forEach(name->selected.put(name,options.getOptionValues().getStringValueOrDefault(name)));
        options.getOptionSet().getBooleanOptions().keySet().forEach(name->selected.put(name,options.getOptionValues().getBooleanValueOrDefault(name)));
        report.put("selectedOptions",selected); report.put("disabledPrograms",disabled);
        List<String> sources = new ArrayList<>();
        for (AbsolutePackPath source : starts.build()) {
            String relative=source.getPathString().substring(1);
            if (args.length >= 5 && args[4].equals("--compute-only") && !relative.endsWith(".csh")) continue;
            String program=relative.substring(0,relative.lastIndexOf('.'));
            if (disabled.contains(program)) continue;
            var lines = includes.getIncludedFile(source);
            if(lines==null) continue;
            String active=JcppProcessor.glslPreprocessSource(String.join("\n",lines)+"\n",environment);
            Path file=output.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file,active);
            sources.add(relative);
        }
        report.put("sources",sources);
        Files.writeString(output.resolve("inventory.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("OFFLINE_ULTRA_AUDIT_PASS sources="+sources.size()+" output="+output.toAbsolutePath());
    }
}
