package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.screen.ExperimentalVulkanWarning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** UI-only controller in the disposable probe JAR; never creates or opens a world. */
public final class NativeVulkanWarningProbe {
    private static final Identifier GRAPHICS_API = Identifier.parse("sodium:general.graphics_api");
    private static final long START = System.nanoTime();
    private static final AtomicBoolean CAPTURE_PENDING = new AtomicBoolean();
    private static final AtomicReference<Throwable> CAPTURE_FAILURE = new AtomicReference<>();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static int stage, frames;
    private static String queuedCapture;
    private static int captureFrames;
    private static boolean done;
    private static PreferredGraphicsApi initialBackend;

    private NativeVulkanWarningProbe() { }

    public static void frame(Minecraft client) {
        if (done) return;
        Path run = client.gameDirectory.toPath().toAbsolutePath().normalize();
        String mode = System.getProperty("iris.uiSmoke", "");
        try {
            Path authorized = Path.of(System.getProperty("iris.uiSmoke.runDir", "")).toAbsolutePath().normalize();
            require(run.equals(authorized) && run.getFileName().toString().contains("ui-warning"), "UI smoke must use its explicit isolated ui-warning directory");
            require(List.of("startup-accept", "startup-decline", "opengl-selection").contains(mode), "Unknown UI smoke mode");
            require(client.level == null, "UI smoke must remain at the menu");
            require(System.nanoTime() - START < 120_000_000_000L, "UI smoke timed out at stage " + stage);
            if (CAPTURE_FAILURE.get() != null) throw new IllegalStateException("UI screenshot failed", CAPTURE_FAILURE.get());
            if (CAPTURE_PENDING.get() || client.gui.overlay() != null || Iris.getIrisConfig() == null) return;
            if (queuedCapture != null) {
                // setScreen changes the next frame, not the framebuffer just presented.
                if (++captureFrames < 3) return;
                performCapture(client, run, queuedCapture);
                queuedCapture = null;
                return;
            }
            frames++;
            if (frames < 30) return;
            if (stage == 0) {
                require(!Iris.getIrisConfig().hasSeenVulkanWarning(), "Fresh UI smoke requires vulkanWarningVersion=0");
                REPORT.put("mode", mode);
                REPORT.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
                REPORT.put("productionClasses", classEvidence());
                if (mode.equals("opengl-selection")) {
                    require(RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("OpenGL"), "OpenGL selection test requires actual OpenGL");
                    require(client.gui.screen() instanceof TitleScreen, "OpenGL startup must not display Vulkan disclosure");
                    initialBackend = client.options.preferredGraphicsBackend().get();
                    REPORT.put("openGlStartupNoAlert", true);
                    capture(client, run, "opengl-no-warning.png");
                } else {
                    require(RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan"), "Startup warning test requires actual Vulkan");
                    verifyWarning(client, false);
                    REPORT.put("shadersBefore", Iris.getIrisConfig().areShadersEnabled());
                    capture(client, run, mode + ".png");
                }
                next();
            } else if (stage == 1) {
                if (mode.equals("opengl-selection")) {
                    client.gui.setScreen(VideoSettingsScreen.createScreen(client.gui.screen()));
                    selectVulkanThroughSodium();
                    verifyWarning(client, true);
                    require(client.options.preferredGraphicsBackend().get() == initialBackend, "Opening confirmation must not change engine backend");
                    capture(client, run, "sodium-vulkan-cancel.png");
                } else {
                    press(client, mode.equals("startup-accept") ? "gui.continue" : "iris.vulkan.warning.disable");
                    require(client.gui.screen() instanceof TitleScreen, "Startup response must return to the title screen");
                }
                next();
            } else if (stage == 2) {
                if (mode.equals("opengl-selection")) {
                    // Escape is the real ConfirmScreen negative action.
                    client.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                    require(client.gui.screen() instanceof VideoSettingsScreen, "Cancel should return to the existing Sodium screen");
                    require(client.options.preferredGraphicsBackend().get() == initialBackend, "Cancel changed engine backend");
                    require(!Iris.getIrisConfig().hasSeenVulkanWarning(), "Cancel must not acknowledge Vulkan selection");
                    require(ConfigManager.CONFIG.readEnumOption(GRAPHICS_API, PreferredGraphicsApi.class, true) == initialBackend, "Cancel left Sodium's applied cache on Vulkan");
                    ConfigManager.CONFIG.applyOption(GRAPHICS_API);
                    require(!(client.gui.screen() instanceof ConfirmScreen), "Cancel caused the confirmation to reopen on Apply");
                    REPORT.put("cancelPreservedBackendAndClearedPendingChange", true);
                    capture(client, run, "sodium-after-cancel.png");
                    next();
                } else {
                    verifyPersisted(client, mode.equals("startup-accept") ? (boolean) REPORT.get("shadersBefore") : false);
                    ExperimentalVulkanWarning.tick(client);
                    require(client.gui.screen() instanceof TitleScreen, "Acknowledged startup warning looped");
                    REPORT.put("startupResponsePersisted", true);
                    capture(client, run, mode + "-after.png");
                    stage = 6; frames = 0;
                }
            } else if (stage == 3) {
                selectVulkanThroughSodium();
                verifyWarning(client, true);
                capture(client, run, "sodium-vulkan-accept.png");
                next();
            } else if (stage == 4) {
                press(client, "iris.vulkan.warning.select");
                require(client.gui.screen() instanceof VideoSettingsScreen, "Accept must return to Sodium settings");
                require(client.options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN, "Accepted backend not applied");
                verifyPersisted(client, Iris.getIrisConfig().areShadersEnabled());
                String backendLine = Files.readAllLines(run.resolve("options.txt")).stream().filter(line -> line.startsWith("preferredGraphicsBackend:")).findFirst().orElseThrow();
                require(backendLine.toLowerCase().contains("vulkan"), "Accepted backend was not saved after Sodium's earlier storage flush");
                REPORT.put("acceptedBackendPersisted", true);
                REPORT.put("savedBackend", backendLine);
                capture(client, run, "sodium-after-accept.png");
                stage = 6; frames = 0;
            } else if (stage == 6) {
                REPORT.put("passed", true);
                writeResult(client, run, null);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            writeResult(client, run, failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void selectVulkanThroughSodium() {
        var option = ConfigManager.CONFIG.getOption(GRAPHICS_API);
        require(option instanceof StatefulOption<?>, "Sodium graphics API option unavailable");
        ((StatefulOption<PreferredGraphicsApi>) option).modifyValue(PreferredGraphicsApi.VULKAN);
        ConfigManager.CONFIG.applyOption(GRAPHICS_API);
    }

    private static void verifyWarning(Minecraft client, boolean switching) {
        require(client.gui.screen() instanceof ConfirmScreen, "Expected visible production ConfirmScreen");
        String text = client.gui.screen().getNarrationMessage().getString();
        require(text.contains("experimental") && text.contains("unofficial") && text.contains("crash"), "Disclosure text missing key experimental information: " + text);
        if (switching) require(text.contains("restart"), "Backend-change disclosure must explain restart");
        REPORT.put(switching ? "selectionWarningText" : "startupWarningText", text);
    }

    private static void press(Minecraft client, String translationKey) {
        String label = Component.translatable(translationKey).getString();
        Button button = client.gui.screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(candidate -> candidate.getMessage().getString().equals(label)).findFirst().orElseThrow();
        require(button.active && button.visible, "Confirmation button must be visible and active");
        button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
    }

    private static void verifyPersisted(Minecraft client, boolean shaders) throws Exception {
        Path configPath = client.gameDirectory.toPath().resolve("config/iris.properties");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(configPath)) { properties.load(in); }
        require(properties.getProperty("vulkanWarningVersion", "0").equals("1"), "Warning acknowledgment not saved");
        require(properties.getProperty("enableShaders").equals(Boolean.toString(shaders)), "Shader choice not saved");
        var loaded = new net.irisshaders.iris.config.IrisConfig(configPath, configPath.resolveSibling("iris-excluded.json"));
        loaded.load();
        require(loaded.hasSeenVulkanWarning() && loaded.areShadersEnabled() == shaders, "Saved warning/shader choice did not survive config reload");
    }

    private static Map<String, Object> classEvidence() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Class<?> type : List.of(ExperimentalVulkanWarning.class, net.irisshaders.iris.config.IrisConfig.class, net.irisshaders.iris.compat.sodium.config.IrisConfig.class)) {
            var url = type.getClassLoader().getResource(type.getName().replace('.', '/') + ".class");
            try (var in = url.openStream()) {
                result.put(type.getName(), Map.of("source", url.toString(), "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))));
            }
        }
        return result;
    }

    private static void capture(Minecraft client, Path run, String name) {
        queuedCapture = name;
        captureFrames = 0;
    }

    private static void performCapture(Minecraft client, Path run, String name) throws Exception {
        Files.createDirectories(run.resolve("evidence"));
        CAPTURE_PENDING.set(true);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try { image.writeToFile(run.resolve("evidence").resolve(name)); }
            catch (Throwable failure) { CAPTURE_FAILURE.set(failure); }
            finally { image.close(); CAPTURE_PENDING.set(false); }
        });
    }

    private static void next() { stage++; frames = 0; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void writeResult(Minecraft client, Path run, Throwable failure) {
        done = true;
        try {
            REPORT.put("passed", failure == null);
            if (failure != null) REPORT.put("failure", failure.toString());
            Files.createDirectories(run.resolve("evidence"));
            Files.writeString(run.resolve("evidence/ui-warning-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            Files.writeString(run.resolve("ui-warning-result.txt"), failure == null ? "IRIS_UI_WARNING_PASS\n" : "IRIS_UI_WARNING_FAIL\n" + failure);
        } catch (Exception error) { error.printStackTrace(); }
        client.stop();
    }
}
