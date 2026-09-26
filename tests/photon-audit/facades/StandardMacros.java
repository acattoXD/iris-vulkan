package net.irisshaders.iris.gl.shader;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit Windows/Vulkan target; replaces only live Minecraft/device environment queries. */
public final class StandardMacros {
    public static ImmutableList<StringPair> createStandardEnvironmentDefines() {
        var result = new ArrayList<StringPair>();
        for(String name:List.of("IS_IRIS","IRIS_VULKAN","MC_OS_WINDOWS","MC_GL_VENDOR_NVIDIA","MC_GL_RENDERER_OTHER",
                "IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS","IRIS_HAS_TRANSLUCENCY_SORTING","IRIS_INLINE_GLINT",
                "IRIS_HAS_CONNECTED_TEXTURES","MC_NORMAL_MAP","MC_SPECULAR_MAP")) result.add(new StringPair(name,""));
        Map.ofEntries(Map.entry("MC_VERSION","260300"),Map.entry("IRIS_VERSION","11105"),Map.entry("MC_MIPMAP_LEVEL","4"),
                Map.entry("MC_GL_VERSION","460"),Map.entry("MC_GLSL_VERSION","460"),Map.entry("MAX_COLOR_BUFFERS","32"),
                Map.entry("IRIS_TAG_SUPPORT","2"),Map.entry("MC_RENDER_QUALITY","1.0"),Map.entry("MC_SHADOW_QUALITY","1.0"),
                Map.entry("MC_HAND_DEPTH","0.125")).forEach((k,v)->result.add(new StringPair(k,v)));
        for(var phase:WorldRenderingPhase.values())result.add(new StringPair("MC_RENDER_STAGE_"+phase.name(),Integer.toString(phase.ordinal())));
        var dh=List.of("UNKNOWN","LEAVES","STONE","WOOD","METAL","DIRT","LAVA","DEEPSLATE","SNOW","SAND","TERRACOTTA","NETHER_STONE","WATER","GRASS","AIR","ILLUMINATED");
        for(int i=0;i<dh.size();i++)result.add(new StringPair("DH_BLOCK_"+dh.get(i),Integer.toString(i)));
        return ImmutableList.copyOf(result);
    }
}
