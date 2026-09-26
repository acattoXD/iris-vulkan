package net.irisshaders.iris.probe.mixin;

import net.irisshaders.iris.probe.NativePortalProbe;
import net.minecraft.client.renderer.blockentity.TheEndGatewayRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TheEndGatewayRenderer.class)
public abstract class ProbePortalGatewayMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/EndGatewayRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
    private void iris$gatewaySubmitted(CallbackInfo ci) { NativePortalProbe.submitted(true); }
}
