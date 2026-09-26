package net.irisshaders.iris.mixin.vulkan;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ByteBufferBuilder.class)
public interface VKOnly_ByteBufferBuilderNormalAccess {
	@Accessor("pointer") long iris$normalBufferBase();
}
