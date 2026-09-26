package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.vulkan.IrisVulkanPhaseContext;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class VKOnly_MixinSodiumTerrain_Phases {
	@WrapMethod(method = "render")
	private void iris$terrainPass(ChunkRenderMatrices matrices, ChunkRenderListIterable lists, TerrainRenderPass pass,
		CameraTransform camera, FogParameters fog, boolean indexedRendering, RenderPass renderPass, GpuSampler terrainSampler,
		GpuBufferSlice globals, GpuBuffer sectionTimes, OitStage oit, Operation<Void> original) {
		var pipeline = IrisVulkanPhaseContext.pipeline();
		if (pipeline != null && pipeline.skipAllRendering()) return;
		WorldRenderingPhase phase = pass.isTranslucent() ? WorldRenderingPhase.TERRAIN_TRANSLUCENT
			: pass.supportsFragmentDiscard() ? WorldRenderingPhase.TERRAIN_CUTOUT : WorldRenderingPhase.TERRAIN_SOLID;
		try (var ignored = IrisVulkanPhaseContext.enter(phase)) {
			original.call(matrices, lists, pass, camera, fog, indexedRendering, renderPass, terrainSampler, globals, sectionTimes, oit);
		}
	}
}
