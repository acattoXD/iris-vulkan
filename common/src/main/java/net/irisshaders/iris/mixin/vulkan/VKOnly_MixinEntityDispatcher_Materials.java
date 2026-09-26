package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public class VKOnly_MixinEntityDispatcher_Materials {
	@WrapMethod(method = "submit")
	private <S extends EntityRenderState> void iris$captureEntity(S state, CameraRenderState camera, double x, double y, double z,
		PoseStack pose, SubmitNodeCollector collector, Operation<Void> original) {
		var ids = WorldRenderingSettings.INSTANCE.getEntityIds();
		int id = 0;
		if (ids != null && state.entityType != null) {
			var name = BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType);
			NamespacedId key = new NamespacedId(name.getNamespace(), name.getPath());
			NamespacedId player = new NamespacedId("minecraft", "current_player");
			NamespacedId converting = new NamespacedId("minecraft", "zombie_villager_converting");
			if (state instanceof ZombieVillagerRenderState zombie && zombie.isConverting && ids.containsKey(converting)) key = converting;
			else if (state instanceof AvatarRenderState avatar && Minecraft.getInstance().getCameraEntity() != null
				&& Minecraft.getInstance().getCameraEntity().getId() == avatar.id && ids.containsKey(player)) key = player;
			id = ids.getInt(key);
		}
		try (var ignored = IrisVulkanEntityContext.enter(new IrisVulkanEntityContext.Material(id, 0, 0, false))) {
			original.call(state, camera, x, y, z, pose, collector);
		}
	}
}
