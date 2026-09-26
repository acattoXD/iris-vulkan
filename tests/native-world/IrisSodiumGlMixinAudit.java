package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Audits all registered OpenGL-selected Sodium mixin targets without loading a game. */
public final class IrisSodiumGlMixinAudit {
    private static final List<String> failures = new ArrayList<>(), optionalMisses = new ArrayList<>(), checks = new ArrayList<>();
    private static final Map<String, ClassNode> cache = new HashMap<>();
    private static final Map<String, String> mixinSources = new TreeMap<>();
    private static JarFile sodium;
    private static int mixinCount, targetCount;

    public static void main(String[] args) throws Exception {
        Path resources = Path.of(args[0]);
        sodium = new JarFile(args[1]);
        Set<String> registeredConfigs = new HashSet<>();
        var mod = JsonParser.parseString(Files.readString(resources.resolve("fabric.mod.json"))).getAsJsonObject();
        mod.getAsJsonArray("mixins").forEach(entry -> registeredConfigs.add(entry.isJsonPrimitive()
            ? entry.getAsString() : entry.getAsJsonObject().get("config").getAsString()));
        try (var files = Files.list(resources)) {
            for (Path file : files.filter(p -> registeredConfigs.contains(p.getFileName().toString())).toList()) {
                var config = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!config.has("package")) continue;
                int defaultRequire = config.has("injectors") && config.getAsJsonObject("injectors").has("defaultRequire")
                    ? config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() : 0;
                String packageName = config.get("package").getAsString();
                Set<String> names = new LinkedHashSet<>();
                for (String group : List.of("mixins", "client")) if (config.has(group))
                    config.getAsJsonArray(group).forEach(v -> names.add(packageName + "." + v.getAsString()));
                for (String name : names) {
                    if (name.contains("VKOnly") || name.contains(".vulkan.")) continue;
                    ClassNode mixin = read(name.replace('.', '/'));
                    if (mixin == null) { failures.add("Missing registered mixin class " + name); continue; }
                    AnnotationNode annotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, "/Mixin;");
                    if (annotation == null) continue;
                    List<String> targets = new ArrayList<>();
                    if (value(annotation, "value") instanceof List<?> types) for (Object type : types) targets.add(((Type) type).getInternalName());
                    if (value(annotation, "targets") instanceof List<?> types) for (Object type : types) targets.add(type.toString().replace('.', '/'));
                    targets.removeIf(t -> !t.startsWith("net/caffeinemc/mods/sodium/"));
                    if (targets.isEmpty()) continue;
                    mixinCount++;
                    var location = IrisSodiumGlMixinAudit.class.getClassLoader().getResource(name.replace('.', '/') + ".class");
                    mixinSources.put(name, String.valueOf(location));
                    for (String target : targets) {
                        ClassNode actual = read(target);
                        if (actual == null && annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, "/Pseudo;") != null) {
                            optionalMisses.add(name + " optional target absent: " + target); continue;
                        }
                        require(actual != null, name + " target " + target);
                        if (actual == null) continue;
                        targetCount++;
                        inspect(mixin, actual, defaultRequire);
                    }
                }
            }
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("passed", failures.isEmpty()); report.put("sodiumJar", Path.of(args[1]).toAbsolutePath().toString());
        report.put("configurationDirectory", resources.toAbsolutePath().toString()); report.put("mixinCount", mixinCount);
        report.put("targetCount", targetCount); report.put("checkCount", checks.size()); report.put("failures", failures);
        report.put("optionalMisses", optionalMisses); report.put("mixinClassSources", mixinSources); report.put("checks", checks);
        report.put("scope", "Current registered OpenGL-selected Sodium mixins: actual class/member descriptors, shadow/accessor/invoker/overwrite targets, injection selectors and INVOKE/FIELD ordinals, grouped alternatives. Does not simulate all Mixin local-capture or runtime rendering semantics.");
        Files.writeString(Path.of(args[2]), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("SODIUM_GL_AUDIT: " + mixinCount + " mixins, " + targetCount + " targets, " + checks.size() + " checks, " + failures.size() + " required failures, " + optionalMisses.size() + " optional misses");
        failures.forEach(System.out::println);
        sodium.close();
        if (!failures.isEmpty()) System.exit(1);
    }

    private static void inspect(ClassNode mixin, ClassNode actual, int defaultRequire) throws Exception {
        String prefix = mixin.name + " -> " + actual.name;
        for (FieldNode field : mixin.fields)
            if (annotation(field.visibleAnnotations, field.invisibleAnnotations, "/Shadow;") != null)
                require(findField(actual.name, field.name, field.desc, new HashSet<>()) != null, prefix + " @Shadow field " + field.name + ":" + field.desc);
        Map<String, int[]> groups = new LinkedHashMap<>();
        for (MethodNode handler : mixin.methods) {
            var annotations = annotations(handler.visibleAnnotations, handler.invisibleAnnotations);
            AnnotationNode group = annotations.stream().filter(a -> a.desc.endsWith("/Group;")).findFirst().orElse(null);
            String groupName = group == null ? null : String.valueOf(value(group, "name"));
            if (group != null) groups.putIfAbsent(groupName, new int[]{integer(group, "min", 1), integer(group, "max", Integer.MAX_VALUE), 0});
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.endsWith("/Shadow;") || annotation.desc.endsWith("/Overwrite;"))
                    require(findMethod(actual.name, handler.name, handler.desc, new HashSet<>()) != null, prefix + " shadow/overwrite " + handler.name + handler.desc);
                if (annotation.desc.endsWith("/Accessor;")) {
                    String name = value(annotation, "value") == null ? infer(handler.name, "get", "set", "is") : value(annotation, "value").toString();
                    Type result = Type.getReturnType(handler.desc);
                    String descriptor = result.getSort() == Type.VOID ? Type.getArgumentTypes(handler.desc)[0].getDescriptor() : result.getDescriptor();
                    require(findField(actual.name, name, descriptor, new HashSet<>()) != null, prefix + " accessor " + name + ":" + descriptor);
                }
                if (annotation.desc.endsWith("/Invoker;")) {
                    String name = value(annotation, "value") == null ? infer(handler.name, "invoke", "call") : value(annotation, "value").toString();
                    require(findMethod(actual.name, name, handler.desc, new HashSet<>()) != null, prefix + " invoker " + name + handler.desc);
                }
                if (!(value(annotation, "method") instanceof List<?> selectors)) continue;
                int required = integer(annotation, "require", defaultRequire);
                for (Object selectorObject : selectors) {
                    String selector = selectorObject.toString(); int split = selector.indexOf('(');
                    String methodName = split < 0 ? selector : selector.substring(0, split), descriptor = split < 0 ? null : selector.substring(split);
                    List<MethodNode> matched = actual.methods.stream().filter(m -> m.name.equals(methodName) && (descriptor == null || m.desc.equals(descriptor))).toList();
                    if (matched.isEmpty()) { miss(required, prefix + " selector " + selector); continue; }
                    checks.add(prefix + " selector " + selector);
                    Object points = value(annotation, "at");
                    List<?> ats = points instanceof List<?> list ? list : points == null ? List.of() : List.of(points);
                    int handlerMatches = ats.isEmpty() ? matched.size() : 0;
                    for (Object point : ats) {
                        AnnotationNode at = (AnnotationNode) point;
                        String kind = String.valueOf(value(at, "value")), member = String.valueOf(value(at, "target"));
                        int count = matched.size();
                        if (kind.equals("INVOKE") || kind.equals("FIELD")) {
                            count = 0;
                            for (MethodNode target : matched) for (var instruction : target.instructions) {
                                String found = instruction instanceof MethodInsnNode c ? "L" + c.owner + ";" + c.name + c.desc
                                    : instruction instanceof FieldInsnNode f ? "L" + f.owner + ";" + f.name + ":" + f.desc : "";
                                if (member.equals(found)) count++;
                            }
                            int ordinal = integer(at, "ordinal", -1);
                            String label = prefix + " " + selector + " " + kind + " " + member + " ordinal=" + ordinal + " matches=" + count;
                            if (count <= Math.max(ordinal, 0)) miss(required, label); else checks.add(label);
                            if (ordinal >= 0) count = count > ordinal ? 1 : 0;
                        }
                        handlerMatches += count;
                    }
                    if (groupName != null) groups.get(groupName)[2] += handlerMatches;
                }
            }
        }
        for (var entry : groups.entrySet()) {
            int[] values = entry.getValue();
            require(values[2] >= values[0] && values[2] <= values[1], prefix + " group " + entry.getKey() + " injected=" + values[2] + " expected=" + values[0] + ".." + values[1]);
        }
    }
    private static String infer(String method, String... prefixes) {
        for (String prefix : prefixes) if (method.startsWith(prefix) && method.length() > prefix.length()) {
            String suffix = method.substring(prefix.length()); return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
        }
        return method;
    }
    private static void miss(int required, String label) { if (required > 0) failures.add(label); else optionalMisses.add(label); }
    private static void require(boolean okay, String label) { checks.add(label); if (!okay) failures.add(label); }
    private static ClassNode read(String name) throws Exception {
        if (cache.containsKey(name)) return cache.get(name);
        var entry = sodium.getJarEntry(name + ".class");
        try (var stream = entry != null ? sodium.getInputStream(entry) : name.startsWith("net/caffeinemc/mods/sodium/") ? null
                : IrisSodiumGlMixinAudit.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (stream == null) { cache.put(name, null); return null; }
            ClassNode node = new ClassNode(); new ClassReader(stream).accept(node, 0); cache.put(name, node); return node;
        }
    }
    private static FieldNode findField(String owner, String name, String descriptor, Set<String> seen) throws Exception {
        if (owner == null || !seen.add(owner)) return null; ClassNode node = read(owner); if (node == null) return null;
        for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(descriptor)) return field;
        FieldNode found = findField(node.superName, name, descriptor, seen); if (found != null) return found;
        for (String type : node.interfaces) { found = findField(type, name, descriptor, seen); if (found != null) return found; }
        return null;
    }
    private static MethodNode findMethod(String owner, String name, String descriptor, Set<String> seen) throws Exception {
        if (owner == null || !seen.add(owner)) return null; ClassNode node = read(owner); if (node == null) return null;
        for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        MethodNode found = findMethod(node.superName, name, descriptor, seen); if (found != null) return found;
        for (String type : node.interfaces) { found = findMethod(type, name, descriptor, seen); if (found != null) return found; }
        return null;
    }
    private static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) { var result = new ArrayList<AnnotationNode>(); if (a != null) result.addAll(a); if (b != null) result.addAll(b); return result; }
    private static AnnotationNode annotation(List<AnnotationNode> a, List<AnnotationNode> b, String suffix) { return annotations(a, b).stream().filter(v -> v.desc.endsWith(suffix)).findFirst().orElse(null); }
    private static int integer(AnnotationNode annotation, String key, int fallback) { return value(annotation, key) instanceof Integer number ? number : fallback; }
    private static Object value(AnnotationNode node, String key) { if (node.values != null) for (int i = 0; i < node.values.size(); i += 2) if (node.values.get(i).equals(key)) return node.values.get(i + 1); return null; }
}
