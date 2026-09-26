package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compares cached capture bytes with the pre-cache algorithm across provider and draw changes. */
public final class IrisVulkanLayoutCacheProviderRegression {
	private static final Method WRITE = method("write", ByteBuffer.class, int.class, IrisVulkanUniformSnapshot.Field.class);
	private static final Method SYNC = method("syncPreviousMatrices");
	private static int captures;

	public static void main(String[] args) throws Exception {
		float[] sourceA = {0}, sourceB = {0};
		CustomUniforms providerA = provider(sourceA, 1), providerB = provider(sourceB, 2);
		List<IrisVulkanUniformSnapshot.Field> compiled = List.of(
			field("probeExposure", "float"), field("probeColor", "vec3"), field("entityId", "int"),
			field("atlasSize", "ivec2"), field("gbufferModelView", "mat4"), field("gbufferPreviousModelView", "mat4"),
			field("iris_NativeEntityIds", "ivec3"), field("frameCounter", "int"));
		List<IrisVulkanUniformSnapshot.Field> reordered = List.of(compiled.get(6), compiled.get(0), compiled.get(4), compiled.get(3), compiled.get(1));
		var counter = IrisVulkanUniformSnapshot.class.getDeclaredField("vulkanFrameCounter"); counter.setAccessible(true);
		CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f().setPerspective(1.1F, 16F / 9F, 512F, 0.05F, true));
		try {
			for (int frame = 1; frame <= 48; frame++) {
				counter.setInt(null, frame);
				sourceA[0] = 100 + frame;
				sourceB[0] = 400 + frame;
				providerA.beginFrame();
				providerB.beginFrame();
				CapturedRenderingState.INSTANCE.setGbufferModelView(new Matrix4f().rotateY(frame * 0.02f).translate(frame, 0, 0));
				for (int providerId = 1; providerId <= 2; providerId++) {
					CustomUniforms provider = providerId == 1 ? providerA : providerB;
					float expected = (providerId == 1 ? sourceA[0] : sourceB[0]) * 0.25f + providerId;
					IrisVulkanUniformSnapshot.registerActiveCustomUniforms(provider);
					for (int draw = 1; draw <= 4; draw++) {
						int entity = frame * 100 + providerId * 10 + draw;
						CapturedRenderingState.INSTANCE.setCurrentEntity(entity);
						CapturedRenderingState.INSTANCE.setCurrentBlockEntity(entity + 1);
						CapturedRenderingState.INSTANCE.setCurrentRenderedItem(entity + 2);
						IrisVulkanUniformSnapshot.setPrimaryTextureSize(256 * draw, 128 * providerId);
						ByteBuffer captured = compare(compiled, provider);
						check(captured.getFloat(0) == expected, "Changing active provider must supply its own expression value");
						check(captured.getInt(28) == entity && captured.getInt(32) == 256 * draw && captured.getInt(36) == 128 * providerId,
							"Same-frame entity IDs and actual atlas dimensions remain live");
						compare(reordered, provider);
						if (draw == 2) {
							Map<?, ?> cache = cache();
							cache.clear(); // Shader reload/eviction must not affect the resulting bytes.
						}
					}
				}
			}
		} finally {
			IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(providerB);
			IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(providerA);
		}
		System.out.println("PASS: " + captures + " captures match the pre-cache byte writer across 48 frames, alternating active providers, same-frame entities/atlases, matrix history, reordered fields and cache eviction");
	}

	private static CustomUniforms provider(float[] value, int id) {
		var input = new CustomUniformFixedInputUniformsHolder.Builder();
		input.uniform1f(UniformUpdateFrequency.PER_FRAME, "probeBrightness", () -> value[0]);
		input.uniform3f(UniformUpdateFrequency.PER_FRAME, "probeColor", () -> new Vector3f(value[0], id, 0.5f));
		var builder = new CustomUniforms.Builder();
		builder.addVariable("float", "probeExposure", "probeBrightness * 0.25 + " + id, true);
		return builder.build(input.build());
	}

	private static ByteBuffer compare(List<IrisVulkanUniformSnapshot.Field> fields, CustomUniforms active) throws Exception {
		ByteBuffer actual = IrisVulkanUniformSnapshot.capture(fields).data().order(ByteOrder.nativeOrder());
		ByteBuffer reference = uncachedCapture(fields, active);
		check(actual.equals(reference), "Cached and pre-cache captures differ at capture " + captures);
		captures++;
		return actual;
	}

	/** Previous capture flow, retaining the production live-value writer but no cached layout or names. */
	private static ByteBuffer uncachedCapture(List<IrisVulkanUniformSnapshot.Field> fields, CustomUniforms active) throws Exception {
		SYNC.invoke(null);
		List<Integer> offsets = new ArrayList<>();
		int cursor = 0;
		for (var field : fields) {
			int alignment = switch (field.type()) { case "ivec2" -> 8; case "vec3", "ivec3", "mat4" -> 16; default -> 4; };
			int size = switch (field.type()) { case "ivec2" -> 8; case "vec3", "ivec3" -> 12; case "mat4" -> 64; default -> 4; };
			cursor = align(cursor, alignment);
			offsets.add(cursor);
			cursor += size;
		}
		active.updateFor(fields.stream().map(IrisVulkanUniformSnapshot.Field::name).toList());
		ByteBuffer data = ByteBuffer.allocateDirect(Math.max(16, align(cursor, 16))).order(ByteOrder.nativeOrder());
		for (int i = 0; i < fields.size(); i++) WRITE.invoke(null, data, offsets.get(i), fields.get(i));
		data.clear();
		return data;
	}

	private static Map<?, ?> cache() throws ReflectiveOperationException {
		var cache = IrisVulkanUniformSnapshot.class.getDeclaredField("LAYOUT_CACHE"); cache.setAccessible(true);
		return (Map<?, ?>) cache.get(null);
	}
	private static int align(int value, int alignment) { return (value + alignment - 1) & -alignment; }
	private static IrisVulkanUniformSnapshot.Field field(String name, String type) { return new IrisVulkanUniformSnapshot.Field(name, type); }
	private static void check(boolean result, String label) { if (!result) throw new AssertionError(label); }
	private static Method method(String name, Class<?>... types) {
		try { Method method = IrisVulkanUniformSnapshot.class.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
		catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
	}
}
