package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import java.util.Collection;
import java.util.Map;

/** CPU-only replacement for the mixin constructor invoker; creates the real immutable pipeline. */
public interface VKOnly_RenderPipelineAccessor {
    static RenderPipeline iris$create(Identifier location, Map<ShaderType, Identifier> shaders, ShaderDefines defines,
            Collection<BindGroupLayout> layouts, ColorTargetState[] colors, DepthStencilState depth, PolygonMode polygon,
            boolean cull, VertexFormat[] formats, PrimitiveTopology topology, int pushConstantSize, int sortKey) {
        try {
            var constructor = RenderPipeline.class.getDeclaredConstructor(Identifier.class, Map.class,
                    ShaderDefines.class, Collection.class, ColorTargetState[].class, DepthStencilState.class, PolygonMode.class,
                    boolean.class, VertexFormat[].class, PrimitiveTopology.class, int.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(location, shaders, defines, layouts, colors, depth, polygon, cull, formats, topology, pushConstantSize, sortKey);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
