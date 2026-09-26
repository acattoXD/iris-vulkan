package net.irisshaders.iris.vulkan;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.JumpInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks every native world hook against the real installed 26.2/Sodium bytecode. */
public final class IrisVulkanMixinContractTest {
	public static void main(String[] args) throws Exception {
		int checks = 0;
		try (var files = Files.list(Path.of(args[0]))) {
			var selected = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".java"))
				.filter(p -> p.toString().contains("_Materials") || p.toString().contains("_Phases") || p.toString().contains("_WorldPasses") || p.toString().contains("_ModelView") || p.toString().contains("_PushConstants") || p.toString().contains("VKOnly_BlockEntityStateAccessor")).toList());
			// This shared camera hook is also required by the native renderer.
			selected.add(Path.of(args[0]).resolve("../MixinModelViewBobbing.java").normalize());
			for (var file : selected) {
				String name = "net/irisshaders/iris/mixin/" + (file.getParent().getFileName().toString().equals("vulkan") ? "vulkan/" : "")
					+ file.getFileName().toString().replace(".java", "");
				ClassNode mixin = read(name);
				AnnotationNode config = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
					.filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
				List<String> targets = new ArrayList<>();
				Object types = value(config, "value");
				if (types instanceof List<?> list) for (Object type : list) targets.add(((Type) type).getInternalName());
				Object names = value(config, "targets");
				if (names instanceof List<?> list) for (Object target : list) targets.add(target.toString().replace('.', '/'));
				for (String target : targets) {
					ClassNode actual = read(target);
					for (var field : mixin.fields) {
						if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream().anyMatch(a -> a.desc.endsWith("/Shadow;"))) {
							check(actual.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)), name + " field " + field.name + " -> " + target);
							checks++;
						}
					}
					for (MethodNode handler : mixin.methods) for (AnnotationNode annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
						Object selectors = value(annotation, "method");
						if (!(selectors instanceof List<?> list)) continue;
						for (Object selectorValue : list) {
							String selector = selectorValue.toString();
							int delimiter = selector.indexOf('(');
							String methodName = delimiter < 0 ? selector : selector.substring(0, delimiter);
							String descriptor = delimiter < 0 ? null : selector.substring(delimiter);
							List<MethodNode> matched = actual.methods.stream().filter(m -> m.name.equals(methodName) && (descriptor == null || m.desc.equals(descriptor))).toList();
							check(!matched.isEmpty(), name + " method " + selector + " -> " + target);
							checks++;
							Object points = value(annotation, "at");
							List<?> ats = points instanceof List<?> many ? many : points == null ? List.of() : List.of(points);
							for (Object point : ats) {
								AnnotationNode at = (AnnotationNode) point;
								String kind = String.valueOf(value(at, "value"));
								String member = String.valueOf(value(at, "target"));
								if (!kind.equals("INVOKE") && !kind.equals("FIELD")) continue;
								int count = 0;
								for (MethodNode method : matched) for (AbstractInsnNode instruction : method.instructions) {
									String found = instruction instanceof MethodInsnNode call ? "L" + call.owner + ";" + call.name + call.desc
										: instruction instanceof FieldInsnNode field ? "L" + field.owner + ";" + field.name + ":" + field.desc : "";
									if (found.equals(member)) count++;
								}
								int ordinal = value(at, "ordinal") instanceof Integer number ? number : 0;
								check(count > ordinal, name + " " + selector + " " + kind + " " + member + " ordinal " + ordinal + ", matches=" + count);
								checks++;
								}
							}
						}
					}
				}
			}
		checks += verifyHandSchedule();
		System.out.println("PASS: " + checks + " native world mixin bytecode contracts");
	}
	private static int verifyHandSchedule() throws IOException {
		ClassNode mixin = read("net/irisshaders/iris/mixin/vulkan/VKOnly_MixinGameRenderer_WorldPasses");
		MethodNode early = mixin.methods.stream().filter(m -> m.name.equals("iris$renderSolidHandBeforeTranslucents")).findFirst().orElseThrow();
		List<String> earlyCalls = calls(early);
		check(earlyCalls.contains("renderItemInHand"), "Early hand retains native eligibility, pose submission and resource scopes");
		check(earlyCalls.contains("setupPerspective") && !earlyCalls.contains("scale"), "Native hand projection is not precompressed");
		check(earlyCalls.indexOf("identity") < earlyCalls.indexOf("renderItemInHand"), "Early hand does not multiply world model-view twice");
		check(early.tryCatchBlocks.stream().anyMatch(block -> block.type == null), "Early hand restores renderer state on failure");
		MethodNode prepare = mixin.methods.stream().filter(m -> m.name.equals("iris$prepareHandFeatures")).findFirst().orElseThrow();
		check(java.util.Arrays.stream(prepare.instructions.toArray()).anyMatch(instruction -> instruction instanceof FieldInsnNode field && field.name.equals("iris$handFeatures")), "Early hand has its own dispatcher while the world frame is alive");
		MethodNode split = mixin.methods.stream().filter(m -> m.name.equals("iris$splitHandFeatures")).findFirst().orElseThrow();
		check(calls(split).containsAll(List.of("executeSolid", "executeTranslucent", "executeTranslucentAfterTerrain", "executeSeeThrough", "executeAlwaysOnTop")), "Native hand feature phases are split, not dropped");
		MethodNode close = mixin.methods.stream().filter(m -> m.name.equals("iris$closeHandFeatures")).findFirst().orElseThrow();
		check(calls(close).stream().filter("close"::equals).count() >= 2 && close.tryCatchBlocks.stream().anyMatch(block -> block.type == null), "Owned dispatcher and buffers are closed even if dispatcher teardown fails");
		MethodNode nativeHand = mixin.methods.stream().filter(m -> m.name.equals("iris$renderNativeHand")).findFirst().orElseThrow();
		check(calls(nativeHand).containsAll(List.of("popMatrix", "clearHandMatrices")), "Native hand restores model-view and matrix scope after failure");
		return 8;
	}
	private static List<String> calls(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
			.map(MethodInsnNode.class::cast).map(call -> call.name).toList();
	}
	private static ClassNode read(String name) throws IOException {
		try (var stream = IrisVulkanMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
			if (stream == null) throw new AssertionError("Missing class " + name);
			ClassNode node = new ClassNode();
			new ClassReader(stream).accept(node, 0);
			return node;
		}
	}
	private static List<AnnotationNode> annotations(List<AnnotationNode> first, List<AnnotationNode> second) {
		List<AnnotationNode> result = new ArrayList<>();
		if (first != null) result.addAll(first);
		if (second != null) result.addAll(second);
		return result;
	}
	private static Object value(AnnotationNode node, String key) {
		if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) if (node.values.get(i).equals(key)) return node.values.get(i + 1);
		return null;
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
