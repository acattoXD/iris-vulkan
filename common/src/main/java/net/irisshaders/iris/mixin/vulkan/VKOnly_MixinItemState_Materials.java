package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.mixinterface.ItemContextState;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public class VKOnly_MixinItemState_Materials implements ItemContextState {
	@Unique private Item iris$item;
	@Unique private Identifier iris$model;
	@Override public void setDisplayItem(Item item, Identifier model) { iris$item = item; iris$model = model; }
	@Override public Item getDisplayItem() { return iris$item; }
	@Override public Identifier getDisplayItemModel() { return iris$model; }
	@Inject(method = "clear", at = @At("HEAD"))
	private void iris$clearItem(CallbackInfo ci) { iris$item = null; iris$model = null; }
	@WrapMethod(method = "submit")
	private void iris$captureItem(PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline, Operation<Void> original) {
		var settings = WorldRenderingSettings.INSTANCE;
		var previous = IrisVulkanEntityContext.current();
		int itemId = 0;
		int blockId = previous.blockEntityId();
		if (iris$item instanceof BlockItem block && !(iris$item instanceof SolidBucketItem)) {
			if (settings.getBlockStateIds() != null) itemId = settings.getBlockStateIds().getOrDefault(block.getBlock().defaultBlockState(), 0);
			blockId = 1;
		} else if (iris$item != null && settings.getItemIds() != null) {
			var name = iris$model != null ? iris$model : BuiltInRegistries.ITEM.getKey(iris$item);
			itemId = settings.getItemIds().getInt(new NamespacedId(name.getNamespace(), name.getPath()));
		}
		try (var ignored = IrisVulkanEntityContext.enter(new IrisVulkanEntityContext.Material(previous.entityId(), blockId, itemId, previous.blockEntity()))) {
			original.call(pose, collector, light, overlay, outline);
		}
	}
}
