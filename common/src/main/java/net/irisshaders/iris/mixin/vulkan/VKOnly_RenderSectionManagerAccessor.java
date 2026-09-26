package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = RenderSectionManager.class, remap = false)
public interface VKOnly_RenderSectionManagerAccessor {
	@Accessor("regions")
	RenderRegionManager iris$getRegions();
}
