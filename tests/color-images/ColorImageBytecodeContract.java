package net.irisshaders.iris.vulkan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.objectweb.asm.Opcodes.*;

/** Reads actual compiled classes without loading Vulkan, creating a device, or starting the game. */
public final class ColorImageBytecodeContract {
    private static final String IRIS = "net/irisshaders/iris/vulkan/";
    private static final String ENGINE = "com/mojang/renderpearl/backend/vulkan/";
    private static final String VK = "org/lwjgl/vulkan/";
    private static final String TEXTURE_VIEW = "Lcom/mojang/renderpearl/api/textures/GpuTextureView;";
    private static final String COMMAND = "(Lorg/lwjgl/vulkan/VkCommandBuffer;)V";
    private static final String USAGE = "(ILcom/mojang/renderpearl/api/GpuFormat;)I";
    private static final String WRITES = "(JLcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/lwjgl/system/MemoryStack;)Lorg/lwjgl/vulkan/VkWriteDescriptorSet$Buffer;";
    private static final String MIXIN = "net/irisshaders/iris/mixin/vulkan/VKOnly_MixinVulkanConst_ColorImages";
    private static int checks;

    public static void main(String[] args) throws IOException {
        require(args.length <= 1, "Usage: ColorImageBytecodeContract [path/to/mixins.iris.json]");
        allocationMixin(args);
        engineAllocation();
        engineLayouts();
        computeDispatch();
        descriptorPrecedenceAndOwnership();
        System.out.println("PASS: " + checks + " compiled color-image allocation, layout, dispatch and ownership invariants");
    }

    private static void allocationMixin(String[] args) throws IOException {
        JsonObject config;
        if (args.length == 1) {
            try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
                config = JsonParser.parseReader(reader).getAsJsonObject();
            }
        } else {
            try (var input = ColorImageBytecodeContract.class.getClassLoader().getResourceAsStream("mixins.iris.json")) {
                require(input != null, "Mixin config must be on classpath or supplied as an argument");
                config = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        require(config.get("required").getAsBoolean(), "Iris mixin configuration is required");
        require(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() >= 1,
            "Missing allocation injection must fail instead of silently skipping storage usage");
        String prefix = config.get("package").getAsString().replace('.', '/') + "/";
        long registrations = config.getAsJsonArray("client").asList().stream()
            .filter(value -> (prefix + value.getAsString().replace('.', '/')).equals(MIXIN)).count();
        require(registrations == 1, "Exact allocation mixin is registered once in the client mixin list");

        var mixin = read(MIXIN);
        var annotation = annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        require(value(annotation, "value").equals(List.of(Type.getObjectType(ENGINE + "VulkanConst"))),
            "Allocation mixin targets the actual RenderPearl VulkanConst class");
        require(Boolean.FALSE.equals(value(annotation, "remap")), "Allocation mixin disables remapping for engine bytecode");
        var engine = read(ENGINE + "VulkanConst");
        var conversion = method(engine, "textureUsageToVk", USAGE);
        require((conversion.access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC),
            "Real textureUsageToVk has the required public static (int, GpuFormat) -> int signature");
        var handler = method(mixin, "iris$colorImageUsage", "(I)I");
        require((handler.access & ACC_STATIC) != 0, "Return-value handler matches a static target");
        var inject = annotation(handler.visibleAnnotations, handler.invisibleAnnotations,
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
        var selectors = (List<?>) value(inject, "method");
        require(selectors.size() == 1 && (selectors.getFirst().equals(conversion.name)
            || selectors.getFirst().equals(conversion.name + conversion.desc)), "ModifyReturnValue selects the real conversion method");
        require(engine.methods.stream().filter(candidate -> candidate.name.equals(conversion.name)).count() == 1
            || selectors.getFirst().equals(conversion.name + conversion.desc), "Name-only injection selector cannot match an unexpected overload");
        var locations = (List<?>) value(inject, "at");
        require(locations.size() == 1 && locations.getFirst() instanceof AnnotationNode at
            && at.desc.equals("Lorg/spongepowered/asm/mixin/injection/At;") && value(at, "value").equals("RETURN"),
            "Storage usage modification is injected at conversion RETURN");
        var code = realInstructions(handler);
        require(code.size() == 3 && local(code.get(0), ILOAD) == 0
            && matches(code.get(1), IRIS + "IrisVulkanColorImages", "allocationUsage", "(I)I")
            && code.get(1).getOpcode() == INVOKESTATIC && code.get(2).getOpcode() == IRETURN,
            "Handler returns allocationUsage(original) without replacing or dropping the original bits");
    }

    private static void engineAllocation() throws IOException {
        var texture = read(ENGINE + "VulkanGpuTexture");
        var constructor = method(texture, "<init>", "(L" + ENGINE
            + "VulkanDevice;ILjava/lang/String;Lcom/mojang/renderpearl/api/GpuFormat;IIII)V");
        var conversion = single(constructor, ENGINE + "VulkanConst", "textureUsageToVk", USAGE);
        require(matches(next(conversion), VK + "VkImageCreateInfo", "usage", "(I)L" + VK + "VkImageCreateInfo;"),
            "Real allocation feeds the hooked conversion directly into VkImageCreateInfo.usage");
        require(local(previous(conversion), ALOAD) == 4 && local(previous(previous(conversion)), ILOAD) == 2,
            "Allocation converts the constructor's original usage and actual format arguments");
        constantArgument(single(constructor, VK + "VkImageCreateInfo", "initialLayout", null), 0,
            "Engine image allocation starts UNDEFINED");
        var oldLayout = single(constructor, VK + "VkImageMemoryBarrier$Buffer", "oldLayout", null);
        var newLayout = single(constructor, VK + "VkImageMemoryBarrier$Buffer", "newLayout", null);
        constantArgument(oldLayout, 0, "Initial transition starts at UNDEFINED");
        constantArgument(newLayout, 1, "Initial transition ends at GENERAL");
        require(local(previous(previous(oldLayout)), ALOAD) == local(previous(previous(newLayout)), ALOAD),
            "UNDEFINED and GENERAL belong to the same initial image barrier");
        var init = single(constructor, ENGINE + "VulkanCommandEncoder", "objectInitCommandBuffer", "()L" + VK + "VkCommandBuffer;");
        var barrier = single(constructor, VK + "VK12", "vkCmdPipelineBarrier", null);
        require(ordered(constructor, conversion, oldLayout, newLayout, init, barrier),
            "GENERAL transition is scheduled through the engine object-initialization command buffer after allocation");
        var barrierArguments = following(init, 7);
        require(integer(barrierArguments.get(0)) == 1 && integer(barrierArguments.get(1)) == 8192
            && integer(barrierArguments.get(2)) == 0 && barrierArguments.get(3).getOpcode() == ACONST_NULL
            && barrierArguments.get(4).getOpcode() == ACONST_NULL
            && local(barrierArguments.get(5), ALOAD) == local(previous(previous(oldLayout)), ALOAD)
            && barrierArguments.get(6) == barrier,
            "Object-init buffer records the same image barrier from TOP_OF_PIPE to BOTTOM_OF_PIPE");
    }

    private static void engineLayouts() throws IOException {
        var sampled = method(read(ENGINE + "VulkanRenderPass"), "pushDescriptors", "()V");
        constantArgument(single(sampled, VK + "VkDescriptorImageInfo$Buffer", "imageLayout", null), 1,
            "Actual graphics sampled-image descriptor uses GENERAL");
        var render = method(read(ENGINE + "VulkanCommandEncoder"), "createRenderPass",
            "(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/backend/api/RenderPassBackend;");
        var colorLayouts = calls(render, VK + "VkRenderingAttachmentInfo$Buffer", "imageLayout", null);
        require(colorLayouts.size() == 2, "Engine has explicit live and absent color-attachment layout paths");
        constantArgument(colorLayouts.get(0), 1, "Live color attachment uses GENERAL");
        constantArgument(colorLayouts.get(1), 0, "Absent color attachment uses UNDEFINED");
        var colorViews = calls(render, VK + "VkRenderingAttachmentInfo$Buffer", "imageView", null);
        require(colorViews.size() == 2 && previous(colorViews.get(0)) instanceof MethodInsnNode call
            && matches(call, ENGINE + "VulkanGpuTextureView", "vkImageView", "()J")
            && previous(colorViews.get(1)).getOpcode() == LCONST_0
            && ordered(render, colorViews.get(0), colorLayouts.get(0), colorViews.get(1), colorLayouts.get(1)),
            "Only the absent/null image view gets UNDEFINED; real color image views use GENERAL");
        constantArgument(single(render, VK + "VkRenderingAttachmentInfo", "imageLayout", null), 1,
            "Live depth attachment also uses GENERAL");
    }

    private static void computeDispatch() throws IOException {
        var program = read(IRIS + "IrisVulkanComputeExecutor$Program");
        var dispatch = method(program, "dispatch", "(II)V");
        var barriers = calls(dispatch, IRIS + "IrisVulkanStorageResources", "barrier", COMMAND);
        require(barriers.size() == 2 && calls(dispatch, IRIS + "IrisVulkanStorageResources", "barrier", null).size() == 2,
            "Actual dispatch retains exactly two explicit-command-buffer storage barriers");
        var execute = single(dispatch, ENGINE + "VulkanCommandEncoder", "execute", COMMAND);
        var allocate = single(dispatch, ENGINE + "VulkanCommandEncoder", "allocateAndBeginTransientCommandBuffer", "()L" + VK + "VkCommandBuffer;");
        var descriptorWrites = single(dispatch, program.name, "descriptorWrites", WRITES);
        require(ordered(dispatch, descriptorWrites, allocate), "Every dispatch resolves current image descriptors before recording GPU work");
        var direct = single(dispatch, VK + "VK10", "vkCmdDispatch", "(L" + VK + "VkCommandBuffer;III)V");
        var indirect = single(dispatch, VK + "VK10", "vkCmdDispatchIndirect", "(L" + VK + "VkCommandBuffer;JJ)V");
        var end = single(dispatch, VK + "VK10", "vkEndCommandBuffer", "(L" + VK + "VkCommandBuffer;)I");
        require(ordered(dispatch, allocate, barriers.get(0), direct, indirect, barriers.get(1), end, execute),
            "Both dispatch paths sit between barriers, followed by command-buffer end and one ordered execute");
        int commandLocal = local(next(allocate), ASTORE);
        require(commandLocal >= 0 && local(previous(barriers.get(0)), ALOAD) == commandLocal
            && local(previous(barriers.get(1)), ALOAD) == commandLocal && local(previous(end), ALOAD) == commandLocal
            && local(previous(execute), ALOAD) == commandLocal, "Both barriers, end and execute use the same allocated command buffer");
        int encoderLocal = local(previous(allocate), ALOAD);
        require(encoderLocal >= 0 && local(previous(previous(execute)), ALOAD) == encoderLocal,
            "Transient command buffer is appended through the same engine encoder that allocated it");
        for (var command : List.of(direct, indirect)) {
            require(reachable(dispatch, dispatch.instructions.getFirst(), command, Set.of())
                && !reachable(dispatch, dispatch.instructions.getFirst(), command, Set.of(barriers.get(0))),
                command.name + " cannot bypass the pre-dispatch barrier");
            require(reachable(dispatch, command, execute, Set.of())
                && !reachable(dispatch, command, execute, Set.of(barriers.get(1))),
                command.name + " cannot be submitted without the post-dispatch barrier");
        }
        var storageBarrier = method(read(IRIS + "IrisVulkanStorageResources"), "barrier", COMMAND);
        require(calls(storageBarrier, IRIS + "IrisVulkanStorageResources", "active", null).isEmpty(),
            "Explicit command-buffer barrier does not depend on custom storage resources being active");
        single(storageBarrier, VK + "VK12", "vkCmdPipelineBarrier", null);
        constantArgument(single(storageBarrier, VK + "VkMemoryBarrier$Buffer", "srcAccessMask", null), 65536,
            "Storage barrier makes previous memory writes available");
        constantArgument(single(storageBarrier, VK + "VkMemoryBarrier$Buffer", "dstAccessMask", null), 98304,
            "Storage barrier makes writes visible to subsequent memory reads and writes");
    }

    private static void descriptorPrecedenceAndOwnership() throws IOException {
        var program = read(IRIS + "IrisVulkanComputeExecutor$Program");
        var writes = method(program, "descriptorWrites", WRITES);
        var custom = single(writes, IRIS + "IrisVulkanStorageResources", "image", "(Ljava/lang/String;)L" + IRIS + "IrisVulkanStorageResources$Image;");
        var alias = single(writes, IRIS + "IrisVulkanColorImages", "view", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + TEXTURE_VIEW);
        var customView = single(writes, IRIS + "IrisVulkanStorageResources$Image", "vkImageView", "()J");
        var nativeView = single(writes, ENGINE + "VulkanGpuTextureView", "vkImageView", "()J");
        int descriptorLocal = local(previous(previous(custom)), ALOAD);
        var argument = previous(alias);
        for (String accessor : List.of("imageFormat", "type", "name")) {
            require(descriptorLocal >= 0 && matches(argument, IRIS + "IrisVulkanComputeCompiler$Descriptor", accessor, "()Ljava/lang/String;")
                && local(previous(argument), ALOAD) == descriptorLocal, "Borrowed image resolves the current descriptor's " + accessor);
            argument = previous(previous(argument));
        }
        var branchInstructions = following(custom, 3);
        int customLocal = local(branchInstructions.get(0), ASTORE);
        require(customLocal >= 0 && local(branchInstructions.get(1), ALOAD) == customLocal
            && branchInstructions.get(2) instanceof JumpInsnNode branch && branch.getOpcode() == IFNULL,
            "Custom-image lookup result is tested for null before attempting the built-in alias");
        var branch = (JumpInsnNode) branchInstructions.get(2);
        int viewLocal = local(next(customView), LSTORE);
        require(viewLocal >= 0 && local(next(nativeView), LSTORE) == viewLocal,
            "Custom and borrowed image branches write the same descriptor image-view local");
        var mergedImageView = calls(writes, VK + "VkDescriptorImageInfo$Buffer", "imageView", null).getFirst();
        require(local(previous(mergedImageView), LLOAD) == viewLocal
            && ordered(writes, custom, customView, alias, nativeView, mergedImageView),
            "Storage descriptor uses the image view chosen by custom-first alias fallback");
        require(reachable(writes, branch.label, alias, Set.of(mergedImageView))
            && !reachable(writes, next(branch), alias, Set.of(mergedImageView))
            && reachable(writes, next(branch), customView, Set.of(mergedImageView))
            && !reachable(writes, branch.label, customView, Set.of(mergedImageView)),
            "Built-in alias is reached only on the custom-image-null branch, preserving custom precedence");
        var layouts = calls(writes, VK + "VkDescriptorImageInfo$Buffer", "imageLayout", null);
        require(layouts.size() == 2, "Compute emits both storage and sampled image layouts");
        for (var layout : layouts) constantArgument(layout, 1, "Compute storage/sampled descriptor uses GENERAL");
        var aliases = read(IRIS + "IrisVulkanColorImages");
        var resolve = method(aliases, "view", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)" + TEXTURE_VIEW);
        single(resolve, IRIS + "IrisVulkanGbufferTargets", "colorImageView", "(I)" + TEXTURE_VIEW);
        require(calls(resolve, IRIS + "IrisVulkanGbufferTargets", "nextView", null).isEmpty(),
            "Writable alias resolves the current storage-capable gbuffer view rather than the next side");
        for (var owner : List.of(program, aliases)) {
            List<String> ownershipViolations = new ArrayList<>();
            for (var method : owner.methods) for (var instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.owner.equals(IRIS + "IrisVulkanStorageResources")
                    && field.name.equals("images")) ownershipViolations.add(method.name + " accesses the custom-image ownership map");
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if ((Set.of("close", "clear", "destroy").contains(call.name) && (call.owner.contains("GpuTexture")
                    || call.owner.equals(IRIS + "IrisVulkanGbufferTargets") || call.owner.startsWith(IRIS + "IrisVulkanTargetModel")))
                    || (call.owner.startsWith(VK) && Set.of("vkCreateImage", "vkCreateImageView", "vkDestroyImage", "vkDestroyImageView", "vkCmdClearColorImage").contains(call.name))
                    || (call.owner.equals("org/lwjgl/util/vma/Vma") && Set.of("vmaCreateImage", "vmaDestroyImage").contains(call.name))
                    || (call.owner.equals(IRIS + "IrisVulkanStorageResources$Image") && call.name.equals("<init>")))
                    ownershipViolations.add(method.name + " calls " + call.owner + "." + call.name);
            }
            require(ownershipViolations.isEmpty(), owner.name + " must neither own nor independently clear borrowed gbuffer images: " + ownershipViolations);
            require(owner.fields.stream().noneMatch(field -> field.desc.contains("GpuTexture")),
                owner.name + " must not cache borrowed texture or view objects between dispatches");
        }
        require(aliases.fields.stream().noneMatch(field -> field.desc.contains("GpuTexture") || field.desc.equals("Ljava/util/Map;")),
            "Alias helper retains neither borrowed texture handles nor an independently owned image map");
        require(program.methods.stream().flatMap(method -> calls(method, IRIS + "IrisVulkanStorageResources", "image", null).stream()).count() == 1,
            "Custom storage lookup remains in descriptor construction, without registering borrowed targets");
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = ColorImageBytecodeContract.class.getClassLoader().getResourceAsStream(name + ".class")) {
            require(input != null, "Missing compiled class " + name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static MethodNode method(ClassNode owner, String name, String desc) {
        return owner.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(desc)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing method " + owner.name + "." + name + desc));
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (var list : java.util.Arrays.asList(visible, invisible)) if (list != null)
            for (var annotation : list) if (annotation.desc.equals(desc)) return annotation;
        throw new AssertionError("Missing annotation " + desc);
    }

    private static Object value(AnnotationNode annotation, String name) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(name)) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation value " + annotation.desc + "." + name);
    }

    private static boolean matches(AbstractInsnNode instruction, String owner, String name, String desc) {
        return instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)
            && (desc == null || call.desc.equals(desc));
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (matches(instruction, owner, name, desc)) result.add((MethodInsnNode) instruction);
        return result;
    }

    private static MethodInsnNode single(MethodNode method, String owner, String name, String desc) {
        var calls = calls(method, owner, name, desc);
        require(calls.size() == 1, method.name + " must call " + owner + "." + name + " exactly once; got " + calls.size());
        return calls.getFirst();
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static List<AbstractInsnNode> following(AbstractInsnNode instruction, int count) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (int i = 0; i < count; i++) { instruction = next(instruction); result.add(instruction); }
        return result;
    }

    private static int local(AbstractInsnNode instruction, int opcode) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode ? variable.var : -1;
    }

    private static int integer(AbstractInsnNode instruction) {
        if (instruction == null) return Integer.MIN_VALUE;
        int opcode = instruction.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) return opcode - ICONST_0;
        if (instruction instanceof IntInsnNode integer && (opcode == BIPUSH || opcode == SIPUSH)) return integer.operand;
        if (instruction instanceof LdcInsnNode literal && literal.cst instanceof Integer integer) return integer;
        return Integer.MIN_VALUE;
    }

    private static void constantArgument(MethodInsnNode call, int expected, String message) {
        require(Type.getArgumentTypes(call.desc).length == 1 && Type.getArgumentTypes(call.desc)[0].equals(Type.INT_TYPE)
            && integer(previous(call)) == expected, message);
    }

    private static boolean ordered(MethodNode method, AbstractInsnNode... instructions) {
        int prior = -1;
        for (var instruction : instructions) {
            int current = method.instructions.indexOf(instruction);
            if (current <= prior) return false;
            prior = current;
        }
        return true;
    }

    /** Normal control flow only: exceptions abort recording and need not reach submission. */
    private static boolean reachable(MethodNode method, AbstractInsnNode start, AbstractInsnNode target, Set<AbstractInsnNode> excluded) {
        Set<AbstractInsnNode> visited = new HashSet<>();
        ArrayDeque<AbstractInsnNode> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (excluded.contains(node) || !visited.add(node)) continue;
            if (node == target) return true;
            int opcode = node.getOpcode();
            if (node instanceof JumpInsnNode jump) {
                pending.add(jump.label);
                if (opcode == GOTO) continue;
            } else if (node instanceof TableSwitchInsnNode table) {
                pending.add(table.dflt); pending.addAll(table.labels); continue;
            } else if (node instanceof LookupSwitchInsnNode lookup) {
                pending.add(lookup.dflt); pending.addAll(lookup.labels); continue;
            } else if (opcode == ATHROW || (opcode >= IRETURN && opcode <= RETURN)) continue;
            if (node.getNext() != null) pending.add(node.getNext());
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
