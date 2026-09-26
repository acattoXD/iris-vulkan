package net.irisshaders.iris.mixin.vulkan;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntityRenderState.class)
public interface VKOnly_BlockEntityStateAccessor {
	@Accessor("blockState") BlockState iris$blockState();
}
