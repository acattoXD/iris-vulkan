package net.irisshaders.iris.probe.mixin;

import net.irisshaders.iris.probe.NativeBenchmarkProbe;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** GPU timestamp markers bracket actual world/shadow/composite command submission. */
@Mixin(GameRenderer.class)
public class ProbeBenchmarkWorldMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void iris$benchmarkWorldBegin(CallbackInfo ci) { NativeBenchmarkProbe.beginWorld(); }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void iris$benchmarkWorldEnd(CallbackInfo ci) { NativeBenchmarkProbe.endWorld(); }
}
