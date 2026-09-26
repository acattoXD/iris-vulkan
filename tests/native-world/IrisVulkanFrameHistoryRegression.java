package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/** Replays the actual native cache/history methods with a rotating camera, without starting Minecraft. */
public final class IrisVulkanFrameHistoryRegression {
	public static void main(String[] args) throws Exception {
		Field counter = field("vulkanFrameCounter");
		field("previousFrame").setInt(null, Integer.MIN_VALUE);
		((Map<?, ?>) field("CURRENT_MATRICES").get(null)).clear();
		((Map<?, ?>) field("PREVIOUS_MATRICES").get(null)).clear();
		Method nativeMatrix = IrisVulkanUniformSnapshot.class.getDeclaredMethod("nativeMatrix", String.class);
		nativeMatrix.setAccessible(true);
		var inputs = new CustomUniformFixedInputUniformsHolder.Builder();
		inputs.uniform1i(UniformUpdateFrequency.PER_FRAME, "frameCounter", () -> {
			try { return counter.getInt(null); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
		});
		for (String name : List.of("gbufferModelView", "gbufferPreviousModelView")) {
			inputs.uniformMatrix(UniformUpdateFrequency.PER_FRAME, name, () -> {
				try { return (Matrix4fc) nativeMatrix.invoke(null, name); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
			});
		}
		var builder = new CustomUniforms.Builder();
		builder.addVariable("float", "framemod8", "frameCounter % 8", true);
		CustomUniforms custom = builder.build(inputs.build());
		IrisVulkanUniformSnapshot.registerActiveCustomUniforms(custom);
		CapturedRenderingState.INSTANCE.setGbufferProjection(new Matrix4f().setPerspective(1.1F, 16F / 9F, 512F, 0.05F, true));
		List<IrisVulkanUniformSnapshot.Field> requested = List.of(
			new IrisVulkanUniformSnapshot.Field("gbufferPreviousModelView", "mat4"),
			new IrisVulkanUniformSnapshot.Field("gbufferModelView", "mat4"),
			new IrisVulkanUniformSnapshot.Field("frameCounter", "int"),
			new IrisVulkanUniformSnapshot.Field("framemod8", "float"));
		Matrix4f expectedPrevious = new Matrix4f();
		try {
			for (int frame = 1; frame <= 32; frame++) {
				Matrix4f camera = new Matrix4f().rotateX(frame * 0.007F).rotateY(frame * 0.025F);
				CapturedRenderingState.INSTANCE.setGbufferModelView(camera);
				// This is the current production order: beginFrame clears the cache,
				// executor advances the counter, then first capture advances history.
				custom.beginFrame();
				counter.setInt(null, frame);
				for (int draw = 0; draw < 3; draw++) {
					ByteBuffer values = IrisVulkanUniformSnapshot.capture(requested).data().order(ByteOrder.nativeOrder());
					checkMatrix(new Matrix4f().set(0, values), expectedPrevious, "previous camera", frame, draw);
					checkMatrix(new Matrix4f().set(64, values), camera, "current camera", frame, draw);
					if (values.getInt(128) != frame || values.getFloat(132) != frame % 8) {
						throw new AssertionError("Frame counter/custom jitter disagree at frame " + frame + " draw " + draw);
					}
				}
				expectedPrevious = camera;
			}
		} finally {
			IrisVulkanUniformSnapshot.unregisterActiveCustomUniforms(custom);
		}
		System.out.println("PASS: actual cache/history methods preserve current and previous rotating-camera matrices and framemod8 over32 frames/96 draws");
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field field = IrisVulkanUniformSnapshot.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static void checkMatrix(Matrix4f actual, Matrix4f expected, String label, int frame, int draw) {
		if (!actual.equals(expected, 1.0e-7F)) throw new AssertionError(label + " mismatch at frame " + frame + " draw " + draw);
	}
}
