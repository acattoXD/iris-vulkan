package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spvc.*;

/** Bridges legacy pack GLSL into RenderPearl's reflected pipeline contract. */
public final class IrisVulkanGraphicsCompiler {
    private static final Map<VulkanDevice, Compiler> COMPILERS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<VulkanRenderPipeline, FrontendRenderPipeline> FRONTENDS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<VulkanRenderPipeline, RenderPipeline> DESCRIPTORS = Collections.synchronizedMap(new IdentityHashMap<>());

    private IrisVulkanGraphicsCompiler() { }

    public static VulkanRenderPipeline compile(VulkanDevice device, RenderPipeline pipeline,
            String name, String vertexSource, String fragmentSource) throws ShaderCompileException {
        Compiler compiler = COMPILERS.computeIfAbsent(device, ignored -> new Compiler());
        try (SpvModule vertex = compiler.compile(name + "_vertex", vertexSource, ShaderType.VERTEX);
             SpvModule fragment = compiler.compile(name + "_fragment", fragmentSource, ShaderType.FRAGMENT)) {
            Set<String> storageSamplers = IrisVulkanStoragePipeline.storageSamplerNames();
            var vertexStorage = IrisVulkanStorageReflection.reflect(vertex.spv(), storageSamplers);
            var fragmentStorage = IrisVulkanStorageReflection.reflect(fragment.spv(), storageSamplers);
            Prepared prepared = prepare(pipeline, name, vertex, fragment, vertexStorage, fragmentStorage);
            VulkanRenderPipeline compiled = IrisVulkanStoragePipeline.compile(device, prepared.createInfo(), prepared.storageBindings());
            FrontendRenderPipeline frontend = new FrontendRenderPipeline(pipeline.getLocation().toString(), compiled,
                    Collections.unmodifiableList(new ArrayList<>(pipeline.getVertexFormatBindings())),
                    Object2IntMaps.unmodifiable(prepared.uniformIndices()), prepared.createInfo().uniforms(),
                    Collections.unmodifiableList(new ArrayList<>(pipeline.getColorTargetStates())),
                    pipeline.wantsDepthTexture(), prepared.createInfo().pushConstantsSize());
            FRONTENDS.put(compiled, frontend);
            DESCRIPTORS.put(compiled, pipeline);
            return compiled;
        }
    }

    public record Prepared(BackendRenderPipeline.CreateInfo createInfo,
                           Object2IntOpenHashMap<String> uniformIndices,
                           List<IrisVulkanStorageReflection.Binding> storageBindings) { }

    /** Retains named varyings, active descriptors and the producer's exact vertex layout. */
    public static Prepared prepare(RenderPipeline pipeline, String name, SpvModule vertex, SpvModule fragment,
            IrisVulkanStorageReflection.Reflection vertexStorage,
            IrisVulkanStorageReflection.Reflection fragmentStorage) throws ShaderCompileException {
        SpvModule.Reflection vertexReflection = vertex.reflect();
        SpvModule.Reflection fragmentReflection = fragment.reflect();
        Map<String, SpvModule.Reflection.InterfaceVariable> outputs = new LinkedHashMap<>();
        for (var output : vertexReflection.outputs()) outputs.put(output.name(), output);
        for (var input : fragmentReflection.inputs()) {
            var output = outputs.get(input.name());
            if (output == null) throw new ShaderCompileException("Missing vertex output for fragment varying " + input.name());
            if (!compatible(output.type(), input.type()) || output.hasDecoration(14) != input.hasDecoration(14)) {
                throw new ShaderCompileException("Varying type/interpolation mismatch for " + input.name());
            }
            input.location(output.location());
        }

        Map<String, SpvModule.Reflection.InterfaceVariable> inputs = new LinkedHashMap<>();
        for (var input : vertexReflection.inputs()) inputs.put(input.name(), input);
        List<BackendRenderPipeline.CreateInfo.VertexBuffer> buffers = new ArrayList<>();
        List<BackendRenderPipeline.CreateInfo.AttribBinding> attributes = new ArrayList<>();
        var formats = pipeline.getVertexFormatBindings();
        for (int slot = 0; slot < formats.size(); ++slot) {
            var format = formats.get(slot);
            if (format == null) continue;
            buffers.add(new BackendRenderPipeline.CreateInfo.VertexBuffer(slot, format.getVertexSize(), format.getStepRate()));
            Map<String, Integer> elements = new LinkedHashMap<>();
            for (var element : format.getElements()) {
                var input = inputs.get(element.name());
                if (input == null) continue;
                int location = input.location() + elements.merge(element.name(), 1, Integer::sum) - 1;
                attributes.add(new BackendRenderPipeline.CreateInfo.AttribBinding(slot, location, element.offset(), element.format()));
            }
        }
        for (var input : vertexReflection.inputs()) {
            if (attributes.stream().noneMatch(attribute -> attribute.location() == input.location())) {
                throw new ShaderCompileException("Missing producer vertex attribute " + input.name());
            }
        }

        Map<String, BindGroupLayout.UniformDescription> declarations = new LinkedHashMap<>();
        for (var declaration : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            var previous = declarations.putIfAbsent(declaration.name(), declaration);
            if (previous != null && (previous.type() != declaration.type() || previous.gpuFormat() != declaration.gpuFormat())) {
                throw new ShaderCompileException("Conflicting pipeline uniform declaration " + declaration.name());
            }
        }
        Set<String> advancedNames = new java.util.HashSet<>();
        vertexStorage.resources().forEach(resource -> advancedNames.add(resource.name()));
        fragmentStorage.resources().forEach(resource -> advancedNames.add(resource.name()));
        Map<String, SpvModule.Reflection.Descriptor> allDescriptors = new LinkedHashMap<>();
        for (var reflection : List.of(vertexReflection, fragmentReflection)) {
            for (var descriptor : reflection.descriptors()) {
                var previous = allDescriptors.putIfAbsent(descriptor.name(), descriptor);
                if (previous != null && (previous.resourceType() != descriptor.resourceType()
                        || previous.type().dimensions() != descriptor.type().dimensions()
                        || !compatible(previous.type(), descriptor.type()))) {
                    throw new ShaderCompileException("Descriptor differs between stages: " + descriptor.name());
                }
            }
        }
        Object2IntOpenHashMap<String> uniformIndices = new Object2IntOpenHashMap<>();
        List<BindGroupLayout.UniformDescription> uniforms = new ArrayList<>();
        Map<String, SpvModule.Reflection.Descriptor> sharedDescriptors = new LinkedHashMap<>();
        for (SpvModule.Reflection reflection : List.of(vertexReflection, fragmentReflection)) {
            for (var descriptor : reflection.descriptors()) {
                if (advancedNames.contains(descriptor.name())) continue;
                var declaration = declarations.get(descriptor.name());
                if (declaration == null) throw new ShaderCompileException("No pipeline binding for " + descriptor.name());
                int expected = declaration.type() == UniformType.UNIFORM_BUFFER ? SPVC_RESOURCE_TYPE_UNIFORM_BUFFER : SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;
                if (descriptor.resourceType() != expected || descriptor.type().arrayDimensions() != 0) {
                    throw new ShaderCompileException("Unsupported descriptor type/array for " + descriptor.name());
                }
                if (expected == SPVC_RESOURCE_TYPE_SAMPLED_IMAGE
                        && (descriptor.type().dimensions() == 5) != (declaration.type() == UniformType.TEXEL_BUFFER)) {
                    throw new ShaderCompileException("Texel-buffer/image binding mismatch for " + descriptor.name());
                }
                var previous = sharedDescriptors.putIfAbsent(descriptor.name(), descriptor);
                if (previous != null && (previous.resourceType() != descriptor.resourceType()
                        || previous.type().dimensions() != descriptor.type().dimensions())) {
                    throw new ShaderCompileException("Descriptor differs between stages: " + descriptor.name());
                }
                if (!uniformIndices.containsKey(descriptor.name())) {
                    uniformIndices.put(descriptor.name(), uniforms.size());
                    uniforms.add(declaration);
                }
                descriptor.binding(uniformIndices.getInt(descriptor.name()));
                descriptor.descriptorSetIndex(0);
            }
        }
        List<IrisVulkanStorageReflection.Binding> storageBindings = IrisVulkanStorageReflection.bindings(uniforms.size(), vertexStorage, fragmentStorage);
        IrisVulkanStorageReflection.rebind(vertex.spv(), vertexStorage, storageBindings);
        IrisVulkanStorageReflection.rebind(fragment.spv(), fragmentStorage, storageBindings);
        int pushConstantSize = pipeline.pushConstantSize();
        for (var reflection : List.of(vertexReflection, fragmentReflection)) {
            if (reflection.pushConstants().size() > 1) throw new ShaderCompileException("Multiple push-constant blocks are unsupported");
            for (var constant : reflection.pushConstants()) {
                if (constant.size() > pushConstantSize) throw new ShaderCompileException("Shader push constant exceeds pipeline ABI: " + constant.size() + " > " + pushConstantSize);
            }
        }
        var createInfo = new BackendRenderPipeline.CreateInfo(pipeline.getLocation().toString(),
                List.of(new BackendRenderPipeline.CreateInfo.Shader(name + "_vertex", "main", vertex),
                        new BackendRenderPipeline.CreateInfo.Shader(name + "_fragment", "main", fragment)),
                List.copyOf(buffers), List.copyOf(attributes), List.copyOf(uniforms), pushConstantSize,
                pipeline.getDepthStencilState(), pipeline.getPolygonMode(), pipeline.isCull(),
                pipeline.getColorTargetStates(), pipeline.getPrimitiveTopology());
        return new Prepared(createInfo, uniformIndices, storageBindings);
    }

    private static boolean compatible(SpvModule.Reflection.Type left, SpvModule.Reflection.Type right) {
        if (left.baseType() != right.baseType() || left.vectorSize() != right.vectorSize()
                || left.arrayDimensions() != right.arrayDimensions()) return false;
        for (int i = 0; i < left.arrayDimensions(); ++i) if (left.arrayLength(i) != right.arrayLength(i)) return false;
        return true;
    }

    public static FrontendRenderPipeline frontend(VulkanRenderPipeline pipeline) { return FRONTENDS.get(pipeline); }
    public static RenderPipeline descriptor(VulkanRenderPipeline pipeline) { return DESCRIPTORS.get(pipeline); }
    public static void unregister(VulkanRenderPipeline pipeline) { FRONTENDS.remove(pipeline); DESCRIPTORS.remove(pipeline); }
    public static void closeCompiler(VulkanDevice device) {
        Compiler compiler = COMPILERS.remove(device);
        if (compiler != null) compiler.close();
    }

    public static String injectDefines(String source, ShaderDefines defines) {
        int versionEnd = source.indexOf('\n', source.indexOf("#version"));
        if (versionEnd < 0) throw new IllegalArgumentException("Shader has no version line");
        return source.substring(0, versionEnd + 1) + defines.asSourceDirectives() + source.substring(versionEnd + 1);
    }

    /** Kept separate from Mojang's compiler so vanilla explicit-location GLSL is unaffected. */
    private static final class Compiler implements AutoCloseable {
        private final long compiler = shaderc_compiler_initialize();
        private final long options = shaderc_compile_options_initialize();
        Compiler() {
            if (compiler == 0 || options == 0) throw new IllegalStateException("Cannot initialize Iris shader compiler");
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);
            shaderc_compile_options_add_macro_definition(options, "gl_VertexID", "gl_VertexIndex");
            shaderc_compile_options_add_macro_definition(options, "gl_InstanceID", "gl_InstanceIndex");
        }
        synchronized SpvModule compile(String name, String source, ShaderType type) throws ShaderCompileException {
            // LWJGL's CharSequence overload encodes the entire expanded shader on
            // MemoryStack. Real packs exceed that small temporary stack routinely.
            ByteBuffer sourceBytes = MemoryUtil.memUTF8(source, false);
            ByteBuffer nameBytes = null;
            ByteBuffer entryPointBytes = null;
            try {
                nameBytes = MemoryUtil.memUTF8(name);
                entryPointBytes = MemoryUtil.memUTF8("main");
                long result = shaderc_compile_into_spv(compiler, sourceBytes,
                        type == ShaderType.VERTEX ? shaderc_vertex_shader : shaderc_fragment_shader,
                        nameBytes, entryPointBytes, options);
                if (result == 0) throw new ShaderCompileException("shaderc returned no result for " + name);
                try {
                    if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                        throw new ShaderCompileException(name + ": " + shaderc_result_get_error_message(result));
                    }
                    ByteBuffer bytes = shaderc_result_get_bytes(result);
                    if (bytes == null) throw new ShaderCompileException("shaderc returned no SPIR-V for " + name);
                    ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining()).order(ByteOrder.nativeOrder());
                    copy.put(bytes).flip();
                    return new SPIRVModule(copy, type);
                } finally {
                    shaderc_result_release(result);
                }
            } finally {
                MemoryUtil.memFree(entryPointBytes);
                MemoryUtil.memFree(nameBytes);
                MemoryUtil.memFree(sourceBytes);
            }
        }
        @Override public void close() { shaderc_compile_options_release(options); shaderc_compiler_release(compiler); }
    }
}
