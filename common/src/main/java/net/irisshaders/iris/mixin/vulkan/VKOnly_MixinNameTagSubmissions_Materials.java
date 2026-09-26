package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/** Name-tag and generic text share a renderer in 26.3, but retain different pack materials. */
@Mixin(SubmitNodeCollection.class)
public class VKOnly_MixinNameTagSubmissions_Materials {
	@WrapMethod(method = "nameTag")
	private static TextFeatureRenderer.Submit iris$nameTagMaterial(Matrix4f pose, float x, float y,
		FormattedCharSequence text, int color, int backgroundColor, int lightCoords, Font.DisplayMode mode,
		Operation<TextFeatureRenderer.Submit> original) {
		try (var ignored = IrisVulkanEntityContext.enter(IrisVulkanEntityContext.nameTagMaterial())) {
			return original.call(pose, x, y, text, color, backgroundColor, lightCoords, mode);
		}
	}
}
