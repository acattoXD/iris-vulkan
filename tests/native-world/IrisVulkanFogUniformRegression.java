package net.irisshaders.iris.vulkan;

import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Exercises production name routing and std140 capture without launching a game.
 * The test substitutes only the FogStorage accessor with changing engine-shaped
 * FogParameters; environmental distances deliberately differ from terrain range.
 */
public final class IrisVulkanFogUniformRegression {
	private static final String SNAPSHOT = "net.irisshaders.iris.vulkan.IrisVulkanUniformSnapshot";
	private static FogParameters currentFog;

	public static FogParameters testFogParameters() {
		return currentFog;
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Usage: compiled production classes directory");
		ClassLoader loader = new SnapshotLoader(Path.of(args[0]));
		Class<?> snapshot = loader.loadClass(SNAPSHOT);
		Class<?> field = loader.loadClass(SNAPSHOT + "$Field");
		var constructor = field.getConstructor(String.class, String.class);
		var expectedType = snapshot.getDeclaredMethod("hardcodedExpectedType", String.class);
		expectedType.setAccessible(true);
		List<Object> fields = new ArrayList<>();
		for (String name : List.of("fogStart", "fogEnd", "iris_FogStart", "iris_FogEnd")) {
			if (!"float".equals(expectedType.invoke(null, name))) {
				throw new AssertionError("Missing float source for " + name);
			}
			fields.add(constructor.newInstance(name, "float"));
		}
		var capture = snapshot.getMethod("capture", Collection.class);
		var data = loader.loadClass(SNAPSHOT + "$Snapshot").getMethod("data");
		List<FogParameters> states = List.of(
			new FogParameters(0.1f, 0.2f, 0.3f, 1.0f, 5.0f, 30.0f, 128.0f, 256.0f, 256.0f),
			new FogParameters(0.0f, 0.2f, 0.5f, 1.0f, 0.0f, 12.0f, 32.0f, 96.0f, 96.0f),
			FogParameters.NONE);
		for (FogParameters state : states) {
			currentFog = state;
			// No beginFrame call: these uniforms must observe changed fog between draws.
			ByteBuffer values = ((ByteBuffer) data.invoke(capture.invoke(null, fields))).order(ByteOrder.nativeOrder());
			if (values.remaining() != 16) throw new AssertionError("Incorrect four-float std140 block size");
			for (int offset : new int[]{0, 8}) check(values.getFloat(offset), state.environmentalStart(), "start");
			for (int offset : new int[]{4, 12}) check(values.getFloat(offset), state.environmentalEnd(), "end");
		}
		System.out.println("PASS: fog aliases capture environmental distances, preserve NONE, and refresh between draws in one frame");
	}

	private static void check(float actual, float expected, String label) {
		if (Float.floatToIntBits(actual) != Float.floatToIntBits(expected)) {
			throw new AssertionError("Fog " + label + ": " + actual + " != " + expected);
		}
	}

	private static final class SnapshotLoader extends ClassLoader {
		private final Path classes;

		SnapshotLoader(Path classes) {
			super(IrisVulkanFogUniformRegression.class.getClassLoader());
			this.classes = classes;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(SNAPSHOT) && !name.startsWith(SNAPSHOT + "$")) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					try {
						byte[] bytes = Files.readAllBytes(classes.resolve(name.replace('.', '/') + ".class"));
						if (name.equals(SNAPSHOT)) bytes = substituteFogAccessor(bytes);
						loaded = defineClass(name, bytes, 0, bytes.length);
					} catch (Exception failure) {
						throw new ClassNotFoundException(name, failure);
					}
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}

	private static byte[] substituteFogAccessor(byte[] original) {
		ClassWriter writer = new ClassWriter(0);
		new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
				if (!name.equals("fogParameters")) return method;
				method.visitCode();
				method.visitMethodInsn(Opcodes.INVOKESTATIC,
					IrisVulkanFogUniformRegression.class.getName().replace('.', '/'), "testFogParameters", descriptor, false);
				method.visitInsn(Opcodes.ARETURN);
				method.visitMaxs(1, 0);
				method.visitEnd();
				return null;
			}
		}, 0);
		return writer.toByteArray();
	}
}
