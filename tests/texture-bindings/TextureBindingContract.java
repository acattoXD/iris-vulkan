package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Executes the compiled production selectors; fake resources never open a GPU. */
public final class TextureBindingContract {
    private static final Method PRIMARY = method("primaryTexture", Map.class, ShaderKey.class);
    private static final Method LIGHTMAP = method("producerLightmap", Map.class, ShaderKey.class);
    private static final Method FIND = method("findTextureBinding", String.class, Map.class, ShaderKey.class);
    private static final Method VIEW = bindingMethod("view");
    private static final Method SAMPLER = bindingMethod("sampler");
    private static final List<String> ALBEDO = List.of("tex", "texture", "gtexture", "u_MainSampler");
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path actual = Path.of(IrisVulkanRenderPassBindings.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(actual.equals(Path.of(args[0]).toRealPath()), "Binding methods must load from current compiled production classes: " + actual);
        require(Iris.getCurrentPack().isEmpty(), "CPU test starts without a shader pack");
        require(IrisPlatformHelpers.getInstance() instanceof CpuPlatform, "Use only the isolated CPU platform provider");

        List<ShaderKey> sodium = Arrays.stream(ShaderKey.values()).filter(key -> key.patch == Patch.SODIUM).toList();
        require(sodium.containsAll(List.of(ShaderKey.SODIUM_TERRAIN_SOLID, ShaderKey.SODIUM_TERRAIN_CUTOUT,
                ShaderKey.SODIUM_TERRAIN_TRANSLUCENT, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT, ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT)),
                "All solid/cutout/translucent and shadow Sodium routes must be exercised");
        var atlas = texture("terrain-atlas", 4096, 2048, FilterMode.NEAREST);
        var terrainLight = texture("terrain-light", 16, 16, FilterMode.LINEAR);
        var skin = texture("skin", 64, 64, FilterMode.NEAREST);
        var font = texture("font", 256, 256, FilterMode.LINEAR);
        var featureLight = texture("feature-light", 16, 16, FilterMode.NEAREST);
        var staleAlias = texture("previous-iris-alias", 32, 32, FilterMode.LINEAR);

        for (ShaderKey key : sodium) {
            Map<String, Object> retained = new LinkedHashMap<>();
            retained.put("Sampler0", skin);
            retained.put("Sampler2", featureLight);
            expectRoutes(retained, ShaderKey.ENTITIES_CUTOUT, skin, featureLight, "feature before " + key);
            retained.put("u_BlockTex", atlas);
            retained.put("u_LightTex", terrainLight);
            for (String alias : ALBEDO) retained.put(alias, staleAlias);
            retained.put("lightmap", featureLight);
            expectRoutes(retained, key, atlas, terrainLight, "Sodium with stale skin/aliases " + key);

            // Simulate a WorldRenderPass attachment reopening which replays all retained uniforms.
            Map<String, Object> replayed = new LinkedHashMap<>();
            replayed.putAll(retained);
            expectRoutes(replayed, key, atlas, terrainLight, "replayed Sodium " + key);
            replayed.put("Sampler0", font);
            replayed.put("Sampler2", featureLight);
            expectRoutes(replayed, ShaderKey.TEXT, font, featureLight, "font after Sodium " + key);
            expectRoutes(replayed, null, font, featureLight, "null-key feature after Sodium " + key);

            // A missing producer binding must never borrow the other producer's namespace.
            replayed.remove("u_BlockTex");
            replayed.put("colortex0", skin);
            replayed.put("gcolor", font);
            require(invoke(PRIMARY, replayed, key) == null, "Missing Sodium atlas cannot borrow feature/alias texture: " + key);
            for (String alias : ALBEDO) require(invoke(FIND, alias, replayed, key) == null,
                    "Real lookup cannot fall back to skin/font/colortex when Sodium atlas is missing: " + key + "/" + alias);
            replayed.remove("u_LightTex");
            require(invoke(LIGHTMAP, replayed, key) == null, "Sodium lightmap cannot borrow Sampler2/lightmap: " + key);
        }

        for (ShaderKey key : Arrays.asList(null, ShaderKey.ENTITIES_CUTOUT, ShaderKey.TEXT,
                ShaderKey.MOVING_BLOCK, ShaderKey.TERRAIN_TRANSLUCENT, ShaderKey.SHADOW_ENTITIES_CUTOUT)) {
            Map<String, Object> sodiumOnly = new LinkedHashMap<>();
            sodiumOnly.put("u_BlockTex", atlas);
            sodiumOnly.put("u_LightTex", terrainLight);
            require(invoke(PRIMARY, sodiumOnly, key) == null, "Non-Sodium producer cannot inherit terrain atlas: " + key);
            require(invoke(LIGHTMAP, sodiumOnly, key) == null, "Non-Sodium producer cannot inherit terrain lightmap: " + key);
            for (String alias : ALBEDO) require(invoke(FIND, alias, sodiumOnly, key) == null,
                    "Real feature lookup excludes foreign terrain namespace: " + key + "/" + alias);
            for (String producerName : List.of("Sampler0", "u_MainSampler", "gtexture", "tex", "texture")) {
                var aliases = new LinkedHashMap<>(sodiumOnly);
                aliases.put(producerName, skin);
                expect(invoke(PRIMARY, aliases, key), skin, "legacy feature alias " + producerName);
            }
            sodiumOnly.put("lightmap", featureLight);
            expect(invoke(LIGHTMAP, sodiumOnly, key), featureLight, "feature lightmap alias");
        }

        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("Sampler0", skin);
        producer.put("u_BlockTex", atlas);
        producer.put("GlintSampler", font);
        expect(invoke(FIND, "glintTexture", producer, ShaderKey.ENTITIES_CUTOUT_GLINT_ARMOR), font, "glint remains producer supplied");
        verifyCustomPrecedence(producer, texture("pack-stage", 512, 128, FilterMode.LINEAR),
                texture("pack-global", 128, 512, FilterMode.NEAREST));

        System.out.println("PASS: " + checks + " checks against actual compiled binding methods; " + sodium.size()
                + " Sodium keys, retained/replayed mixed namespaces, view+sampler identity, dimensions, feature/null routes, custom precedence, glint.");
        System.out.println("LIMITS: CPU resource fakes and map replay only; no GPU, render-pass execution, image visibility, resource upload, FPS, or server validation.");
    }

    private static void expectRoutes(Map<String, Object> values, ShaderKey key, TextureViewAndSampler primary,
                                     TextureViewAndSampler lightmap, String label) throws Exception {
        expect(invoke(PRIMARY, values, key), primary, label + " primary");
        expect(invoke(LIGHTMAP, values, key), lightmap, label + " lightmap");
        for (String alias : ALBEDO) {
            Object selected = invoke(FIND, alias, values, key);
            expect(selected, primary, label + " real lookup " + alias);
            // Match Iris's retained alias writes before the next producer draws.
            values.put(alias, new TextureViewAndSampler((GpuTextureView) VIEW.invoke(selected), (GpuSampler) SAMPLER.invoke(selected)));
        }
        expect(invoke(FIND, "lightmap", values, key), lightmap, label + " real lightmap");
        expect(invoke(FIND, "u_LightTex", values, key), lightmap, label + " real u_LightTex alias");
    }

    private static void verifyCustomPrecedence(Map<String, Object> values, TextureViewAndSampler stage,
                                                TextureViewAndSampler global) throws Exception {
        Field stages = field(IrisVulkanCustomTextures.class, "stageTextures");
        Field globals = field(IrisVulkanCustomTextures.class, "globalTextures");
        Object savedStages = stages.get(null), savedGlobals = globals.get(null);
        try {
            var stageMap = new EnumMap<TextureStage, Map<String, IrisVulkanCustomTextures.Binding>>(TextureStage.class);
            var customStage = new LinkedHashMap<String, IrisVulkanCustomTextures.Binding>();
            var customGlobal = new LinkedHashMap<String, IrisVulkanCustomTextures.Binding>();
            for (String alias : ALBEDO) { customStage.put(alias, custom(stage)); customGlobal.put(alias, custom(global)); }
            customStage.put("lightmap", custom(stage));
            customGlobal.put("lightmap", custom(global));
            stageMap.put(TextureStage.GBUFFERS_AND_SHADOW, customStage);
            stages.set(null, stageMap);
            globals.set(null, customGlobal);
            for (ShaderKey key : Arrays.asList(null, ShaderKey.TEXT, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                    ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT)) {
                for (String alias : ALBEDO) expect(invoke(FIND, alias, values, key), stage, "stage custom precedence " + key + "/" + alias);
                expect(invoke(FIND, "lightmap", values, key), stage, "stage custom lightmap precedence " + key);
            }
            stages.set(null, new EnumMap<>(TextureStage.class));
            for (String alias : ALBEDO) expect(invoke(FIND, alias, values, ShaderKey.SODIUM_TERRAIN_CUTOUT), global,
                    "global custom precedence " + alias);
        } finally { stages.set(null, savedStages); globals.set(null, savedGlobals); }
    }

    private static IrisVulkanCustomTextures.Binding custom(TextureViewAndSampler pair) {
        return new IrisVulkanCustomTextures.Binding(pair.view().texture(), pair.view(), pair.sampler(), false);
    }

    private static void expect(Object binding, TextureViewAndSampler expected, String label) throws Exception {
        require(binding != null, label + " exists");
        GpuTextureView view = (GpuTextureView) VIEW.invoke(binding);
        require(view == expected.view(), label + " view identity");
        require(SAMPLER.invoke(binding) == expected.sampler(), label + " sampler identity");
        require(view.texture().getWidth(0) == expected.view().texture().getWidth(0)
                && view.texture().getHeight(0) == expected.view().texture().getHeight(0), label + " texture dimensions");
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static Method method(String name, Class<?>... args) {
        try { Method result = IrisVulkanRenderPassBindings.class.getDeclaredMethod(name, args); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Method bindingMethod(String name) {
        try { Method result = Class.forName(IrisVulkanRenderPassBindings.class.getName() + "$TextureBinding").getDeclaredMethod(name); result.setAccessible(true); return result; }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    private static void require(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    private static TextureViewAndSampler texture(String name, int width, int height, FilterMode filter) {
        return new TextureViewAndSampler(new View(new Texture(name, width, height)), new Sampler(filter));
    }
    private record Texture(String getLabel, int width, int height) implements GpuTexture {
        public int getWidth(int mip) { return Math.max(1, width >> mip); }
        public int getHeight(int mip) { return Math.max(1, height >> mip); }
        public int getDepthOrLayers() { return 1; }
        public int getMipLevels() { return 1; }
        public GpuFormat getFormat() { return GpuFormat.RGBA8_UNORM; }
        public int usage() { return GpuTexture.USAGE_TEXTURE_BINDING; }
        public boolean isClosed() { return false; }
        public void close() { throw new AssertionError("CPU resources must not be closed by selector"); }
    }
    private record View(GpuTexture texture) implements GpuTextureView {
        public int baseMipLevel() { return 0; }
        public int mipLevels() { return 1; }
        public int getWidth(int mip) { return texture.getWidth(mip); }
        public int getHeight(int mip) { return texture.getHeight(mip); }
        public boolean isClosed() { return false; }
        public void close() { throw new AssertionError("CPU resources must not be closed by selector"); }
    }
    private record Sampler(FilterMode filter) implements GpuSampler {
        public AddressMode getAddressModeU() { return AddressMode.CLAMP_TO_EDGE; }
        public AddressMode getAddressModeV() { return AddressMode.CLAMP_TO_EDGE; }
        public FilterMode getMinFilter() { return filter; }
        public FilterMode getMagFilter() { return filter; }
        public int getMaxAnisotropy() { return 1; }
        public OptionalDouble getMaxLod() { return OptionalDouble.empty(); }
        public boolean isClosed() { return false; }
        public void close() { throw new AssertionError("CPU resources must not be closed by selector"); }
    }

    /** Only avoids Fabric launcher initialization; production Iris/binding classes remain unchanged. */
    public static final class CpuPlatform implements IrisPlatformHelpers {
        public boolean isModLoaded(String id) { return false; }
        public String getVersion() { return "cpu-test"; }
        public boolean isDevelopmentEnvironment() { return false; }
        public Path getGameDir() { throw new AssertionError("No game directory in CPU regression"); }
        public Path getConfigDir() { throw new AssertionError("No config directory in CPU regression"); }
        public int compareVersions(String first, String second) { throw new AssertionError("No version lookup in CPU regression"); }
        public KeyMapping registerKeyBinding(KeyMapping mapping) { throw new AssertionError("No key binding registration in CPU regression"); }
        public boolean useELS() { return false; }
        public BlockState getBlockAppearance(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos position) { return state; }
    }
}
