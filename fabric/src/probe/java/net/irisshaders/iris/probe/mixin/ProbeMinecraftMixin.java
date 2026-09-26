package net.irisshaders.iris.probe.mixin;
import net.irisshaders.iris.probe.NativeProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class ProbeMinecraftMixin {
    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void iris$benchmarkBegin(boolean render, CallbackInfo ci) {
        net.irisshaders.iris.probe.NativeBenchmarkProbe.beginFrame();
    }
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V"))
    private void iris$showOnlyInteractiveTests(long window) {
        if (!Boolean.getBoolean("iris.vulkan.probe.hidden")) org.lwjgl.glfw.GLFW.glfwShowWindow(window);
    }
    @Inject(method = "runTick(Z)V", at = @At("RETURN"))
    private void iris$probe(boolean render, CallbackInfo ci) { NativeProbe.frame((Minecraft) (Object) this); }
}
