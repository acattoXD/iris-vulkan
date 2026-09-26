package net.irisshaders.iris.shaderpack;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import java.util.EnumMap;

/** Metadata-only pack fixture; no pack loading, resource reload or graphics initialization. */
public final class ShaderPack {
    private final EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> stages = new EnumMap<>(TextureStage.class);
    private final Object2ObjectMap<String, CustomTextureData> global = new Object2ObjectOpenHashMap<>();
    public EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> getCustomTextureDataMap() { return stages; }
    public Object2ObjectMap<String, CustomTextureData> getIrisCustomTextureDataMap() { return global; }
    public CustomTextureData getCustomNoiseTexture() { return null; }
}
