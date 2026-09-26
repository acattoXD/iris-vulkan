package net.irisshaders.iris.mixin.vulkan;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface VKOnly_SodiumWorldRendererAccessor {
	@Accessor("renderSectionManager")
	RenderSectionManager iris$getRenderSectionManager();

	@Accessor("useTranslucencySorting")
	boolean iris$usesTranslucencySorting();
}
