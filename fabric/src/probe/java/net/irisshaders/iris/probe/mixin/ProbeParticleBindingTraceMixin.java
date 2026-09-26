package net.irisshaders.iris.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.vulkan.VKOnly_RenderPassAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_TextureViewAndSamplerAccessor;
import net.irisshaders.iris.mixin.vulkan.VKOnly_VulkanRenderPassAccessor;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Optional probe evidence collected after Iris has bound resources for the actual particle draw. */
@Mixin(QuadParticleFeatureRenderer.class)
public class ProbeParticleBindingTraceMixin {
	@Unique private static final Set<String> iris$reportedParticleBindings = new HashSet<>();

	@WrapOperation(method = "drawLayers", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/api/commands/RenderPass;drawIndexed(IIIII)V"))
	private static void iris$traceParticleLayer(RenderPass pass, int count, int instances, int firstIndex,
												  int baseVertex, int firstInstance, Operation<Void> original,
												  @Local Map.Entry<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> entry) {
		original.call(pass, count, instances, firstIndex, baseVertex, firstInstance);
		if (!Boolean.getBoolean("iris.vulkan.probe.particleTrace")) return;
		var backend = ((VKOnly_RenderPassAccessor) pass).iris$getBackend();
		if (!(backend instanceof VulkanRenderPass vulkan)) return;
		var access = (VKOnly_VulkanRenderPassAccessor) vulkan;
		var textures = access.iris$getTextures();
		GpuTextureView canonical = iris$view(textures.get("Sampler0"));
		GpuTextureView aliased = iris$view(textures.get("tex"));
		String expected = entry.getKey().textureAtlasLocation().toString();
		String description = "layer=" + expected + " Sampler0=" + iris$textureDescription(canonical)
			+ " tex=" + iris$textureDescription(aliased) + " sameView=" + (canonical == aliased);
		if (iris$reportedParticleBindings.size() < 24 && iris$reportedParticleBindings.add(description)) {
			Iris.logger.info("PARTICLE_BINDING_TRACE {} shader={} indexCount={} firstIndex={} baseVertex={}",
				description, access.iris$getPipeline().info().getLocation(), count, firstIndex, baseVertex);
		}
	}

	@Unique
	private static GpuTextureView iris$view(Object binding) {
		return binding == null ? null : ((VKOnly_TextureViewAndSamplerAccessor) binding).iris$getView();
	}

	@Unique
	private static String iris$textureDescription(GpuTextureView view) {
		if (view == null) return "<missing>";
		var texture = view.texture();
		return texture.getLabel() + "@" + Integer.toHexString(System.identityHashCode(texture))
			+ ":" + texture.getWidth(0) + "x" + texture.getHeight(0);
	}
}
