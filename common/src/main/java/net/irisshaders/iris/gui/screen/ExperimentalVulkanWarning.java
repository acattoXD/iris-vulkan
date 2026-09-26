package net.irisshaders.iris.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.backend.IrisBackend;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Visible disclosure for this fork's experimental shader backend, including config-file selections. */
public final class ExperimentalVulkanWarning {
    private static boolean shownThisSession;
    private ExperimentalVulkanWarning() { }

    public static void selectBackend(PreferredGraphicsApi value, Runnable accept, Runnable cancel) {
        Minecraft client = Minecraft.getInstance();
        if (value != PreferredGraphicsApi.VULKAN || client.options.preferredGraphicsBackend().get() == value) {
            accept.run();
            return;
        }
        Screen parent = client.gui.screen();
        client.gui.setScreen(new ConfirmScreen(confirmed -> {
            client.gui.setScreen(parent);
            if (confirmed) {
                acknowledge();
                accept.run();
            } else {
                cancel.run();
            }
        }, title(),
            Component.translatable("iris.vulkan.warning.switch"),
            Component.translatable("iris.vulkan.warning.select"), Component.translatable("gui.cancel")));
    }

    public static void tick(Minecraft client) {
        if (shownThisSession || Iris.getIrisConfig() == null || Iris.getIrisConfig().hasSeenVulkanWarning()
                || client.gui.overlay() != null
                || !IrisBackend.isVulkan(RenderSystem.getDevice())) return;
        // Quick Play can enter a world without ever displaying the title screen.
        if (!(client.gui.screen() instanceof TitleScreen)
                && !(client.level != null && client.gui.screen() == null)) return;
        shownThisSession = true;
        Screen parent = client.gui.screen();
        client.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (!confirmed) Iris.getIrisConfig().setShadersEnabled(false);
            // reload() reads the persisted settings, so save the choice first.
            acknowledge();
            if (!confirmed) {
                try { Iris.reload(); }
                catch (Exception failure) { Iris.logger.error("Could not disable experimental Vulkan shaders", failure); }
            }
            client.gui.setScreen(parent);
        }, title(),
            Component.translatable("iris.vulkan.warning.startup"),
            Component.translatable("gui.continue"), Component.translatable("iris.vulkan.warning.disable")));
    }

    private static Component title() {
        return Component.literal("\u26A0 ").withStyle(ChatFormatting.GOLD)
            .append(Component.translatable("iris.vulkan.warning.title").withStyle(ChatFormatting.RED))
            .append(Component.literal(" \u26A0").withStyle(ChatFormatting.GOLD));
    }

    private static void acknowledge() {
        var config = Iris.getIrisConfig();
        if (config == null) return;
        config.markVulkanWarningSeen();
        try { config.save(); }
        catch (java.io.IOException failure) { Iris.logger.warn("Could not save Vulkan warning acknowledgment", failure); }
    }
}
