package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public class VKOnly_MixinShaderChunkRenderer_Materials {
	@Shadow @Final private static Map<TerrainRenderPass, RenderPipeline> programs;
	@Shadow @Final protected VertexFormat vertexFormat;
	@Inject(method = "compileProgram", at = @At("HEAD"))
	private void iris$invalidateOldVertexLayout(TerrainRenderPass pass, OitStage oit, CallbackInfoReturnable<RenderPipeline> ci) {
		RenderPipeline cached = programs.get(pass);
		if (cached != null && cached.getVertexFormatBinding(0) != vertexFormat) programs.remove(pass);
	}
}
