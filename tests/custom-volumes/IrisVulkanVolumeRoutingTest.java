package net.irisshaders.iris.vulkan;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import java.util.Map;

/** Real native texture routing and real RawData3D metadata, with startup facades only. */
public final class IrisVulkanVolumeRoutingTest {
    private static int assertions;
    public static void main(String[] args) {
        var pack = new ShaderPack();
        var deferred = new Object2ObjectOpenHashMap<String, CustomTextureData>();
        var composite = new Object2ObjectOpenHashMap<String, CustomTextureData>();
        pack.getCustomTextureDataMap().put(TextureStage.DEFERRED, deferred);
        pack.getCustomTextureDataMap().put(TextureStage.COMPOSITE_AND_FINAL, composite);
        var red = raw(InternalTextureFormat.R8, PixelFormat.RED, PixelType.UNSIGNED_BYTE, 1);
        var half = raw(InternalTextureFormat.RGB16F, PixelFormat.RGB, PixelType.HALF_FLOAT, 6);
        var floats = raw(InternalTextureFormat.RGBA32F, PixelFormat.RGBA, PixelType.FLOAT, 16);
        deferred.put("customtex0", half);
        deferred.put("customtex1", red);
        composite.put("customtex2", red);
        Map<Tri<String, TextureType, TextureStage>, String> aliases = Map.of(
                new Tri<>("depthtex0", TextureType.TEXTURE_3D, TextureStage.DEFERRED), "customtex0",
                new Tri<>("colortex6", TextureType.TEXTURE_3D, TextureStage.DEFERRED), "customtex1",
                new Tri<>("colortex0", TextureType.TEXTURE_3D, TextureStage.COMPOSITE_AND_FINAL), "customtex2");
        require(!supports(pack, TextureStage.DEFERRED, "depthtex0", Map.of()), "Original alias alone is not registered");
        require(supports(pack, TextureStage.DEFERRED, "depthtex0", aliases), "Deferred RGB16F alias resolves");
        require(supports(pack, TextureStage.DEFERRED, "colortex6", aliases), "Deferred R8 alias resolves");
        require(supports(pack, TextureStage.COMPOSITE_AND_FINAL, "colortex0", aliases), "Composite R8 alias resolves");
        require(!supports(pack, TextureStage.COMPOSITE_AND_FINAL, "depthtex0", aliases), "Aliases are stage-specific");
        require(!supports(pack, TextureStage.DEFERRED, "depthtex0", Map.of(
                new Tri<>("depthtex0", TextureType.TEXTURE_2D, TextureStage.DEFERRED), "customtex0")), "2D mapping cannot qualify a 3D sampler");
        require(supports(pack, TextureStage.DEFERRED, "customtex0", Map.of()), "Already renamed sampler remains supported");
        require(!supports(null, TextureStage.DEFERRED, "depthtex0", aliases), "Missing pack is unsupported");
        pack.getIrisCustomTextureDataMap().put("globalFloat", floats);
        require(supports(pack, TextureStage.DEFERRED, "globalFloat", Map.of()), "Existing RGBA32F global volume remains supported");
        deferred.put("globalFloat", new CustomTextureData.PngData(new TextureFilteringData(true, true), new byte[0]));
        require(!supports(pack, TextureStage.DEFERRED, "globalFloat", Map.of()), "Stage registration wins over same-name global volume");
        require(supports(pack, TextureStage.COMPOSITE_AND_FINAL, "globalFloat", Map.of()), "Unshadowed stage still sees global volume");
        deferred.put("customtex0", raw(InternalTextureFormat.R8UI, PixelFormat.RED_INTEGER, PixelType.UNSIGNED_BYTE, 1));
        require(!supports(pack, TextureStage.DEFERRED, "depthtex0", aliases), "Integer R8UI is not accepted as normalized R8");
        deferred.put("customtex0", raw(InternalTextureFormat.RGB16F, PixelFormat.RGB, PixelType.FLOAT, 12));
        require(!supports(pack, TextureStage.DEFERRED, "depthtex0", aliases), "Unsupported format/type combination stays rejected");
        deferred.put("customtex0", raw(InternalTextureFormat.R8, PixelFormat.RED, PixelType.UNSIGNED_BYTE, 2));
        require(!supports(pack, TextureStage.DEFERRED, "depthtex0", aliases), "Native support gate rejects trailing raw bytes");
        System.out.println("IRIS_VOLUME_ROUTING_PASS: " + assertions + " assertions; mapped stage/type aliases, global fallback, precedence, format gate");
    }
    private static CustomTextureData.RawData3D raw(InternalTextureFormat internal, PixelFormat format, PixelType type, int bytes) {
        return new CustomTextureData.RawData3D(new byte[bytes], new TextureFilteringData(true, false), internal, format, type, 1, 1, 1);
    }
    private static boolean supports(ShaderPack pack, TextureStage stage, String sampler, Map<Tri<String, TextureType, TextureStage>, String> aliases) {
        return IrisVulkanCustomTextures.supportsStaticVolume(pack, stage, sampler, aliases);
    }
    private static void require(boolean condition, String message) { ++assertions; if (!condition) throw new AssertionError(message); }
}
