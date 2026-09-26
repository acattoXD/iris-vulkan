package net.irisshaders.iris.pipeline.programs;

import com.mojang.renderpearl.backend.opengl.GlProgram;

import java.util.function.Supplier;

public record ShaderSupplier(ShaderKey key, PartialShader id, Supplier<GlProgram> shader, IrisShaderSource irisSource) {
	public ShaderSupplier(ShaderKey key, PartialShader id, Supplier<GlProgram> shader) {
		this(key, id, shader, null);
	}
}
