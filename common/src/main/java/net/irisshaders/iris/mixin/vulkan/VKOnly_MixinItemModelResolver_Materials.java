package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.mixinterface.ItemContextState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelResolver.class)
public class VKOnly_MixinItemModelResolver_Materials {
	@Inject(method = "appendItemLayers", at = @At("HEAD"))
	private void iris$rememberItem(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Level level, ItemOwner owner, int seed, CallbackInfo ci) {
		((ItemContextState) state).setDisplayItem(stack == null ? null : stack.getItem(), stack == null ? null : stack.get(DataComponents.ITEM_MODEL));
	}
}
