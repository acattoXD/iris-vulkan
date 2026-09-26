package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedSheetGlyph;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Real nametag submissions, actual vanilla alpha blend state and native material scopes. */
public final class IrisVulkanNameTagTest {
    public static void main(String[] args) throws Exception {
        checkTextureContracts();
        for (RenderPipeline pipeline : List.of(RenderPipelines.TEXT, RenderPipelines.TEXT_SEE_THROUGH,
                RenderPipelines.TEXT_BACKGROUND, RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH)) {
            System.out.println(pipeline.getLocation() + " " + pipeline.getVertexFormatBinding(0) + " " + pipeline.getColorTargetState());
            require(pipeline.getColorTargetState().blendFunction().isPresent(), "Vanilla nametag color must blend");
        }
        for (RenderPipeline pipeline : List.of(RenderPipelines.TEXT, RenderPipelines.TEXT_SEE_THROUGH,
                RenderPipelines.TEXT_BACKGROUND, RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH)) {
            ShaderKey key = pipeline == RenderPipelines.TEXT_BACKGROUND || pipeline == RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH
                    ? ShaderKey.TEXT_BG : ShaderKey.TEXT;
            var states = IrisVulkanWorldPipelineStates.colorStates(pipeline, key, null,
                    List.of(GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_FLOAT, GpuFormat.RGBA16_FLOAT), new int[]{0, 3, 6, 4});
            for (var state : states) require(state.blendFunction().equals(pipeline.getColorTargetState().blendFunction()),
                    "Active MRT adaptation lost inherited alpha blending");
            System.out.println("  MRT blending=" + java.util.Arrays.stream(states).map(ColorTargetState::blendFunction).toList());
        }
        require(ShaderKey.TEXT_BG.getProgram() == ProgramId.EntitiesTrans && ShaderKey.TEXT.getProgram() == ProgramId.EntitiesTrans,
                "Nametag background and glyphs must share translucent entity programs");
        require(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.TEXT, WorldRenderingPhase.BLOCK_ENTITIES) == ShaderKey.TEXT_BE,
                "Block-entity text must retain its separate program family");
        require(IrisVulkanPhaseContext.mapShaderKey(ShaderKey.TEXT, WorldRenderingPhase.ENTITIES) == ShaderKey.TEXT,
                "Entity nametag glyphs must retain entity family");
        Properties properties = new Properties();
        try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
            String raw = new String(zip.getInputStream(zip.getEntry("shaders/entity.properties")).readAllBytes(), StandardCharsets.UTF_8);
            properties.load(new StringReader(PropertiesPreprocessor.preprocessSource(raw, List.of(new StringPair("MC_VERSION", "260200")))));
        }
        int nameId = properties.stringPropertyNames().stream().filter(key -> List.of(properties.getProperty(key).split("\\s+")).contains("name_tag"))
                .mapToInt(key -> Integer.parseInt(key.substring("entity.".length()))).findFirst().orElseThrow();
        require(nameId == 50112, "Actual Complementary r5.9.1 nametag material definition");
        var previous = WorldRenderingSettings.INSTANCE.getEntityIds();
        var entityField = WorldRenderingSettings.class.getDeclaredField("entityIds");
        entityField.setAccessible(true);
        var ids = new Object2IntOpenHashMap<NamespacedId>();
        ids.put(new NamespacedId("minecraft", "name_tag"), nameId);
        WorldRenderingSettings.INSTANCE.setEntityIds(ids);
        try {
            var submit = new NameTagFeatureRenderer.Submit(new Matrix4f(), 0, 0, null, 15728880, -1, 0x40000000, Font.DisplayMode.NORMAL);
            var material = IrisVulkanEntityContext.fromSubmit(submit);
            require(material.entityId() == nameId && material.itemId() == 0 && material.blockEntityId() == 0 && !material.blockEntity(),
                    "Native nametag run must use pack's name_tag pseudo entity, independent of parent entity/item: " + material);
            var before = new IrisVulkanEntityContext.Material(123, 456, 789, true);
            try (var parent = IrisVulkanEntityContext.enter(before)) {
                try (var tag = IrisVulkanEntityContext.enter(material)) {
                    require(CapturedRenderingState.INSTANCE.getCurrentRenderedEntity() == nameId, "Nametag ID must reach shader uniform state");
                    require(CapturedRenderingState.INSTANCE.getCurrentRenderedItem() == 0, "Parent held-item ID must not select item material for a nametag");
                }
                require(IrisVulkanEntityContext.current().equals(before), "Tag scope restores parent entity/block/item IDs");
            }
            WorldRenderingSettings.INSTANCE.setEntityIds(new Object2IntOpenHashMap<>());
            require(IrisVulkanEntityContext.fromSubmit(submit).entityId() == 0, "Missing pack pseudo-entity mapping defaults to0");
            entityField.set(WorldRenderingSettings.INSTANCE, null); // Model the initial state before a pack is installed.
            require(IrisVulkanEntityContext.fromSubmit(submit).equals(IrisVulkanEntityContext.EMPTY), "Missing entity mappings are safe");
        } finally {
            if (previous == null) entityField.set(WorldRenderingSettings.INSTANCE, null);
            else WorldRenderingSettings.INSTANCE.setEntityIds(previous);
        }
        System.out.println("IRIS_NAMETAG_PASS: actual four vanilla pipelines/current MRT alpha states, actual50112 property, actual submission classification and scope restoration");
    }
    private static void checkTextureContracts() throws Exception {
        Identifier atlas = Identifier.withDefaultNamespace("font/nametag_fixture");
        var types = GlyphRenderTypes.createForColorTexture(atlas);
        // The real FontSet white EffectGlyph is a stitched BakedSheetGlyph. Exercise
        // its actual background EffectInstance, including its render-type selection.
        var sheet = new BakedSheetGlyph(null, types, null, 0, 1, 0, 1, 0, 1, 0, 1);
        var background = sheet.createEffect(0, 0, 10, 9, -0.01f, 0x40000000, 0, 0);
        for (Font.DisplayMode mode : List.of(Font.DisplayMode.NORMAL, Font.DisplayMode.SEE_THROUGH)) {
            RenderType selected = background.renderType(mode);
            require(selected == types.select(mode), "Background effect keeps the glyph atlas render type");
            require(selected.pipeline() == (mode == Font.DisplayMode.NORMAL ? RenderPipelines.TEXT : RenderPipelines.TEXT_SEE_THROUGH),
                    "Actual stitched background uses a TEXT pipeline, not untextured TEXT_BG");
            require(textureLocation(selected, "Sampler0").equals(atlas), "Background effect must bind its own glyph atlas");
        }
        require(textureLocation(RenderTypes.textBackground(), "Sampler0") == null, "Standalone TEXT_BG has no albedo texture by design");
        require(textureLocation(RenderTypes.textBackgroundSeeThrough(), "Sampler0") == null, "Standalone see-through TEXT_BG has no albedo texture");
        Identifier shadow = Identifier.withDefaultNamespace("textures/misc/shadow.png");
        require(textureLocation(RenderTypes.entityShadow(shadow), "Sampler0").equals(shadow), "Vanilla ground shadow uses its explicit circular texture");
        try (var input = IrisVulkanNameTagTest.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/misc/shadow.png")) {
            require(input != null, "Actual Minecraft shadow texture must be available");
            var pixels = javax.imageio.ImageIO.read(input);
            int minimum = 255, maximum = 0;
            for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++) {
                int alpha = pixels.getRGB(x, y) >>> 24;
                minimum = Math.min(minimum, alpha); maximum = Math.max(maximum, alpha);
            }
            require(minimum == 0 && maximum > 0, "Actual shadow texture has transparent edges and visible center");
            System.out.println("Actual background TEXT atlas binding verified; entity shadow PNG alpha=" + minimum + ".." + maximum);
        }
    }
    private static Identifier textureLocation(RenderType type, String sampler) throws Exception {
        var state = RenderType.class.getDeclaredField("state"); state.setAccessible(true);
        Object setup = state.get(type);
        var textures = setup.getClass().getDeclaredField("textures"); textures.setAccessible(true);
        Object binding = ((java.util.Map<?, ?>) textures.get(setup)).get(sampler);
        if (binding == null) return null;
        var location = binding.getClass().getDeclaredMethod("location"); location.setAccessible(true);
        return (Identifier) location.invoke(binding);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
