package net.irisshaders.iris.probe.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.probe.NativeCameraProbe;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Probe jar only; dormant unless the isolated camera controller is active. */
@Mixin(GameRenderer.class)
public abstract class ProbeCameraMotionMixin {
    @Shadow
    private void bobView(CameraRenderState camera, PoseStack stack) { throw new AssertionError(); }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void iris$cameraBegin(DeltaTracker delta, CallbackInfo ci) {
        CameraRenderState camera = NativeCameraProbe.beginFrame((GameRenderer) (Object) this);
        if (camera != null) {
            PoseStack stack = new PoseStack();
            bobView(camera, stack);
            NativeCameraProbe.expectedBobbing(stack.last().pose());
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void iris$cameraEnd(DeltaTracker delta, CallbackInfo ci) {
        NativeCameraProbe.endFrame((GameRenderer) (Object) this);
    }
}
