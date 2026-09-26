package net.irisshaders.iris.probe.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps disposable screenshot fixtures independent of desktop mouse movement. */
@Mixin(MouseHandler.class)
public class ProbeMouseMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void iris$fixedProbeCamera(double elapsed, CallbackInfo ci) {
        if (!Boolean.getBoolean("iris.vulkan.probe.interactive") && System.getProperty("iris.vulkan.probe.runDir") != null && Minecraft.getInstance().level != null) ci.cancel();
    }
}
