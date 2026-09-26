package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultFluidRenderer.class, remap = false)
public class VKOnly_MixinFluidRenderer_Materials {
	@Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;
	@Inject(method = "render", at = @At("HEAD"))
	private void iris$beginFluidMaterial(LevelSlice level, BlockState block, FluidState fluid, BlockPos pos, BlockPos origin,
		TranslucentGeometryCollector collector, ChunkModelBuilder builder, Material material, ColorProvider<FluidState> color, FluidModel model, CallbackInfo ci) {
		var ids = WorldRenderingSettings.INSTANCE.getBlockStateIds();
		int id = ids == null ? -1 : ids.getInt(fluid.createLegacyBlock());
		for (var vertex : vertices) {
			var extension = (ChunkVertexExtension) vertex;
			extension.iris$setData((byte) block.getLightEmission(), (byte) 1, id, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
			extension.iris$ignoresMidBlock(false);
		}
	}
	@ModifyVariable(method = "updateQuad", at = @At("HEAD"), argsOnly = true)
	private float iris$disableVanillaDirectionalShade(float brightness) {
		return WorldRenderingSettings.INSTANCE.shouldDisableDirectionalShading() ? 1.0F : brightness;
	}
}
