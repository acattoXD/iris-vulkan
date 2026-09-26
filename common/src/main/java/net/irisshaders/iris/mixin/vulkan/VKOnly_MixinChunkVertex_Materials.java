package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.irisshaders.iris.vertices.sodium.terrain.ChunkVertexExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per-vertex material metadata follows Sodium's vertex copies, including fluid winding changes. */
@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
public class VKOnly_MixinChunkVertex_Materials implements ChunkVertexExtension {
	@Unique private byte iris$emission;
	@Unique private byte iris$renderType;
	@Unique private int iris$blockId = -1;
	@Unique private int iris$x, iris$y, iris$z;
	@Unique private boolean iris$ignoreMidBlock;
	@Override public void iris$setData(byte emission, byte renderType, int blockId, int x, int y, int z) {
		iris$emission = emission;
		iris$renderType = renderType;
		iris$blockId = blockId;
		iris$x = x; iris$y = y; iris$z = z;
	}
	@Override public void iris$ignoresMidBlock(boolean ignore) { iris$ignoreMidBlock = ignore; }
	@Override public void iris$copyData(ChunkVertexExtension destination) {
		destination.iris$setData(iris$emission, iris$renderType, iris$blockId, iris$x, iris$y, iris$z);
		destination.iris$ignoresMidBlock(iris$ignoreMidBlock);
	}
	@Inject(method = "copyVertexTo", at = @At("HEAD"))
	private static void iris$copyMaterial(ChunkVertexEncoder.Vertex source, ChunkVertexEncoder.Vertex destination, CallbackInfo ci) {
		((ChunkVertexExtension) source).iris$copyData((ChunkVertexExtension) destination);
	}
	@Override public int getBlockId() { return iris$blockId; }
	@Override public byte getRenderType() { return iris$renderType; }
	@Override public byte getBlockEmission() { return iris$emission; }
	@Override public int getLocalPosX() { return iris$x; }
	@Override public int getLocalPosY() { return iris$y; }
	@Override public int getLocalPosZ() { return iris$z; }
	@Override public boolean ignoreMidBlock() { return iris$ignoreMidBlock; }
}
