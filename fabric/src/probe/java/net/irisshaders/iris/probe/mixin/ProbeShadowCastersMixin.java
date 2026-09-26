package net.irisshaders.iris.probe.mixin;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Probe-only isolation of terrain shadows from entity/block-entity casters. */
@Mixin(value = IrisVulkanShadowRenderer.class, remap = false)
public class ProbeShadowCastersMixin {
    @Unique private static boolean iris$reportedSkip;

    @Inject(method = "renderEntityCasters", at = @At("HEAD"), cancellable = true, remap = false)
    private void iris$skipEntityCasters(CallbackInfo ci) {
        if (!Boolean.getBoolean("iris.probe.shadowSkipEntities")) return;
        if (!iris$reportedSkip) {
            iris$reportedSkip = true;
            Iris.logger.info("IRIS_PROBE_SHADOW_SKIP_ENTITIES: native entity and block-entity shadow casters disabled; terrain unchanged");
        }
        ci.cancel();
    }
}
