package net.irisshaders.iris.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Opens the disposable scene for manual bug reports and then releases all control. */
public final class InteractiveWorld {
    private static int stage, frames;
    private static CompletableFuture<Void> setup;
    private InteractiveWorld() {}

    public static void frame(Minecraft client) {
        if (stage == 3) return;
        Path run = Path.of(System.getProperty("iris.vulkan.probe.runDir")).toAbsolutePath().normalize();
        try {
            if (!run.equals(client.gameDirectory.toPath().toAbsolutePath().normalize())) throw new IllegalStateException("Wrong interactive test directory");
            client.getWindow().setTitle("Iris VULKAN — " + Iris.getCurrentPackName() + " — Manual bug testing");
            if (client.gui.overlay() != null) return;
            if (stage == 0) {
                if (++frames < 45 || !(client.gui.screen() instanceof TitleScreen)) return;
                if (!RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan")) throw new IllegalStateException("Expected actual Vulkan backend");
                if (Iris.getCurrentPack().isEmpty()) throw new IllegalStateException("The selected shaderpack was not loaded");
                String world = System.getProperty("iris.vulkan.probe.openWorld");
                if (world == null || !world.matches("iris_native_probe_[0-9]+") || !Files.isDirectory(run.resolve("saves").resolve(world))) throw new IllegalStateException("Test world is missing");
                stage = 1;
                client.createWorldOpenFlows().openWorld(world, () -> { stage = 3; Iris.logger.error("Interactive test world opening was cancelled"); });
            } else if (stage == 1) {
                if (client.level == null || client.player == null || client.gui.screen() != null) return;
                var server = client.getSingleplayerServer();
                if (server == null || !server.getWorldData().getLevelName().startsWith("iris_native_probe_")) throw new IllegalStateException("Expected disposable test world");
                var playerId = client.player.getUUID();
                setup = new CompletableFuture<>();
                server.execute(() -> {
                    try {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "tick unfreeze");
                        server.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
                        server.getGameRules().set(GameRules.ADVANCE_WEATHER, true, server);
                        server.getGameRules().set(GameRules.SPAWN_MOBS, true, server);
                        server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3, server);
                        var clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
                        server.clockManager().setPaused(clocks.getOrThrow(WorldClocks.OVERWORLD), false);
                        server.clockManager().setPaused(clocks.getOrThrow(WorldClocks.THE_END), false);
                        server.forceGameTimeSynchronization();
                        for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) if (entity instanceof Mob mob) { mob.setNoAi(false); mob.setNoGravity(false); }
                        var player = server.getPlayerList().getPlayer(playerId);
                        player.setGameMode(GameType.CREATIVE);
                        player.setNoGravity(false);
                        player.getAbilities().flying = false;
                        player.onUpdateAbilities();
                        player.teleportTo(server.getLevel(Level.OVERWORLD), 0.5, 64.0, 15.5, Set.of(), 180f, 17f, true);
                        setup.complete(null);
                    } catch (Throwable failure) { setup.completeExceptionally(failure); }
                });
                stage = 2;
            } else if (stage == 2 && setup.isDone()) {
                setup.join();
                if (client.gui.hud.isHidden()) client.gui.hud.toggle();
                client.player.setNoGravity(false);
                Files.writeString(run.resolve("interactive-ready.txt"), "READY\nBackend=" + RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\nWorld=" + serverWorldName(client) + "\nControls=manual\nAutoStop=false\n");
                Iris.logger.info("INTERACTIVE_VULKAN_READY: {}; controls released; automatic shutdown disabled.", Iris.getCurrentPackName());
                NativePresentationReport.write(run.resolve("presentation-report.json"), client);
                stage = 3;
            }
        } catch (Throwable failure) {
            stage = 3;
            Iris.logger.error("Interactive Vulkan test startup failed", failure);
            try { Files.writeString(run.resolve("interactive-ready.txt"), "FAILED\n" + failure); } catch (Exception ignored) {}
        }
    }
    private static String serverWorldName(Minecraft client) { return client.getSingleplayerServer().getWorldData().getLevelName(); }
}
