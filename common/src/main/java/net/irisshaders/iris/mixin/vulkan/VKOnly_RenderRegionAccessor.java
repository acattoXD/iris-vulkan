package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RenderRegion.class, remap = false)
public interface VKOnly_RenderRegionAccessor {
	@Accessor("sections")
	RenderSection[] iris$getSections();
}
