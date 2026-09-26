package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockRenderer.class, remap = false)
public class VKOnly_MixinBlockRenderer_Materials {
	@Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;
	@Unique private ChunkSectionLayer iris$layer;
	@Inject(method = "renderModel", at = @At("HEAD"))
	private void iris$beginMaterial(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		var settings = WorldRenderingSettings.INSTANCE;
		var ids = settings.getBlockStateIds();
		int id = ids == null ? -1 : ids.getInt(state);
		for (var vertex : vertices) {
			var extension = (ChunkVertexExtension) vertex;
			extension.iris$setData((byte) state.getLightEmission(), (byte) 0, id, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
			extension.iris$ignoresMidBlock(false);
		}
		iris$layer = settings.getBlockTypeIds() == null ? null
			: BlockMaterialMapping.convertBlockToRenderType(settings.getBlockTypeIds().get(state.getBlock()));
	}
	@Inject(method = "processQuad", at = @At("HEAD"))
	private void iris$applyMaterialLayer(MutableQuadViewImpl quad, CallbackInfo ci) {
		if (iris$layer != null) quad.setRenderType(iris$layer);
	}
}
