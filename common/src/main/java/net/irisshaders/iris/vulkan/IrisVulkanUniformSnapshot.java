package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.layer.GbufferPrograms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniformFixedInputUniformsHolder;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Vulkan representation of legacy Iris loose uniforms. The shader patcher
 * turns these declarations into one std140 block, and this class snapshots the
 * values at bind time so a pass never observes a later pass' state.
 */
public final class IrisVulkanUniformSnapshot {
	public static final String BLOCK_NAME = "IrisUniforms";
	private static final Object NO_NATIVE_VALUE = new Object();
	private static final Map<String, Matrix4f> CURRENT_MATRICES = new LinkedHashMap<>();
	private static final Map<String, Matrix4f> PREVIOUS_MATRICES = new LinkedHashMap<>();
	private static volatile CustomUniforms activeCustomUniforms;
	private static volatile java.lang.reflect.Field bossEventsField;
	private static int previousFrame = Integer.MIN_VALUE;
	private static boolean loggedDebugUniforms;
	private static long previousVulkanFrameNanos;
	private static float vulkanFrameTime = 1.0f / 60.0f;
	private static float vulkanFrameTimeCounter;
	private static int vulkanFrameCounter;
	private static final Vector2i primaryTextureSize = new Vector2i();
	private static Vector3d previousCameraPosition;
	private static float vulkanCameraVelocity;
	private static final int MAX_CACHED_LAYOUTS = 512;
	private static final ReferenceQueue<List<Field>> STALE_LAYOUT_FIELDS = new ReferenceQueue<>();
	private static final Map<LayoutKey, PreparedLayout> LAYOUT_CACHE = new LinkedHashMap<>(32, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<LayoutKey, PreparedLayout> eldest) {
			return size() > MAX_CACHED_LAYOUTS;
		}
	};
	private static final LayoutLookup LAYOUT_LOOKUP = new LayoutLookup();
	private static final int MAX_STACK_UPLOAD_BYTES = 16 * 1024;
	private static final int UPLOAD_STACK_HEADROOM = 4 * 1024;
	// Render-thread counters: callers can subtract snapshots around a measured interval.
	private static long transientUploads, transientUploadBytes, stackUploads, nativeFallbackUploads, dedicatedUploads;

	private IrisVulkanUniformSnapshot() {
	}

	public static synchronized void beginFrame() {
		long now = System.nanoTime();
		if (previousVulkanFrameNanos != 0L) {
			float elapsed = (now - previousVulkanFrameNanos) * 1.0e-9f;
			vulkanFrameTime = Math.clamp(elapsed, 0.001f, 0.25f);
		}
		previousVulkanFrameNanos = now;
		vulkanFrameTimeCounter += vulkanFrameTime;
		if (vulkanFrameTimeCounter >= 3600.0f) {
			vulkanFrameTimeCounter = 0.0f;
		}
		vulkanFrameCounter = (vulkanFrameCounter + 1) % 720720;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			previousCameraPosition = null;
			vulkanCameraVelocity = 0.0f;
		} else {
			var camera = client.gameRenderer.mainCamera().position();
			Vector3d current = new Vector3d(camera.x, camera.y, camera.z);
			vulkanCameraVelocity = previousCameraPosition == null ? 0.0f : (float) current.distance(previousCameraPosition);
			previousCameraPosition = current;
		}
	}

	/** Called from binding setup using this draw's actual albedo view. */
	public static void setPrimaryTextureSize(int width, int height) {
		primaryTextureSize.set(Math.max(0, width), Math.max(0, height));
	}

	public static synchronized void registerActiveCustomUniforms(CustomUniforms customUniforms) {
		activeCustomUniforms = Objects.requireNonNull(customUniforms, "customUniforms");
	}

	public static synchronized void unregisterActiveCustomUniforms(CustomUniforms customUniforms) {
		if (activeCustomUniforms == customUniforms) {
			activeCustomUniforms = null;
		}
	}

	/** Native values must also feed pack expressions, not just the final uniform block. */
	public static CustomUniforms createCustomUniforms(ProgramSet programs, FrameUpdateNotifier notifier) {
		var inputs = new CustomUniformFixedInputUniformsHolder.Builder() {
			@Override
			public UniformHolder uniformMatrix(UniformUpdateFrequency frequency, String name, Supplier<Matrix4fc> supplier) {
				return super.uniformMatrix(frequency, name, isNativeMatrix(name) ? () -> nativeMatrix(name) : supplier);
			}

			@Override
			public CustomUniformFixedInputUniformsHolder.Builder uniform1f(UniformUpdateFrequency frequency, String name, FloatSupplier supplier) {
				return super.uniform1f(frequency, name, switch (name) {
					case "frameTime" -> () -> vulkanFrameTime;
					case "frameTimeCounter" -> () -> vulkanFrameTimeCounter;
					default -> supplier;
				});
			}

			@Override
			public CustomUniformFixedInputUniformsHolder.Builder uniform1i(UniformUpdateFrequency frequency, String name, IntSupplier supplier) {
				return super.uniform1i(frequency, name, name.equals("frameCounter") ? () -> vulkanFrameCounter : supplier);
			}
		};
		CommonUniforms.addNonDynamicUniforms(inputs, programs.getPack().getIdMap(), programs.getPackDirectives(), notifier);
		inputs.uniform1i(UniformUpdateFrequency.PER_FRAME, "entityId", CapturedRenderingState.INSTANCE::getCurrentRenderedEntity);
		inputs.uniform1i(UniformUpdateFrequency.PER_FRAME, "renderStage", () -> GbufferPrograms.getCurrentPhase().ordinal());
		inputs.uniform1i(UniformUpdateFrequency.PER_FRAME, "textureReloadCount", CapturedRenderingState.INSTANCE::getTextureReloadCount);
		inputs.uniform2i(UniformUpdateFrequency.PER_FRAME, "atlasSize", () -> new Vector2i(primaryTextureSize));
		inputs.uniform3f(UniformUpdateFrequency.PER_FRAME, "fogColor", IrisVulkanUniformSnapshot::capturedFogColor);
		// FogUniforms supplies these dynamically on OpenGL. Native custom expressions
		// need the same environmental distances from the engine's current fog state.
		inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "fogStart", () -> fogParameters().environmentalStart());
		inputs.uniform1f(UniformUpdateFrequency.PER_FRAME, "fogEnd", () -> fogParameters().environmentalEnd());
		return programs.getPack().customUniforms.build(inputs.build());
	}

	public record Field(String name, String type) {
	}

	private record Layout(Field field, int offset, int size) {
	}

	private record PreparedLayout(List<Layout> members, List<String> names, int size) {
	}

	/** Weak identity keys avoid both retaining retired shader field lists and hashing every field per draw. */
	private static final class LayoutKey extends WeakReference<List<Field>> {
		private final int hash;

		private LayoutKey(List<Field> fields) {
			super(fields, STALE_LAYOUT_FIELDS);
			hash = System.identityHashCode(fields);
		}

		@Override
		public int hashCode() { return hash; }
	}

	/** Reused only while holding preparedLayout's monitor; never inserted into the cache. */
	private static final class LayoutLookup {
		private List<Field> fields;

		@Override
		public int hashCode() { return System.identityHashCode(fields); }

		@Override
		public boolean equals(Object other) {
			return other instanceof LayoutKey key && fields == key.get();
		}
	}

	public record Snapshot(ByteBuffer data, int size) {
		public GpuBuffer upload() {
			ByteBuffer source = data.duplicate();
			source.clear();
			dedicatedUploads++;
			return com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(
				() -> "Iris Vulkan per-pass uniforms", GpuBuffer.USAGE_UNIFORM, source);
		}
	}

	public static Optional<Field> field(String name, String type, String arraySuffix) {
		if (arraySuffix != null && !arraySuffix.isBlank()) {
			return Optional.empty();
		}

		// bossBattle is a standard OptiFine/Iris loose uniform. Resolve it before
		// consulting the optional pack pipeline so startup diagnostics do not need
		// a live pipeline just to validate this built-in field.
		String expected = expectedType(name);
		if (!type.equals(expected)) {
			return Optional.empty();
		}

		return Optional.of(new Field(name, type));
	}

	public static String unsupportedReason(String name, String type, String arraySuffix) {
		if (arraySuffix != null && !arraySuffix.isBlank()) {
			return "uniform arrays are not represented";
		}

		String expected = expectedType(name);
		if (expected == null) {
			return "no live Iris/Mojang snapshot source";
		}

		return "snapshot source is " + expected;
	}

	public static String declaration(Collection<Field> fields) {
		List<Field> ordered = List.copyOf(fields);
		StringBuilder block = new StringBuilder("layout(std140) uniform ").append(BLOCK_NAME).append(" {\n");

		for (Field field : ordered) {
			block.append("    ").append(field.type()).append(' ').append(field.name()).append(";\n");
		}

		// Keep block members in the global namespace. GLSL's normal scope rules then
		// allow shader-local variables to shadow a uniform without macro expansion
		// turning the local declaration into an assignment to the uniform block.
		block.append("};\n");

		return block.toString();
	}

	public static Snapshot capture(Collection<Field> fields) {
		PreparedLayout prepared = prepareCapture(fields);
		ByteBuffer data = ByteBuffer.allocateDirect(prepared.size()).order(ByteOrder.nativeOrder());
		writeCapture(prepared, data);
		return new Snapshot(data, prepared.size());
	}

	/** Fresh values for this draw; bind the engine-owned slice only in the current submission. */
	public static GpuBufferSlice uploadTransient(Collection<Field> fields) {
		var device = RenderSystem.getDevice();
		return uploadTransient(fields, device.createCommandEncoder().transientMemory(),
			device.getDeviceInfo().limits().minUniformOffsetAlignment());
	}

	static GpuBufferSlice uploadTransient(Collection<Field> fields, TransientMemory memory, int minimumAlignment) {
		if (minimumAlignment <= 0 || (minimumAlignment & (minimumAlignment - 1)) != 0)
			throw new IllegalArgumentException("Invalid uniform offset alignment " + minimumAlignment);
		PreparedLayout prepared = prepareCapture(fields);
		int size = prepared.size();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			boolean onStack = size <= MAX_STACK_UPLOAD_BYTES && size + UPLOAD_STACK_HEADROOM <= stack.getPointer();
			// calloc preserves the original capture's zeroed std140 padding. Large
			// blocks use explicitly freed native memory instead of overflowing the stack.
			ByteBuffer data = (onStack ? stack.calloc(size) : MemoryUtil.memCalloc(size)).order(ByteOrder.nativeOrder());
			try {
				writeCapture(prepared, data);
				// Minecraft 26.2 uploadGpu synchronously copies these CPU bytes into
				// its submission-owned mapped arena before returning. No caller fence,
				// buffer close, or cache of per-draw values is needed or permitted here.
				GpuBufferSlice uploaded = memory.uploadGpu(data, Math.max(16, minimumAlignment), GpuBuffer.USAGE_UNIFORM);
				transientUploads++;
				transientUploadBytes += size;
				if (onStack) stackUploads++; else nativeFallbackUploads++;
				return uploaded.length() == size ? uploaded : uploaded.slice(0, size);
			} finally {
				if (!onStack) MemoryUtil.memFree(data);
			}
		}
	}

	public record UploadStats(long transientUploads, long transientBytes, long stackUploads,
							  long nativeFallbackUploads, long dedicatedUploads) { }

	public static UploadStats uploadStats() {
		return new UploadStats(transientUploads, transientUploadBytes, stackUploads, nativeFallbackUploads, dedicatedUploads);
	}

	private static PreparedLayout prepareCapture(Collection<Field> fields) {
		syncPreviousMatrices();
		PreparedLayout prepared = preparedLayout(fields);
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			active.updateFor(prepared.names());
		}
		return prepared;
	}

	private static void writeCapture(PreparedLayout prepared, ByteBuffer data) {
		for (Layout layout : prepared.members()) {
			write(data, layout.offset(), layout.field());
		}
		logDebugUniformsOnce(prepared.members());

		data.clear();
	}

	private static void logDebugUniformsOnce(List<Layout> layouts) {
		String configured = System.getProperty("iris.vulkan.debugUniforms");
		if (loggedDebugUniforms || configured == null || configured.isBlank()) {
			return;
		}

		Map<String, Field> available = new LinkedHashMap<>();
		for (Layout layout : layouts) {
			available.put(layout.field().name(), layout.field());
		}
		List<String> values = new ArrayList<>();
		for (String requested : configured.split(",")) {
			String name = requested.trim();
			Field field = available.get(name);
			if (field != null) {
				values.add(name + "=" + value(name, field.type()));
			}
		}
		if (!values.isEmpty()) {
			loggedDebugUniforms = true;
			Iris.logger.info("Native Vulkan uniform diagnostic: {}", String.join(", ", values));
		}
	}

	private static List<Layout> layouts(Collection<Field> fields) {
		return preparedLayout(fields).members();
	}

	private static synchronized PreparedLayout preparedLayout(Collection<Field> fields) {
		// ResourceSet owns immutable lists, so this is an allocation-free identity
		// operation on the draw path. Mutable callers receive a detached snapshot.
		List<Field> ordered = List.copyOf(fields);
		LayoutKey stale;
		while ((stale = (LayoutKey) STALE_LAYOUT_FIELDS.poll()) != null) LAYOUT_CACHE.remove(stale);
		LAYOUT_LOOKUP.fields = ordered;
		try {
			PreparedLayout cached = LAYOUT_CACHE.get(LAYOUT_LOOKUP);
			if (cached != null) return cached;
		} finally {
			LAYOUT_LOOKUP.fields = null;
		}

		List<Layout> layouts = new ArrayList<>(ordered.size());
		List<String> names = new ArrayList<>(ordered.size());
		int offset = 0;

		for (Field field : ordered) {
			int alignment = alignment(field.type());
			int size = size(field.type());
			offset = align(offset, alignment);
			layouts.add(new Layout(field, offset, size));
			names.add(field.name());
			offset += size;
		}

		PreparedLayout prepared = new PreparedLayout(List.copyOf(layouts), List.copyOf(names), Math.max(16, align(offset, 16)));
		LAYOUT_CACHE.put(new LayoutKey(ordered), prepared);
		return prepared;
	}

	private static void write(ByteBuffer data, int offset, Field field) {
		Object value = nativeValue(field.name());
		if (value == NO_NATIVE_VALUE) {
			CustomUniforms custom = activeCustomUniforms;
			if (custom != null && custom.writeCachedStd140(field.name(), field.type(), data, offset)) return;
			value = fallbackValue(field.name(), field.type());
		}
		// The capture owns this buffer and clears it after all members. Reuse
		// its cursor rather than allocating a direct-buffer wrapper per member.
		ByteBuffer target = data;
		target.position(offset);

		switch (field.type()) {
			case "float" -> target.putFloat(((Number) value).floatValue());
			case "int", "uint" -> target.putInt(((Number) value).intValue());
			case "bool" -> target.putInt((Boolean) value ? 1 : 0);
			case "vec2" -> put(target, (Vector2f) value);
			case "ivec2" -> put(target, (Vector2i) value);
			case "vec3" -> put(target, (Vector3f) value);
			case "ivec3" -> put(target, (Vector3i) value);
			case "vec4" -> put(target, (Vector4f) value);
			case "ivec4" -> put(target, (Vector4i) value);
			case "mat3" -> put(target, (Matrix3f) value);
			case "mat4" -> ((Matrix4fc) value).get(target);
			default -> throw unsupported(field.name(), field.type(), "no std140 writer");
		}
	}

	private static Object value(String name, String type) {
		Object nativeValue = nativeValue(name);
		if (nativeValue != NO_NATIVE_VALUE) return nativeValue;
		Optional<CustomUniforms.Snapshot> custom = customUniform(name);
		if (custom.isPresent() && custom.get().type().equals(type)) return custom.get().value();
		return fallbackValue(name, type);
	}

	/** Shared priority list for immediate serialization and diagnostic snapshots. */
	private static Object nativeValue(String name) {
		if (isNativeMatrix(name)) return nativeMatrix(name);
		Object vulkanFrameValue = switch (name) {
			case "frameTime" -> vulkanFrameTime;
			case "frameTimeCounter" -> vulkanFrameTimeCounter;
			case "frameCounter" -> vulkanFrameCounter;
			default -> null;
		};
		if (vulkanFrameValue != null) {
			return vulkanFrameValue;
		}
		// These change between draws in one frame and must bypass the custom input cache.
		// This scope also selects iris_ProjMat, keeping held glint's projection
		// and depth compression paired while world glint uses neither hand state.
		if (name.equals("iris_NativeHandDraw")) return IrisVulkanPhaseContext.handProjection() == null ? 0 : 1;
		if (name.equals("entityId")) return CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
		if (name.equals("renderStage")) return GbufferPrograms.getCurrentPhase().ordinal();
		if (name.equals("textureReloadCount")) return CapturedRenderingState.INSTANCE.getTextureReloadCount();
		if (name.equals("iris_NativeEntityIds")) return new Vector3i(CapturedRenderingState.INSTANCE.getCurrentRenderedEntity(),
			CapturedRenderingState.INSTANCE.getCurrentRenderedBlockEntity(), CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
		if (name.equals("atlasSize")) return new Vector2i(primaryTextureSize);
		if (name.equals("fogColor")) return capturedFogColor();
		if (name.equals("fogStart") || name.equals("iris_FogStart")) return fogParameters().environmentalStart();
		if (name.equals("fogEnd") || name.equals("iris_FogEnd")) return fogParameters().environmentalEnd();
		if (name.equals("bossBattle")) return bossBattle();
		return NO_NATIVE_VALUE;
	}

	/**
	 * OptiFine's OpenGL source derives this value from the first translatable boss
	 * bar name.  26.3 keeps the live bars private inside BossHealthOverlay, so
	 * read that same engine-owned map rather than manufacturing a constant.
	 */
	private static int bossBattle() {
		Minecraft client = client();
		if (client.level == null || client.gui == null || client.gui.hud == null) {
			return 0;
		}

		String key = bossName(client.gui.hud.getBossOverlay());
		return bossBattleValue(key);
	}

	static int bossBattleValue(String key) {
		if (key == null) return 0;
		return switch (key) {
			case "entity.minecraft.ender_dragon" -> 2;
			case "entity.minecraft.wither" -> 3;
			case "event.minecraft.raid" -> 4;
			default -> 1;
		};
	}

	private static String bossName(BossHealthOverlay overlay) {
		try {
			java.lang.reflect.Field field = bossEventsField;
			if (field == null) {
				field = BossHealthOverlay.class.getDeclaredField("events");
				field.setAccessible(true);
				bossEventsField = field;
			}
			Object value = field.get(overlay);
			if (!(value instanceof Map<?, ?> events)) return null;
			for (Object entry : events.values()) {
				if (!(entry instanceof BossEvent event)) continue;
				Component name = event.getName();
				if (name != null && name.getContents() instanceof TranslatableContents translatable) {
					return translatable.getKey();
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// A render uniform must not break a frame if another mod
			// replaces the HUD implementation or denies reflective access.
		}
		return null;
	}

	private static Object fallbackValue(String name, String type) {
		Minecraft client = client();

		return switch (name) {
			case "iris_NormalMatrix", "iris_NormalMat" -> normalMatrix(
				currentModelView(name));
			case "iris_LightmapTextureMatrix" -> lightmapTextureMatrix();
			case "iris_currentAlphaTest" -> CapturedRenderingState.INSTANCE.getCurrentAlphaTest();
			case "iris_FogDensity" -> Math.max(0.0f, CapturedRenderingState.INSTANCE.getFogDensity());
			case "iris_FogColor" -> fogColor();
			case "iris_ScreenSize" -> screenSize();
			case "viewWidth" -> (float) client.gameRenderer.mainRenderTarget().width;
			case "viewHeight" -> (float) client.gameRenderer.mainRenderTarget().height;
			case "aspectRatio" -> (float) client.gameRenderer.mainRenderTarget().width /
				client.gameRenderer.mainRenderTarget().height;
			case "near" -> projectionPlanes(CapturedRenderingState.INSTANCE.getGbufferProjection()).near();
			case "far" -> projectionPlanes(CapturedRenderingState.INSTANCE.getGbufferProjection()).far();
			case "frameTimeCounter" -> vulkanFrameTimeCounter;
			case "frameTime" -> vulkanFrameTime;
			case "frameCounter" -> vulkanFrameCounter;
			case "worldTime" -> worldTime(client);
			case "worldDay" -> worldDay(client);
			case "moonPhase" -> client.gameRenderer.mainCamera().attributeProbe()
				.getValue(EnvironmentAttributes.MOON_PHASE, CapturedRenderingState.INSTANCE.getTickDelta()).index();
			case "currentDate" -> date();
			case "currentTime" -> time();
			case "currentYearTime" -> yearTime();
			case "rainStrength", "wetness" -> client.level == null ? 0.0f : client.level.getRainLevel(
				CapturedRenderingState.INSTANCE.getTickDelta());
			case "eyeBrightness" -> eyeBrightness(client);
			case "skyColor" -> skyColor(client);
			case "isEyeInWater" -> eyeInWater(client);
			case "screenBrightness" -> client.options.gamma().get().floatValue();
			case "darknessLightFactor" -> CapturedRenderingState.INSTANCE.getDarknessLightFactor();
			case "cameraPositionFract" -> cameraPositionFract(client);
			case "cameraPositionInt" -> cameraPositionInt(client);
			case "cameraPosition", "eyeAltitude" -> cameraPosition(client, name);
			case "velocity" -> vulkanCameraVelocity;
			default -> throw unsupported(name, type, "no live Iris/Mojang source");
		};
	}

	private static boolean isNativeMatrix(String name) {
		return switch (name) {
			case "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection", "gbufferProjectionInverse",
				 "gbufferPreviousModelView", "gbufferPreviousProjection", "shadowModelView", "shadowProjection",
				 "shadowModelViewInverse", "shadowProjectionInverse", "iris_ModelViewMatrix", "iris_ModelViewMat",
				 "iris_ModelViewMatrixInverse", "iris_ModelViewMatInverse", "iris_ProjectionMatrix", "iris_ProjMat",
				 "iris_ProjectionMatrixInverse", "iris_ProjMatInverse" -> true;
			default -> false;
		};
	}

	private static Matrix4f nativeMatrix(String name) {
		if (name.startsWith("shadow")) {
			Matrix4f matrix = IrisVulkanShadowRenderer.uniformMatrix(name);
			if (matrix != null) return matrix;
			// Preserve the startup matrices before the first native shadow frame.
			Matrix4f fallback = requiredMatrix(name.contains("Projection") ? ShadowRenderer.PROJECTION : ShadowRenderer.MODELVIEW, name);
			return name.endsWith("Inverse") ? fallback.invert() : fallback;
		}
		return switch (name) {
			case "gbufferModelView" -> requiredMatrix(CapturedRenderingState.INSTANCE.getGbufferModelView(), name);
			case "gbufferModelViewInverse" -> requiredMatrix(CapturedRenderingState.INSTANCE.getGbufferModelView(), name).invert();
			case "gbufferProjection" -> shaderpackProjection(name);
			case "gbufferProjectionInverse" -> shaderpackProjection(name).invert();
			case "gbufferPreviousModelView" -> previousMatrix("gbufferModelView", name);
			case "gbufferPreviousProjection" -> previousMatrix("gbufferProjection", name);
			case "iris_ModelViewMatrix", "iris_ModelViewMat" -> currentModelView(name);
			case "iris_ModelViewMatrixInverse", "iris_ModelViewMatInverse" -> currentModelView(name).invert();
			case "iris_ProjectionMatrix", "iris_ProjMat" -> currentProjection(name);
			case "iris_ProjectionMatrixInverse", "iris_ProjMatInverse" -> currentProjection(name).invert();
			default -> throw unsupported(name, "mat4", "no native matrix source");
		};
	}

	private static Matrix4f currentModelView(String name) {
		Matrix4fc uploaded = IrisVulkanEntityContext.currentDrawModelView();
		if (uploaded != null) return new Matrix4f(uploaded);
		// Immediate draws (sky, clouds, terrain) do not have PreparedRenderType
		// metadata. Match the engine's live model-view stack for those draws.
		return com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy();
	}

	private static Matrix4f currentProjection(String name) {
		if (IrisVulkanShadowRenderer.active()) return nativeMatrix("shadowProjection");
		Matrix4fc hand = IrisVulkanPhaseContext.handProjection();
		return hand == null ? shaderpackProjection(name) : shaderpackProjection(hand, name);
	}

	private static Matrix4f previousMatrix(String key, String name) {
		Matrix4f previous = PREVIOUS_MATRICES.get(key);
		return previous == null ? new Matrix4f() : new Matrix4f(previous);
	}

	private static Matrix4f requiredMatrix(Matrix4fc matrix, String name) {
		if (matrix == null) {
			throw unsupported(name, "mat4", "captured matrix is unavailable for this pass");
		}

		return new Matrix4f(matrix);
	}

	private static void syncPreviousMatrices() {
		int frame = vulkanFrameCounter;
		if (frame == previousFrame) {
			return;
		}

		previousFrame = frame;
		PREVIOUS_MATRICES.clear();
		PREVIOUS_MATRICES.putAll(CURRENT_MATRICES);
		CURRENT_MATRICES.clear();

		Matrix4fc modelView = CapturedRenderingState.INSTANCE.getGbufferModelView();
		Matrix4fc projection = CapturedRenderingState.INSTANCE.getGbufferProjection();
		if (modelView != null) CURRENT_MATRICES.put("gbufferModelView", new Matrix4f(modelView));
		if (projection != null) CURRENT_MATRICES.put("gbufferProjection", shaderpackProjection("gbufferProjection"));
	}

	private static Matrix4f shaderpackProjection(String name) {
		return shaderpackProjection(CapturedRenderingState.INSTANCE.getGbufferProjection(), name);
	}

	private static Matrix4f shaderpackProjection(Matrix4fc nativeProjection, String name) {
		if (nativeProjection == null) {
			throw unsupported(name, "mat4", "captured matrix is unavailable for this pass");
		}

		return IrisVulkanProjection.toShaderpack(nativeProjection);
	}

	private static ProjectionPlanes projectionPlanes(Matrix4fc projection) {
		if (projection != null) {
			float depthScale = projection.m22();
			float depthTranslate = projection.m32();
			float far = depthScale == 0.0f ? Float.NaN : depthTranslate / depthScale;
			float near = depthTranslate / (depthScale + 1.0f);
			if (Float.isFinite(near) && Float.isFinite(far) && near > 0.0f && far > near) {
				return new ProjectionPlanes(near, far);
			}
		}

		Minecraft client = client();
		return new ProjectionPlanes(0.05f, Math.max(16.0f,
			(float) client.options.getEffectiveRenderDistance() * 16.0f));
	}

	private static Matrix3f normalMatrix(Matrix4f modelView) {
		return modelView.invert().transpose3x3(new Matrix3f());
	}

	private static Matrix4f lightmapTextureMatrix() {
		return new Matrix4f().m00(0.00390625f).m11(0.00390625f).m22(0.00390625f)
			.m30(0.03125f).m31(0.03125f).m32(0.03125f);
	}

	private static Vector4f fogColor() {
		FogParameters fog = fogParameters();
		return fog == FogParameters.NONE
			? new Vector4f(1.0f, 1.0f, 1.0f, 1.0f)
			: new Vector4f(fog.red(), fog.green(), fog.blue(), fog.alpha());
	}

	private static Vector3f capturedFogColor() {
		var color = CapturedRenderingState.INSTANCE.getFogColor();
		return new Vector3f((float) color.x, (float) color.y, (float) color.z);
	}

	private static FogParameters fogParameters() {
		return ((FogStorage) client().gameRenderer).sodium$getFogParameters();
	}

	private static Vector2f screenSize() {
		return new Vector2f(client().gameRenderer.mainRenderTarget().width, client().gameRenderer.mainRenderTarget().height);
	}

	private static Minecraft client() {
		return Minecraft.getInstance();
	}

	private static int worldTime(Minecraft client) {
		return client.level == null ? 0 : (int) (client.level.getDefaultClockTime() % 24000L);
	}

	private static int worldDay(Minecraft client) {
		return client.level == null ? 0 : (int) (client.level.getDefaultClockTime() / 24000L);
	}

	private static Vector3i date() {
		LocalDateTime now = LocalDateTime.now();
		return new Vector3i(now.getYear(), now.getMonthValue(), now.getDayOfMonth());
	}

	private static Vector3i time() {
		LocalDateTime now = LocalDateTime.now();
		return new Vector3i(now.getHour(), now.getMinute(), now.getSecond());
	}

	private static Vector2i yearTime() {
		LocalDateTime now = LocalDateTime.now();
		int elapsed = (now.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60 + now.getSecond();
		return new Vector2i(elapsed, now.toLocalDate().lengthOfYear() * 86400 - elapsed);
	}

	private static Vector2i eyeBrightness(Minecraft client) {
		if (client.level == null || client.getCameraEntity() == null) {
			return new Vector2i();
		}

		var entity = client.getCameraEntity();
		var position = entity.blockPosition();
		return new Vector2i(client.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, position) * 16,
			client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, position) * 16);
	}

	private static Vector3f skyColor(Minecraft client) {
		if (client.level == null || client.getCameraEntity() == null) {
			return new Vector3f();
		}

		var color = client.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR,
			CapturedRenderingState.INSTANCE.getTickDelta());
		return new Vector3f(color);
	}

	private static int eyeInWater(Minecraft client) {
		FogType type = client.gameRenderer.mainCamera().getFluidInCamera();
		if (type == FogType.WATER) return 1;
		if (type == FogType.LAVA && client.player != null && !client.player.isSpectator()) return 2;
		if (type == FogType.POWDER_SNOW) return 3;
		return 0;
	}

	private static Vector3f cameraPositionFract(Minecraft client) {
		var position = client.gameRenderer.mainCamera().position();
		return new Vector3f((float) (position.x - Math.floor(position.x)), (float) (position.y - Math.floor(position.y)),
			(float) (position.z - Math.floor(position.z)));
	}

	private static Vector3i cameraPositionInt(Minecraft client) {
		var position = client.gameRenderer.mainCamera().position();
		return new Vector3i((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
	}

	private static Object cameraPosition(Minecraft client, String name) {
		var position = client.gameRenderer.mainCamera().position();
		if (name.equals("eyeAltitude")) return (float) position.y;
		return new Vector3f((float) position.x, (float) position.y, (float) position.z);
	}

	private static Optional<CustomUniforms.Snapshot> customUniform(String name) {
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			Optional<CustomUniforms.Snapshot> snapshot = active.lookup(name);
			if (snapshot.isPresent()) {
				return snapshot;
			}
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
			return irisPipeline.getCustomUniforms().lookup(name);
		}
		return Optional.empty();
	}

	private static String expectedType(String name) {
		if (name.equals("bossBattle")) return "int";
		Optional<String> customType = customType(name);
		return customType.orElseGet(() -> hardcodedExpectedType(name));
	}

	private static Optional<String> customType(String name) {
		CustomUniforms active = activeCustomUniforms;
		if (active != null) {
			Optional<String> type = active.typeOf(name);
			if (type.isPresent()) {
				return type;
			}
		}

		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
		if (pipeline instanceof IrisRenderingPipeline irisPipeline) {
			return irisPipeline.getCustomUniforms().typeOf(name);
		}

		return Optional.empty();
	}

	private static String hardcodedExpectedType(String name) {
		return switch (name) {
			case "gbufferModelView", "gbufferProjection", "gbufferModelViewInverse", "gbufferProjectionInverse",
				"gbufferPreviousModelView", "gbufferPreviousProjection", "shadowModelView", "shadowProjection",
				"shadowModelViewInverse", "shadowProjectionInverse", "iris_ModelViewMatrix", "iris_ProjectionMatrix",
				"iris_ModelViewMat", "iris_ProjMat", "iris_ModelViewMatrixInverse", "iris_ProjectionMatrixInverse",
				"iris_ModelViewMatInverse", "iris_ProjMatInverse" -> "mat4";
			case "iris_NormalMatrix", "iris_NormalMat" -> "mat3";
			case "iris_LightmapTextureMatrix" -> "mat4";
			case "iris_currentAlphaTest", "iris_FogDensity", "iris_FogStart", "iris_FogEnd", "fogStart", "fogEnd",
				"viewWidth", "viewHeight", "aspectRatio", "near", "far", "frameTimeCounter", "frameTime",
				"rainStrength", "wetness", "screenBrightness", "darknessLightFactor", "eyeAltitude", "velocity" -> "float";
			case "iris_FogColor" -> "vec4";
			case "iris_ScreenSize" -> "vec2";
			case "frameCounter", "worldTime", "worldDay", "moonPhase", "isEyeInWater", "entityId", "renderStage", "textureReloadCount", "iris_NativeHandDraw", "bossBattle" -> "int";
			case "currentDate", "currentTime", "cameraPositionInt", "iris_NativeEntityIds" -> "ivec3";
			case "currentYearTime", "eyeBrightness", "atlasSize" -> "ivec2";
			case "skyColor", "cameraPositionFract", "cameraPosition", "fogColor" -> "vec3";
			default -> null;
		};
	}

	private static int alignment(String type) {
		return switch (type) {
			case "vec2", "ivec2" -> 8;
			case "vec3", "vec4", "ivec3", "ivec4", "mat3", "mat4" -> 16;
			default -> 4;
		};
	}

	private static int size(String type) {
		return switch (type) {
			case "vec2", "ivec2" -> 8;
			case "vec3", "ivec3" -> 12;
			case "vec4", "ivec4" -> 16;
			case "mat3" -> 48;
			case "mat4" -> 64;
			default -> 4;
		};
	}

	private static int align(int value, int alignment) {
		return (value + alignment - 1) & -alignment;
	}

	private static void put(ByteBuffer target, Vector2f value) {
		target.putFloat(value.x).putFloat(value.y);
	}

	private static void put(ByteBuffer target, Vector2i value) {
		target.putInt(value.x).putInt(value.y);
	}

	private static void put(ByteBuffer target, Vector3f value) {
		target.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(0.0f);
	}

	private static void put(ByteBuffer target, Vector3i value) {
		target.putInt(value.x).putInt(value.y).putInt(value.z).putInt(0);
	}

	private static void put(ByteBuffer target, Vector4f value) {
		target.putFloat(value.x).putFloat(value.y).putFloat(value.z).putFloat(value.w);
	}

	private static void put(ByteBuffer target, Vector4i value) {
		target.putInt(value.x).putInt(value.y).putInt(value.z).putInt(value.w);
	}

	private static void put(ByteBuffer target, Matrix3f value) {
		target.putFloat(value.m00()).putFloat(value.m01()).putFloat(value.m02()).putFloat(0.0f);
		target.putFloat(value.m10()).putFloat(value.m11()).putFloat(value.m12()).putFloat(0.0f);
		target.putFloat(value.m20()).putFloat(value.m21()).putFloat(value.m22()).putFloat(0.0f);
	}

	private record ProjectionPlanes(float near, float far) {
	}

	private static UnsupportedOperationException unsupported(String name, String type, String reason) {
		return new UnsupportedOperationException("Unsupported Vulkan uniform " + type + " " + name + ": " + reason);
	}
}
