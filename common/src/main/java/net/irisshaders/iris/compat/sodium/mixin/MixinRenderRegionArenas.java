package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderRegion.DeviceResources.class)
public class MixinRenderRegionArenas {
	@Group(name = "iris$arenaVertexType", min = 1, max = 1)
	@Redirect(method = "<init>", remap = false, require = 0,
		at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;COMPACT:Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;",
			remap = false))
	private ChunkVertexType iris$useExtendedStride() {
		return WorldRenderingSettings.INSTANCE.getVertexFormat();
	}

	// Sodium 0.9.2 obtains the arena stride through getCurrent() instead of
	// reading COMPACT directly. Exactly one version-specific hook must apply.
	@Group(name = "iris$arenaVertexType", min = 1, max = 1)
	@Redirect(method = "<init>", remap = false, require = 0,
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;getCurrent()Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;",
			remap = false))
	private ChunkVertexType iris$useCurrentExtendedStride() {
		return WorldRenderingSettings.INSTANCE.getVertexFormat();
	}
}
