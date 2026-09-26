import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.List;
import java.util.zip.ZipFile;

/** Reads the selected pack's actual entity_flame mapping; no game or GPU context is started. */
public final class IrisVulkanFlameMaterialTest {
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Pass the installed Complementary Unbound r5.8.1 zip");
		Properties properties = new Properties();
		try (ZipFile pack = new ZipFile(Path.of(args[0]).toFile());
			 InputStreamReader reader = new InputStreamReader(pack.getInputStream(pack.getEntry("shaders/entity.properties")), StandardCharsets.UTF_8)) {
			StringBuilder source = new StringBuilder();
			char[] buffer = new char[4096];
			for (int count; (count = reader.read(buffer)) >= 0;) source.append(buffer, 0, count);
			String selected = PropertiesPreprocessor.preprocessSource(source.toString(),
				List.of(new StringPair("MC_VERSION", "26200")));
			properties.load(new StringReader(selected));
		}
		int flameId = -1;
		for (String key : properties.stringPropertyNames()) {
			if (!key.startsWith("entity.")) continue;
			for (String name : properties.getProperty(key).trim().split("\\s+")) {
				if (name.equals("entity_flame") || name.equals("minecraft:entity_flame")) flameId = Integer.parseInt(key.substring(7));
			}
		}
		check(flameId == 50088, "The selected Complementary pack must declare entity_flame as 50088");
		var ids = new Object2IntOpenHashMap<NamespacedId>();
		ids.defaultReturnValue(-1);
		ids.put(new NamespacedId("minecraft", "entity_flame"), flameId);
		WorldRenderingSettings.INSTANCE.setEntityIds(ids);
		// The classifier only needs the submit type; geometry is irrelevant to material lookup.
		var flame = new FlameFeatureRenderer.Submit(null, null, null);
		var material = IrisVulkanEntityContext.fromSubmit(flame);
		check(material.entityId() == 50088, "Flame draw receives the pack pseudo-entity ID");
		check(material.blockEntityId() == 0 && material.itemId() == 0 && !material.blockEntity(), "Flame draw does not inherit mob/item/block metadata");
		var ordinary = new IrisVulkanEntityContext.Material(50016, 0, 0, false);
		IrisVulkanEntityContext.MaterialSubmit stored = () -> ordinary;
		check(IrisVulkanEntityContext.fromSubmit(stored).equals(ordinary), "Ordinary captured materials are preserved");
		ids.clear();
		check(IrisVulkanEntityContext.fromSubmit(flame).equals(IrisVulkanEntityContext.EMPTY), "Missing optional flame mapping uses the neutral material, not a negative default");
		System.out.println("PASS: Complementary entity_flame=50088 reaches native flame draws; ordinary and absent mappings remain correct");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
