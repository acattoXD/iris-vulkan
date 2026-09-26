package net.irisshaders.iris.vulkan;

import java.lang.ref.Reference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Checks cached std140 offsets, shader-list lifetime, and independent draw snapshots. */
public final class IrisVulkanLayoutCacheTest {
	private static final Method PREPARE = method(IrisVulkanUniformSnapshot.class, "preparedLayout", Collection.class);
	private static final Method LAYOUTS = method(IrisVulkanUniformSnapshot.class, "layouts", Collection.class);

	public static void main(String[] args) throws Exception {
		List<IrisVulkanUniformSnapshot.Field> fields = List.of(
			field("scalar", "float"), field("direction", "vec3"), field("tail", "int"),
			field("normal", "mat3"), field("size", "ivec2"), field("projection", "mat4"), field("enabled", "bool"));
		Object prepared = PREPARE.invoke(null, fields);
		List<?> members = layouts(fields);
		checkOffsets(members, new int[] {0, 16, 28, 32, 80, 96, 160}, new int[] {4, 12, 4, 48, 8, 64, 4});
		check((Integer) method(prepared.getClass(), "size").invoke(prepared) == 176, "Block size includes std140 tail padding");
		for (int draw = 0; draw < 10_000; draw++) {
			check(PREPARE.invoke(null, fields) == prepared, "Reuse the same layout plan for a compiled field list");
			check(layouts(fields) == members, "Do not allocate member layout lists per draw");
		}

		List<IrisVulkanUniformSnapshot.Field> reordered = List.of(fields.get(5), fields.get(4), fields.get(3), fields.get(1), fields.get(0));
		check(PREPARE.invoke(null, reordered) != prepared, "Different field ordering cannot share a plan");
		checkOffsets(layouts(reordered), new int[] {0, 64, 80, 128, 140}, new int[] {64, 8, 48, 12, 4});
		ArrayList<IrisVulkanUniformSnapshot.Field> mutable = new ArrayList<>(fields);
		List<?> originalMutableLayout = layouts(mutable);
		mutable.clear();
		mutable.add(field("replacement", "mat4"));
		check(layouts(mutable).size() == 1 && originalMutableLayout.size() == 7, "Mutable callers receive detached layouts");
		check(IrisVulkanUniformSnapshot.field("gbufferProjection", "mat4", "[2]").isEmpty(), "Matrix arrays remain outside the supported loose-uniform ABI");
		check(IrisVulkanUniformSnapshot.field("frameCounter", "int", "[4]").isEmpty(), "Scalar arrays must not acquire incorrect cached offsets");

		checkDrawIsolation();
		checkCacheLifetime();
		System.out.println("PASS: 10,000 cached draws, reordered scalar/vector/matrix std140 offsets, mutable inputs, rejected arrays, independent snapshots, bounded cache and weak-key cleanup");
	}

	private static void checkDrawIsolation() throws Exception {
		var counter = IrisVulkanUniformSnapshot.class.getDeclaredField("vulkanFrameCounter");
		counter.setAccessible(true);
		List<IrisVulkanUniformSnapshot.Field> fields = List.of(field("frameCounter", "int"));
		int saved = counter.getInt(null);
		try {
			counter.setInt(null, 17);
			var first = IrisVulkanUniformSnapshot.capture(fields);
			counter.setInt(null, 23);
			var second = IrisVulkanUniformSnapshot.capture(fields);
			check(first.data() != second.data() && first.data().getInt(0) == 17 && second.data().getInt(0) == 23,
				"Cached layouts must not cache values or share live draw buffers");
		} finally {
			counter.setInt(null, saved);
		}
	}

	private static void checkCacheLifetime() throws Exception {
		var cacheField = IrisVulkanUniformSnapshot.class.getDeclaredField("LAYOUT_CACHE"); cacheField.setAccessible(true);
		Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
		var limitField = IrisVulkanUniformSnapshot.class.getDeclaredField("MAX_CACHED_LAYOUTS"); limitField.setAccessible(true);
		int limit = limitField.getInt(null);
		List<List<IrisVulkanUniformSnapshot.Field>> retained = new ArrayList<>();
		for (int i = 0; i < limit + 32; i++) {
			var fields = List.of(field("reload_" + i, "vec4"));
			retained.add(fields);
			PREPARE.invoke(null, fields);
		}
		check(cache.size() == limit, "Reloads cannot grow the layout cache beyond its fixed bound");
		Object oldKey = cache.keySet().iterator().next();
		check(oldKey instanceof Reference<?>, "The field-list key must be weak");
		Reference<?> retired = (Reference<?>) oldKey;
		retired.clear();
		retired.enqueue();
		PREPARE.invoke(null, retained.getLast());
		check(!cache.containsKey(oldKey) && cache.size() == limit - 1, "Drain collected field-list keys before the next draw");
	}

	private static List<?> layouts(Collection<IrisVulkanUniformSnapshot.Field> fields) throws Exception {
		return (List<?>) LAYOUTS.invoke(null, fields);
	}

	private static void checkOffsets(List<?> layouts, int[] offsets, int[] sizes) throws Exception {
		check(layouts.size() == offsets.length, "Member count");
		for (int i = 0; i < offsets.length; i++) {
			Object layout = layouts.get(i);
			check((Integer) method(layout.getClass(), "offset").invoke(layout) == offsets[i], "Member offset " + i);
			check((Integer) method(layout.getClass(), "size").invoke(layout) == sizes[i], "Member size " + i);
		}
	}

	private static IrisVulkanUniformSnapshot.Field field(String name, String type) { return new IrisVulkanUniformSnapshot.Field(name, type); }
	private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
	private static Method method(Class<?> type, String name, Class<?>... parameters) {
		try {
			Method method = type.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
	}
}
