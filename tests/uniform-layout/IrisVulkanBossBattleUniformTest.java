package net.irisshaders.iris.vulkan;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Verifies the OptiFine/OpenGL bossBattle key mapping used by native snapshots. */
public final class IrisVulkanBossBattleUniformTest {
	private static int checks;
	private static final Method BOSS_NAME = method("bossName", BossHealthOverlay.class);
	private static final Field EVENTS = field(BossHealthOverlay.class, "events");

	public static void main(String[] args) throws Exception {
		var expectedType = IrisVulkanUniformSnapshot.class.getDeclaredMethod("hardcodedExpectedType", String.class);
		expectedType.setAccessible(true);
		check("int".equals(expectedType.invoke(null, "bossBattle")),
			"bossBattle is an int in the native snapshot ABI");
		check(IrisVulkanUniformSnapshot.field("bossBattle", "int", null).isPresent(),
			"bossBattle is accepted as an int field");
		check(IrisVulkanUniformSnapshot.field("bossBattle", "float", null).isEmpty(),
			"bossBattle rejects a mismatched type");
		checkBossBattleLayout();
		check(bossValue() == 0, "No boss bar maps to 0");
		check(bossValue("minecraft:literal_name") == 0, "Literal boss names are ignored");
		check(bossValue("minecraft:literal_name", "entity.minecraft.ender_dragon") == 2,
			"The first translatable key after a literal maps to Ender Dragon");
		check(bossValue("entity.minecraft.ender_dragon", "entity.minecraft.wither") == 2,
			"Insertion order preserves the first translatable key");
		check(bossValue("entity.minecraft.wither") == 3, "Wither maps to 3");
		check(bossValue("event.minecraft.raid") == 4, "Raid maps to 4");
		check(bossValue("minecraft:custom_boss") == 1, "Other translatable boss bars map to 1");
		System.out.println("PASS: " + checks + " bossBattle live-key mapping checks");
	}

	private static int bossValue(String... keys) throws Exception {
		BossHealthOverlay overlay = new BossHealthOverlay(null);
		@SuppressWarnings("unchecked") Map<UUID, BossEvent> events = (Map<UUID, BossEvent>) EVENTS.get(overlay);
		for (String key : keys) {
			Component name = key.startsWith("minecraft:literal") ? Component.literal(key) : Component.translatable(key);
			events.put(UUID.randomUUID(), new BossEvent(UUID.randomUUID(), name,
				BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.PROGRESS) { });
		}
		String key = (String) BOSS_NAME.invoke(null, overlay);
		return IrisVulkanUniformSnapshot.bossBattleValue(key);
	}

	private static void checkBossBattleLayout() throws Exception {
		Method layouts = IrisVulkanUniformSnapshot.class.getDeclaredMethod("layouts", Collection.class);
		layouts.setAccessible(true);
		List<?> members = (List<?>) layouts.invoke(null, List.of(new IrisVulkanUniformSnapshot.Field("bossBattle", "int")));
		Object member = members.getFirst();
		Method offset = member.getClass().getDeclaredMethod("offset");
		Method size = member.getClass().getDeclaredMethod("size");
		offset.setAccessible(true);
		size.setAccessible(true);
		check((Integer) offset.invoke(member) == 0 && (Integer) size.invoke(member) == Integer.BYTES,
			"bossBattle serializes as one std140 int at offset 0");
		var counter = IrisVulkanUniformSnapshot.class.getDeclaredField("vulkanFrameCounter");
		counter.setAccessible(true);
		int saved = counter.getInt(null);
		try {
			counter.setInt(null, 0x10203040);
			Method write = IrisVulkanUniformSnapshot.class.getDeclaredMethod("write", ByteBuffer.class, int.class,
				IrisVulkanUniformSnapshot.Field.class);
			write.setAccessible(true);
			ByteBuffer bytes = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
			write.invoke(null, bytes, 0, new IrisVulkanUniformSnapshot.Field("frameCounter", "int"));
			check(bytes.getInt(0) == 0x10203040, "The shared int writer preserves exact 32-bit values");
		} finally {
			counter.setInt(null, saved);
		}
	}

	private static Method method(String name, Class<?>... parameterTypes) {
		try {
			Method method = IrisVulkanUniformSnapshot.class.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return method;
		} catch (ReflectiveOperationException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	private static Field field(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		checks++;
	}
}
