package net.irisshaders.iris.vulkan;

import com.google.gson.GsonBuilder;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compiler evidence for undefined SSA values; absence is not a complete undefined-behavior proof. */
public final class IrisVulkanShaderUndefAudit {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        List<Object> reports = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path input = Path.of(args[i]);
            String source = Files.readString(input);
            for (int optimization : new int[]{Shaderc.shaderc_optimization_level_zero, Shaderc.shaderc_optimization_level_performance}) {
                long compiler = Shaderc.shaderc_compiler_initialize(), options = Shaderc.shaderc_compile_options_initialize(), result = 0;
                try {
                    Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
                    Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                    Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
                    Shaderc.shaderc_compile_options_set_generate_debug_info(options);
                    Shaderc.shaderc_compile_options_set_optimization_level(options, optimization);
                    result = Shaderc.shaderc_compile_into_spv_assembly(compiler, source, Shaderc.shaderc_fragment_shader, input.toString(), "main", options);
                    if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) throw new AssertionError(Shaderc.shaderc_result_get_error_message(result));
                    String assembly = MemoryUtil.memUTF8(Shaderc.shaderc_result_get_bytes(result));
                    Path assemblyPath = output.resolve(input.getFileName() + ".O" + optimization + ".spvasm");
                    Files.writeString(assemblyPath, assembly);
                    List<String> undef = assembly.lines().filter(line -> line.contains("OpUndef")).toList();
                    reports.add(Map.of("source", input.toString(), "optimization", optimization, "undef", undef, "assembly", assemblyPath.toString()));
                    System.out.println(input.getFileName() + " O" + optimization + ": OpUndef=" + undef.size());
                } finally {
                    if (result != 0) Shaderc.shaderc_result_release(result);
                    Shaderc.shaderc_compile_options_release(options); Shaderc.shaderc_compiler_release(compiler);
                }
            }
        }
        Files.writeString(output.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(reports));
    }
}
