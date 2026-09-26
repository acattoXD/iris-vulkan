package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Third-person views on a clear receiver with all original fixture casters removed. */
public final class NativePlayerShadowProbe implements AutoCloseable {
    private final Minecraft client;
    private final Path evidence;
    private final CameraType originalCamera;
    private final boolean originalEntityShadows;
    private final CompletableFuture<Void> prepared = new CompletableFuture<>();
    private final AtomicBoolean screenshotPending = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Map<String, Object>> captures = new ArrayList<>();
    private CompletableFuture<NativeShadowReadback.Report> shadowReadback;
    private CompletableFuture<Void> moved = CompletableFuture.completedFuture(null);
    private int angle;
    private int settledFrames;
    private String waitingFor = "starting";
    private long waitingSince = System.nanoTime();
    private long lastProgress;

    public NativePlayerShadowProbe(Minecraft client, Path run) throws Exception {
        var server = client.getSingleplayerServer();
        if (!Boolean.getBoolean("iris.vulkan.probe.playerShadow") || server == null || client.player == null
                || !server.getWorldData().getLevelName().startsWith("iris_native_probe_")
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)) {
            throw new IllegalStateException("Player shadow probe requires a disposable, isolated world");
        }
        this.client = client;
        this.evidence = run.resolve("evidence");
        Files.createDirectories(evidence);
        originalCamera = client.options.getCameraType();
        originalEntityShadows = client.options.entityShadows().get();
        client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        client.options.entityShadows().set(true);
        server.execute(() -> {
            try {
                var source = server.createCommandSourceStack().withSuppressedOutput();
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                server.getGameRules().set(GameRules.MOB_DROPS, false, server);
                for (String command : List.of("time set 4000", "weather clear",
                        "fill -32 63 -32 32 63 48 smooth_stone",
                        "fill -32 64 -32 32 67 48 air",
                        "fill -32 68 -32 32 71 48 air",
                        "fill -32 72 -32 32 75 48 air",
                        "fill -32 76 -32 32 79 48 air",
                        "kill @e[type=!minecraft:player]",
                        "tp @p 0.0 64.0 32.0 180 34")) {
                    server.getCommands().performPrefixedCommand(source, command);
                }
                server.forceGameTimeSynchronization();
                prepared.complete(null);
            } catch (Throwable error) {
                prepared.completeExceptionally(error);
            }
        });
    }

    public boolean frame() throws Exception {
        if (failure.get() != null) throw new IllegalStateException("Player shadow screenshot failed", failure.get());
        if (!prepared.isDone()) return waitFor("preparing-scene");
        prepared.join();
        if (!moved.isDone()) return waitFor("changing-view");
        moved.join();
        if (client.player == null || client.level == null || client.gui.screen() != null) return waitFor("waiting-for-world");
        if (Math.abs(client.player.getX() - (angle >= 2 ? 3.0 : 0.0)) > 0.1 || Math.abs(client.player.getZ() - 32.0) > 0.1) return waitFor("waiting-for-player-position");
        setPose();
        if (screenshotPending.get()) return waitFor("gpu-capture");
        if (shadowReadback != null) {
            if (!shadowReadback.isDone()) return waitFor("gpu-capture");
            var report = shadowReadback.join();
            if (!report.bothTexturesContainCasters() || !report.depthRangeAndOrderingValid())
                throw new IllegalStateException("Invalid GPU shadow depth readback in player shadow probe");
            shadowReadback = null;
            captures.getLast().put("gpuReadback", report);
            angle++;
            settledFrames = 0;
            if (angle == 4) {
                var summary = new LinkedHashMap<String, Object>();
                summary.put("scope", "Two third-person daylight angles, the player moved three blocks east, then the moved view at earlier sunlight; original fixture geometry and nonplayer entities were removed. GPU statistics do not prove player silhouette correctness");
                summary.put("receiverBounds", List.of(-32, 63, -32, 32, 63, 48));
                summary.put("clearedCasterBounds", List.of(-32, 64, -32, 32, 79, 48));
                summary.put("captures", captures);
                summary.put("visualVerificationRequired", true);
                write(evidence.resolve("player-shadow-report.json"), summary);
                close();
                return true;
            }
            if (angle == 2 || angle == 3) {
                moved = new CompletableFuture<>();
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    try {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                                angle == 2 ? "tp @p 3.0 64.0 32.0 90 42" : "time set 1000");
                        server.forceGameTimeSynchronization();
                        moved.complete(null);
                    } catch (Throwable error) {
                        moved.completeExceptionally(error);
                    }
                });
            }
            setPose();
            return false;
        }
        if (++settledFrames < 90) return waitFor("settling-view-" + angle);
        capture();
        return false;
    }

    private void setPose() {
        float yaw = angle == 0 ? 180.0f : 90.0f;
        client.player.setYRot(yaw);
        client.player.setXRot(angle == 0 ? 34.0f : 42.0f);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setOldPosAndRot();
    }

    private void capture() throws Exception {
        String label = angle == 0 ? "player-shadow-south" : angle == 1 ? "player-shadow-east"
                : angle == 2 ? "player-shadow-east-moved" : "player-shadow-east-moved-early-sun";
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (client.options.getCameraType().isFirstPerson()) throw new IllegalStateException("Player shadow capture must be third person");
        var state = new LinkedHashMap<String, Object>();
        state.put("label", label);
        state.put("cameraType", client.options.getCameraType().name());
        state.put("cameraPosition", List.of(camera.pos.x, camera.pos.y, camera.pos.z));
        state.put("cameraYawPitch", List.of(camera.yRot, camera.xRot));
        state.put("playerPosition", List.of(client.player.getX(), client.player.getY(), client.player.getZ()));
        state.put("playerInvisible", client.player.isInvisible());
        state.put("heldItem", client.player.getMainHandItem().toString());
        state.put("time", client.level.getDefaultClockTime());
        state.put("entityShadowsOption", client.options.entityShadows().get());
        state.put("pipelineDisablesVanillaEntityShadows", Iris.getPipelineManager().getPipelineNullable().shouldDisableVanillaEntityShadows());
        state.put("settledFrames", settledFrames);
        var players = new ArrayList<Map<String, Object>>();
        for (var entity : client.gameRenderer.gameRenderState().levelRenderState.entityRenderStates) {
            if (entity.entityType != EntityTypes.PLAYER) continue;
            players.add(Map.of("position", List.of(entity.x, entity.y, entity.z), "invisible", entity.isInvisible,
                    "width", entity.boundingBoxWidth, "height", entity.boundingBoxHeight,
                    "vanillaShadowRadius", entity.shadowRadius, "vanillaShadowPieces", entity.shadowPieces.size()));
        }
        state.put("mainViewPlayerRenderStates", players);
        for (String matrix : List.of("shadowModelView", "shadowProjection")) {
            var value = IrisVulkanShadowRenderer.uniformMatrix(matrix);
            if (value != null) state.put(matrix, value.get(new float[16]));
        }
        state.put("screenshot", label + ".png");
        state.put("verificationLimit", "Player extraction and nonempty shadow maps do not prove silhouette correctness; inspect all views, movement, and sunlight response");
        captures.add(state);
        write(evidence.resolve(label + "-state.json"), state);
        screenshotPending.set(true);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(evidence.resolve(label + ".png"));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                screenshotPending.set(false);
            }
        });
        shadowReadback = NativeShadowReadback.capture(evidence, label);
        Iris.logger.info("Player shadow probe captured {} after {} settled frames", label, settledFrames);
    }

    private boolean waitFor(String reason) throws Exception {
        long now = System.nanoTime();
        if (!waitingFor.equals(reason)) {
            waitingFor = reason;
            waitingSince = now;
            lastProgress = 0;
        }
        if (now - lastProgress >= 5_000_000_000L) {
            var progress = new LinkedHashMap<String, Object>();
            progress.put("waitingFor", reason);
            progress.put("view", angle);
            progress.put("settledFrames", settledFrames);
            progress.put("waitSeconds", (now - waitingSince) / 1_000_000_000.0);
            progress.put("expectedPlayerPosition", List.of(angle >= 2 ? 3.0 : 0.0, 64.0, 32.0));
            progress.put("actualPlayerPosition", client.player == null ? "unavailable" : List.of(client.player.getX(), client.player.getY(), client.player.getZ()));
            progress.put("screen", client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
            progress.put("screenshotPending", screenshotPending.get());
            progress.put("shadowReadbackPending", shadowReadback != null && !shadowReadback.isDone());
            write(evidence.resolve("player-shadow-progress.json"), progress);
            Iris.logger.info("Player shadow probe progress: {}", progress);
            lastProgress = now;
        }
        if (now - waitingSince > 30_000_000_000L)
            throw new IllegalStateException("Player shadow probe waited 30 seconds for " + reason + "; inspect player-shadow-progress.json");
        return false;
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }

    @Override
    public void close() {
        client.options.setCameraType(originalCamera);
        client.options.entityShadows().set(originalEntityShadows);
    }
}
