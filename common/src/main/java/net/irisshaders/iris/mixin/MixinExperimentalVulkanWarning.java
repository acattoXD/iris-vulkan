package net.irisshaders.iris.mixin;

import net.irisshaders.iris.gui.screen.ExperimentalVulkanWarning;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinExperimentalVulkanWarning {
    @Inject(method = "runTick", at = @At("RETURN"))
    private void iris$showExperimentalVulkanWarning(CallbackInfo ci) {
        ExperimentalVulkanWarning.tick((Minecraft) (Object) this);
    }
}
