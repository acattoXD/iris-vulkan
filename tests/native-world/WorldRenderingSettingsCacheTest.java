package net.irisshaders.iris.vulkan;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.world.level.block.state.BlockState;

/** Regression for repeated equal material-map comparisons after a pipeline switch. */
public final class WorldRenderingSettingsCacheTest {
	public static void main(String[] args) {
		var settings = new WorldRenderingSettings();
		var first = new CountingMap();
		first.put(null, 7);
		settings.setBlockStateIds(first);
		check(settings.isReloadRequired(), "Initial material mapping requires a chunk rebuild");
		settings.clearReloadRequired();

		var equivalent = new CountingMap();
		equivalent.put(null, 7);
		settings.setBlockStateIds(equivalent);
		check(!settings.isReloadRequired(), "Equivalent material mappings must not rebuild chunks");
		check(first.comparisons == 1, "A newly selected mapping is compared once");
		check(settings.getBlockStateIds() == equivalent, "Adopt the active pipeline's equivalent mapping");

		for (int frame = 0; frame < 10_000; frame++) settings.setBlockStateIds(equivalent);
		check(first.comparisons == 1 && equivalent.comparisons == 0,
			"Following frames must not traverse either cached material map");
		check(!settings.isReloadRequired(), "Repeated frames must not rebuild chunks");

		var changed = new CountingMap();
		changed.put(null, 8);
		settings.setBlockStateIds(changed);
		check(settings.getBlockStateIds() == changed && settings.isReloadRequired(),
			"Different material IDs still replace the mapping and rebuild chunks");
		settings.clearReloadRequired();
		settings.setBlockStateIds(null);
		check(settings.getBlockStateIds() == null && settings.isReloadRequired(),
			"Clearing material mappings still requests a rebuild");
		System.out.println("PASS: equivalent pipeline maps are adopted once; 10,000 following frames skip comparisons; changed and cleared IDs rebuild chunks");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class CountingMap extends Object2IntLinkedOpenHashMap<BlockState> {
		private int comparisons;

		@Override
		public boolean equals(Object other) {
			comparisons++;
			return super.equals(other);
		}
	}
}
