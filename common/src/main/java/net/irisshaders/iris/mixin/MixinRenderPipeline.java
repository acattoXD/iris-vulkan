package net.irisshaders.iris.mixin;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.ImmediateState;
import net.irisshaders.iris.vertices.IrisVertexFormats;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

@Mixin(RenderPipeline.class)
public class MixinRenderPipeline {
	@Inject(method = "getVertexFormatBinding", at = @At("RETURN"), cancellable = true)
	private void iris$change(CallbackInfoReturnable<VertexFormat> cir) {
		if (Iris.isPackInUseQuick() && Thread.currentThread().getName().contains("Render") && ImmediateState.isRenderingLevel && !ImmediateState.skipExtension.get()) {
			VertexFormat vf = cir.getReturnValue();
			RenderPipeline thiss = (RenderPipeline) (Object) this;
			if (Objects.equals(vf, DefaultVertexFormat.BLOCK)) {
				cir.setReturnValue(IrisVertexFormats.TERRAIN);
			} else if (Objects.equals(vf, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)) {
				cir.setReturnValue(IrisVertexFormats.GLYPH);
			} else if (Objects.equals(vf, DefaultVertexFormat.ENTITY)) {
				cir.setReturnValue(IrisVertexFormats.ENTITY);
			} else if (Objects.equals(vf, DefaultVertexFormat.ENTITY_GLINT_SPECIAL)) {
				cir.setReturnValue(IrisVertexFormats.ENTITY_GLINT_SPECIAL);
			} else if (Objects.equals(vf, ChunkMeshFormats.COMPACT.getVertexFormat())) {
				cir.setReturnValue(WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat());
			}
		}
	}
	@Inject(method = "getVertexFormatBindings", at = @At("RETURN"), cancellable = true)
	private void iris$change2(CallbackInfoReturnable<List<VertexFormat>> cir) {
		if (Iris.isPackInUseQuick() && Thread.currentThread().getName().contains("Render") && ImmediateState.isRenderingLevel && !ImmediateState.skipExtension.get()) {
			List<VertexFormat> bindings = new ArrayList<>(cir.getReturnValue());
			VertexFormat vf = bindings.get(0);
			RenderPipeline thiss = (RenderPipeline) (Object) this;
			if (Objects.equals(vf, DefaultVertexFormat.BLOCK)) {
				bindings.set(0, IrisVertexFormats.TERRAIN);
				cir.setReturnValue(bindings);
			} else if (Objects.equals(vf, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)) {
				bindings.set(0, IrisVertexFormats.GLYPH);
				cir.setReturnValue(bindings);
			} else if (Objects.equals(vf, DefaultVertexFormat.POSITION_TEX_COLOR) && thiss.equals(RenderPipelines.TEXT_SEE_THROUGH)) {
				bindings.set(0, IrisVertexFormats.GLYPH);
				cir.setReturnValue(bindings);
			} else if (Objects.equals(vf, DefaultVertexFormat.ENTITY)) {
				bindings.set(0, IrisVertexFormats.ENTITY);
				cir.setReturnValue(bindings);
			} else if (Objects.equals(vf, DefaultVertexFormat.ENTITY_GLINT_SPECIAL)) {
				bindings.set(0, IrisVertexFormats.ENTITY_GLINT_SPECIAL);
				cir.setReturnValue(bindings);
			} else if (Objects.equals(vf, ChunkMeshFormats.COMPACT.getVertexFormat())) {
                bindings.set(0, WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat());
				cir.setReturnValue(bindings);
            }
		}
	}
}
