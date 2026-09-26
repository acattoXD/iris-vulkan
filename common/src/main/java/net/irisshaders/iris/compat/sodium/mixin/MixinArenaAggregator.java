package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Sodium 0.9.2 registers each arena's supported geometry stride at construction. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator", remap = false)
public class MixinArenaAggregator {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;getCurrent()Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;"))
    private ChunkVertexType iris$registerCurrentGeometryStride() {
        // Sodium's arena registration must agree with our terrain producers and
        // RenderRegion.DeviceResources, including the extended material fields.
        return WorldRenderingSettings.INSTANCE.getVertexFormat();
    }
}
