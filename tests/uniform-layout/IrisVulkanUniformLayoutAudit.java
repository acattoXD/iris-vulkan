package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import org.lwjgl.util.spvc.Spv;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline audit using production CPU layout and Mojang's actual compiler plus SPIRV-Cross. */
public final class IrisVulkanUniformLayoutAudit {
    private static final Pattern BLOCK = Pattern.compile("(?s)layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+IrisUniforms\\s*\\{(.*?)}\\s*;");
    private static final Pattern FIELD = Pattern.compile("(?m)^\\h*(\\w+)\\h+(\\w+)\\h*;");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: output.json shaderfile ...");
        List<Object> shaders = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int members = 0;
        try (GlslCompiler compiler = new GlslCompiler()) {
            for (int i = 1; i < args.length; i++) {
                Path path = Path.of(args[i]).toAbsolutePath().normalize();
                Map<String, Object> shader = inspect(compiler, path, failures);
                shaders.add(shader);
                members += ((Number) shader.get("memberCount")).intValue();
            }
        }
        List<String> packingChecks = testPacking();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Production IrisVulkanUniformSnapshot.layouts vs SPIRV-Cross member offsets/sizes from Mojang GlslCompiler output. Includes byte-writer packing tests; does not measure live uniform values or target history.");
        report.put("shaders", shaders);
        report.put("shaderCount", shaders.size());
        report.put("memberCount", members);
        report.put("packingChecks", packingChecks);
        report.put("failures", failures);
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("IRIS_VULKAN_UNIFORM_LAYOUT_" + (failures.isEmpty() ? "PASS" : "FAIL") + ": "
                + shaders.size() + " stages, " + members + " members; " + output);
        if (!failures.isEmpty()) throw new AssertionError(String.join("\n", failures));
    }

    private static Map<String, Object> inspect(GlslCompiler compiler, Path path, List<String> failures) throws Exception {
        String source = Files.readString(path);
        var block = BLOCK.matcher(source);
        if (!block.find()) throw new IllegalArgumentException("No IrisUniforms in " + path);
        List<IrisVulkanUniformSnapshot.Field> fields = new ArrayList<>();
        var field = FIELD.matcher(block.group(1));
        while (field.find()) fields.add(new IrisVulkanUniformSnapshot.Field(field.group(2), field.group(1)));
        List<CpuLayout> cpu = productionLayouts(fields);
        String lower = path.getFileName().toString().toLowerCase();
        ShaderType shaderType = lower.endsWith(".fsh") || lower.contains(".frag") ? ShaderType.FRAGMENT : ShaderType.VERTEX;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path.toString());
        result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        result.put("stage", shaderType.name());
        try (var module = compiler.createIntermediary(path.getFileName().toString(), source, shaderType);
             MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            ok(Spvc.spvc_context_create(pointer));
            long context = pointer.get(0);
            try {
                IntBuffer words = module.spirv().duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
                ok(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer));
                long ir = pointer.get(0);
                ok(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, ir,
                        Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer));
                long reflection = pointer.get(0);
                ok(Spvc.spvc_compiler_create_shader_resources(reflection, pointer));
                long resources = pointer.get(0);
                PointerBuffer count = stack.mallocPointer(1);
                ok(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, pointer, count));
                var reflected = SpvcReflectedResource.create(pointer.get(0), (int) count.get(0));
                boolean found = false;
                for (int i = 0; i < reflected.capacity(); i++) {
                    var resource = reflected.get(i);
                    if (!resource.nameString().equals("IrisUniforms")) continue;
                    found = true;
                    long type = Spvc.spvc_compiler_get_type_handle(reflection, resource.base_type_id());
                    int reflectedMembers = Spvc.spvc_type_get_num_member_types(type);
                    ok(Spvc.spvc_compiler_get_declared_struct_size(reflection, type, pointer));
                    long reflectedSize = pointer.get(0);
                    int cpuSize = cpu.isEmpty() ? 16 : align16(cpu.getLast().offset() + cpu.getLast().size());
                    result.put("memberCount", reflectedMembers);
                    result.put("cpuAllocatedBytes", cpuSize);
                    result.put("spirvDeclaredBytes", reflectedSize);
                    result.put("spirvRoundedBlockBytes", align16((int) reflectedSize));
                    if (reflectedMembers != cpu.size()) failures.add(path + ": member count " + cpu.size() + " vs " + reflectedMembers);
                    if (cpuSize != align16((int) reflectedSize)) failures.add(path + ": block size " + cpuSize + " vs " + align16((int) reflectedSize));
                    List<Object> memberRows = new ArrayList<>();
                    IntBuffer integer = stack.mallocInt(1);
                    for (int member = 0; member < reflectedMembers; member++) {
                        String name = Spvc.spvc_compiler_get_member_name(reflection, resource.base_type_id(), member);
                        ok(Spvc.spvc_compiler_type_struct_member_offset(reflection, type, member, integer));
                        int reflectedOffset = integer.get(0);
                        ok(Spvc.spvc_compiler_get_declared_struct_member_size(reflection, type, member, pointer));
                        long reflectedMemberSize = pointer.get(0);
                        CpuLayout expected = member < cpu.size() ? cpu.get(member) : null;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", name);
                        row.put("type", expected == null ? "missing CPU field" : expected.field().type());
                        row.put("cpuOffset", expected == null ? -1 : expected.offset());
                        row.put("spirvOffset", reflectedOffset);
                        row.put("cpuSize", expected == null ? -1 : expected.size());
                        row.put("spirvSize", reflectedMemberSize);
                        if (expected == null || !expected.field().name().equals(name) || expected.offset() != reflectedOffset || expected.size() != reflectedMemberSize) {
                            failures.add(path.getFileName() + ": " + row);
                        }
                        long memberType = Spvc.spvc_compiler_get_type_handle(reflection, Spvc.spvc_type_get_member_type(type, member));
                        if (Spvc.spvc_type_get_columns(memberType) > 1) {
                            ok(Spvc.spvc_compiler_type_struct_member_matrix_stride(reflection, type, member, integer));
                            row.put("spirvMatrixStride", integer.get(0));
                            if (integer.get(0) != 16) failures.add(path + ": unexpected matrix stride for " + name + ": " + integer.get(0));
                            boolean rowMajor = Spvc.spvc_compiler_has_member_decoration(reflection, resource.base_type_id(), member, Spv.SpvDecorationRowMajor);
                            boolean columnMajor = Spvc.spvc_compiler_has_member_decoration(reflection, resource.base_type_id(), member, Spv.SpvDecorationColMajor);
                            row.put("spirvRowMajor", rowMajor);
                            row.put("spirvColumnMajor", columnMajor);
                            if (rowMajor || !columnMajor) failures.add(path + ": non-column-major matrix " + name);
                        }
                        memberRows.add(row);
                    }
                    result.put("members", memberRows);
                }
                if (!found) throw new AssertionError("SPIRV-Cross found no IrisUniforms in " + path);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
        return result;
    }

    private static List<CpuLayout> productionLayouts(List<IrisVulkanUniformSnapshot.Field> fields) throws Exception {
        Method layouts = IrisVulkanUniformSnapshot.class.getDeclaredMethod("layouts", Collection.class);
        layouts.setAccessible(true);
        List<?> values = (List<?>) layouts.invoke(null, fields);
        List<CpuLayout> result = new ArrayList<>();
        for (Object value : values) {
            Method field = value.getClass().getDeclaredMethod("field"); field.setAccessible(true);
            Method offset = value.getClass().getDeclaredMethod("offset"); offset.setAccessible(true);
            Method size = value.getClass().getDeclaredMethod("size"); size.setAccessible(true);
            result.add(new CpuLayout((IrisVulkanUniformSnapshot.Field) field.invoke(value),
                    (Integer) offset.invoke(value), (Integer) size.invoke(value)));
        }
        return result;
    }

    private static List<String> testPacking() throws Exception {
        List<String> checks = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        Method putVec3 = IrisVulkanUniformSnapshot.class.getDeclaredMethod("put", ByteBuffer.class, Vector3f.class);
        putVec3.setAccessible(true);
        buffer.position(16);
        putVec3.invoke(null, buffer, new Vector3f(11, 22, 33));
        buffer.putFloat(28, 44); // Exactly the subsequent scalar write in Snapshot.write.
        require(buffer.getFloat(16) == 11 && buffer.getFloat(20) == 22 && buffer.getFloat(24) == 33 && buffer.getFloat(28) == 44,
                "vec3 followed by float packed lane");
        checks.add("production vec3 writer plus following scalar preserves all four reflected lanes");

        Method putIVec3 = IrisVulkanUniformSnapshot.class.getDeclaredMethod("put", ByteBuffer.class, Vector3i.class);
        putIVec3.setAccessible(true);
        buffer.position(16);
        putIVec3.invoke(null, buffer, new Vector3i(101, 202, 303));
        buffer.putInt(28, 404);
        require(buffer.getInt(16) == 101 && buffer.getInt(20) == 202 && buffer.getInt(24) == 303 && buffer.getInt(28) == 404,
                "ivec3 followed by int packed lane");
        checks.add("production ivec3 writer plus following scalar preserves all four reflected lanes");

        Method putMat3 = IrisVulkanUniformSnapshot.class.getDeclaredMethod("put", ByteBuffer.class, Matrix3f.class);
        putMat3.setAccessible(true);
        buffer.position(16);
        putMat3.invoke(null, buffer, new Matrix3f(1, 2, 3, 4, 5, 6, 7, 8, 9));
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) require(buffer.getFloat(16 + column * 16 + row * 4) == 1 + column * 3 + row, "mat3 column/row");
            require(buffer.getFloat(16 + column * 16 + 12) == 0, "mat3 column padding");
        }
        checks.add("production mat3 writer is column-major with 16-byte column strides");

        Matrix4f matrix = new Matrix4f(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
        buffer.position(16);
        new Matrix4f(matrix).get(buffer); // The exact mat4 writing expression in Snapshot.write.
        for (int i = 0; i < 16; i++) require(buffer.getFloat(16 + i * 4) == i + 1, "mat4 column-major byte offset");
        checks.add("production mat4 expression writes column-major from the supplied ByteBuffer position");
        return checks;
    }

    private static int align16(int size) { return (size + 15) & -16; }
    private static void ok(int status) { if (status != Spvc.SPVC_SUCCESS) throw new AssertionError("SPIRV-Cross status " + status); }
    private static void require(boolean valid, String label) { if (!valid) throw new AssertionError(label); }
    private record CpuLayout(IrisVulkanUniformSnapshot.Field field, int offset, int size) { }
}
