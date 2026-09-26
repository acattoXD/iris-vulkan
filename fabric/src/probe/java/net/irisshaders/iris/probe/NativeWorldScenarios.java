package net.irisshaders.iris.probe;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisNativeVulkan;
import net.irisshaders.iris.vulkan.IrisVulkanStorageResources;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A bounded sequence for the disposable native-world probe. The caller owns
 * screenshots, readbacks, logs, and verdicts; READY only means the requested
 * world/camera/window state has settled, not that its image is correct.
 */
public final class NativeWorldScenarios {
    public enum Scenario {
        DAY("day"), NIGHT("night"), RAIN("rain"), UNDERWATER("underwater"),
        NETHER("nether"), END("end"), RETURN_OVERWORLD("overworld-return"),
        RESIZE("resized"), QUALITY_HIGH("quality-high"), QUALITY_ULTRA("quality-ultra"),
        RELOAD("shader-reload"), RESTORE("restored");

        private final String label;
        Scenario(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Status { WAITING, READY_TO_CAPTURE, COMPLETE }

    public record Frame(String scenario, Status status, Map<String, Object> evidence) {
        public boolean ready() { return status == Status.READY_TO_CAPTURE; }
        public boolean complete() { return status == Status.COMPLETE; }
    }

    public record CameraPose(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch) { }

    private static final int SETTLED_FRAMES = 120;
    private static final long SETTLED_NANOS = 2_000_000_000L;
    private static final long SCENARIO_TIMEOUT_NANOS = 90_000_000_000L;
    private final Minecraft client;
    private final Path run;
    private final UUID playerId;
    private final List<Scenario> steps;
    private final CameraPose baseline;
    private final int originalWindowWidth, originalWindowHeight;
    private final boolean originalFullscreen;
    private final Set<ResourceKey<Level>> preparedDimensions = new HashSet<>();
    private CompletableFuture<Void> serverWork;
    private int index, settledFrames;
    private long startedAt, settledAt;
    private boolean clientActionApplied, captureReady, captureAcknowledged;
    private Object packBeforeReload;
    private CameraPose target;

    public NativeWorldScenarios(Minecraft client) {
        this(client, defaultSteps());
    }

    private static List<Scenario> defaultSteps() {
        boolean ultra = Boolean.getBoolean("iris.vulkan.probe.ultra");
        return Arrays.stream(Scenario.values()).filter(step -> ultra || !qualityChange(step)).toList();
    }

    private static boolean qualityChange(Scenario scenario) {
        return scenario == Scenario.QUALITY_HIGH || scenario == Scenario.QUALITY_ULTRA;
    }

    public NativeWorldScenarios(Minecraft client, List<Scenario> steps) {
        this.client = client;
        String configuredRun = System.getProperty("iris.vulkan.probe.runDir");
        if (configuredRun == null || configuredRun.isBlank()) throw new IllegalStateException("Probe run directory is required");
        run = Path.of(configuredRun).toAbsolutePath().normalize();
        assertProbeWorld();
        if (client.level == null || client.player == null) throw new IllegalStateException("Start scenarios after the probe player has joined");
        if (client.level.dimension() != Level.OVERWORLD) throw new IllegalStateException("Start scenarios from the existing Overworld probe scene");
        this.steps = List.copyOf(steps);
        if (!Boolean.getBoolean("iris.vulkan.probe.ultra") && this.steps.stream().anyMatch(NativeWorldScenarios::qualityChange))
            throw new IllegalStateException("Quality scenarios require an explicitly enabled isolated Ultra probe");
        this.playerId = client.player.getUUID();
        this.baseline = new CameraPose(Level.OVERWORLD, client.player.getX(), client.player.getY(), client.player.getZ(),
                client.player.getYRot(), client.player.getXRot());
        originalWindowWidth = client.getWindow().getScreenWidth();
        originalWindowHeight = client.getWindow().getScreenHeight();
        originalFullscreen = client.getWindow().isFullscreen();
    }

    /** Call once per rendered frame, after ordinary pending screenshot work has completed. */
    public synchronized Frame tick() throws IOException {
        assertProbeWorld();
        if (captureAcknowledged) {
            captureAcknowledged = false;
            captureReady = false;
            serverWork = null;
            index++;
        }
        if (index >= steps.size()) return new Frame("complete", Status.COMPLETE, Map.of("capturedScenarios", steps.size()));
        Scenario scenario = steps.get(index);
        if (serverWork == null) begin(scenario);
        if (System.nanoTime() - startedAt > SCENARIO_TIMEOUT_NANOS) {
            throw new IllegalStateException("Scenario " + scenario.label() + " timed out: " + evidence(scenario));
        }
        if (!serverWork.isDone()) return frame(scenario, Status.WAITING);
        serverWork.join(); // Surface server failures to NativeProbe's existing failure handler.
        if (!cameraArrived()) {
            settledFrames = 0;
            settledAt = 0;
            return frame(scenario, Status.WAITING);
        }
        if (!clientActionApplied) {
            applyClientAction(scenario);
            clientActionApplied = true;
        }
        holdCamera();
        if (!stateMatches(scenario)) {
            settledFrames = 0;
            settledAt = 0;
            return frame(scenario, Status.WAITING);
        }
        if (settledAt == 0) settledAt = System.nanoTime();
        settledFrames++;
        captureReady = settledFrames >= SETTLED_FRAMES && System.nanoTime() - settledAt >= SETTLED_NANOS;
        if (captureReady && qualityChange(scenario)) {
            NativeQualityCycle.requireSettled(scenario == Scenario.QUALITY_ULTRA, packBeforeReload);
        }
        return frame(scenario, captureReady ? Status.READY_TO_CAPTURE : Status.WAITING);
    }

    /** Advance only after the caller has saved this scenario's screenshot/readbacks successfully. */
    public synchronized void captureComplete(String scenario) {
        if (!captureReady || index >= steps.size() || !steps.get(index).label().equals(scenario)) {
            throw new IllegalStateException("Capture completion does not match the ready scenario: " + scenario);
        }
        captureAcknowledged = true;
    }

    /** Restore the standalone window if the caller stops early; world fixtures remain disposable. */
    public void restoreWindow() {
        client.getWindow().setWindowed(originalWindowWidth, originalWindowHeight);
        if (originalFullscreen && !client.getWindow().isFullscreen()) client.getWindow().toggleFullScreen();
    }

    private void begin(Scenario scenario) {
        startedAt = System.nanoTime();
        settledAt = 0;
        settledFrames = 0;
        clientActionApplied = false;
        packBeforeReload = null;
        target = switch (scenario) {
            case UNDERWATER -> new CameraPose(Level.OVERWORLD, -9.5, 64.0, 1.5, 180.0f, 0.0f);
            case NETHER -> new CameraPose(Level.NETHER, 0.5, 81.0, 8.5, 180.0f, 15.0f);
            case END -> new CameraPose(Level.END, 0.5, 81.0, 8.5, 180.0f, 15.0f);
            default -> baseline;
        };
        MinecraftServer server = client.getSingleplayerServer();
        serverWork = new CompletableFuture<>();
        server.execute(() -> {
            try {
                prepareServerState(server, scenario, target);
                serverWork.complete(null);
            } catch (Throwable failure) {
                serverWork.completeExceptionally(failure);
            }
        });
    }

    private void prepareServerState(MinecraftServer server, Scenario scenario, CameraPose pose) {
        ServerLevel level = server.getLevel(pose.dimension());
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (level == null || player == null) throw new IllegalStateException("Missing scenario dimension or local player");
        var rules = server.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);

        var clocks = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
        long time = scenario == Scenario.NIGHT ? 18_000L : 4_000L;
        var overworldClock = clocks.getOrThrow(WorldClocks.OVERWORLD);
        server.clockManager().setTotalTicks(overworldClock, time);
        server.clockManager().setPaused(overworldClock, true);
        var endClock = clocks.getOrThrow(WorldClocks.THE_END);
        server.clockManager().setTotalTicks(endClock, 4_000L);
        server.clockManager().setPaused(endClock, true);
        server.forceGameTimeSynchronization();

        boolean raining = scenario == Scenario.RAIN;
        server.setWeatherParameters(raining ? 0 : 12_000, 12_000, raining, false);
        for (ServerLevel world : server.getAllLevels()) {
            world.setRainLevel(raining ? 1.0f : 0.0f);
            world.setThunderLevel(0.0f);
            freezeMobs(world);
        }
        if (pose.dimension() != Level.OVERWORLD && preparedDimensions.add(pose.dimension())) {
            prepareDimensionFixture(level);
        }
        if (scenario == Scenario.UNDERWATER) prepareWaterFixture(level);
        player.setGameMode(GameType.CREATIVE);
        player.setNoGravity(true);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setDeltaMovement(Vec3.ZERO);
        if (!player.teleportTo(level, pose.x(), pose.y(), pose.z(), Set.of(), pose.yaw(), pose.pitch(), true)) {
            throw new IllegalStateException("Could not teleport the probe player for " + scenario.label());
        }
        freezeMobs(level);
    }

    private void applyClientAction(Scenario scenario) throws IOException {
        client.level.setRainLevel(scenario == Scenario.RAIN ? 1.0f : 0.0f);
        client.level.setThunderLevel(0.0f);
        if (scenario == Scenario.RESIZE) {
            client.getWindow().setWindowed(resizedWidth(), resizedHeight());
        } else if (qualityChange(scenario)) {
            packBeforeReload = Iris.getCurrentPack().orElseThrow();
            NativeQualityCycle.reload(scenario == Scenario.QUALITY_ULTRA);
        } else if (scenario == Scenario.RELOAD) {
            packBeforeReload = Iris.getCurrentPack().orElseThrow();
            Iris.reload();
        } else if (scenario == Scenario.RESTORE) {
            restoreWindow();
        }
    }

    private boolean cameraArrived() {
        if (client.level == null || client.player == null || client.level.dimension() != target.dimension()) return false;
        return client.player.distanceToSqr(target.x(), target.y(), target.z()) < 0.25
                && client.level.hasChunkAt(BlockPos.containing(target.x(), target.y(), target.z()));
    }

    private void holdCamera() {
        client.player.setYRot(target.yaw());
        client.player.setXRot(target.pitch());
        client.player.setOldPosAndRot();
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setNoGravity(true);
        client.player.getAbilities().flying = true;
    }

    private boolean stateMatches(Scenario scenario) {
        if (client.gui.overlay() != null || client.gui.screen() != null || Iris.getCurrentPack().isEmpty()) return false;
        if (scenario == Scenario.UNDERWATER && client.gameRenderer.mainCamera().getFluidInCamera() != FogType.WATER) return false;
        if (scenario != Scenario.UNDERWATER && client.gameRenderer.mainCamera().getFluidInCamera() != FogType.NONE) return false;
        if (scenario == Scenario.RAIN && client.level.getRainLevel(1.0f) < 0.99f) return false;
        if (scenario != Scenario.RAIN && client.level.getRainLevel(1.0f) > 0.01f) return false;
        long expectedTime = scenario == Scenario.NIGHT ? 18_000L : 4_000L;
        if (client.level.dimension() == Level.OVERWORLD && Math.floorMod(client.level.getDefaultClockTime(), 24_000L) != expectedTime) return false;
        if (scenario == Scenario.RESIZE && (client.getWindow().getScreenWidth() != resizedWidth()
                || client.getWindow().getScreenHeight() != resizedHeight())) return false;
        if (scenario == Scenario.RESTORE && !originalFullscreen && (client.getWindow().getScreenWidth() != originalWindowWidth
                || client.getWindow().getScreenHeight() != originalWindowHeight)) return false;
        if (scenario == Scenario.RESTORE && client.getWindow().isFullscreen() != originalFullscreen) return false;
        if (scenario == Scenario.RELOAD && Iris.getCurrentPack().orElse(null) == packBeforeReload) return false;
        var renderTarget = client.gameRenderer.mainRenderTarget();
        return renderTarget.width == client.getWindow().getWidth() && renderTarget.height == client.getWindow().getHeight();
    }

    private static void freezeMobs(ServerLevel level) {
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Mob mob) {
                mob.setNoAi(true);
                mob.setNoGravity(true);
                mob.setDeltaMovement(Vec3.ZERO);
                mob.setYRot(135.0f);
                mob.setYHeadRot(135.0f);
                mob.setYBodyRot(135.0f);
            }
        }
    }

    private static void prepareDimensionFixture(ServerLevel level) {
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                level.setBlock(new BlockPos(x, 80, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                for (int y = 81; y <= 88; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        for (int y = 81; y <= 85; y++) {
            level.setBlock(new BlockPos(-3, y, -2), Blocks.OAK_LOG.defaultBlockState(), 3);
            level.setBlock(new BlockPos(3, y, -2), Blocks.QUARTZ_BLOCK.defaultBlockState(), 3);
            level.setBlock(new BlockPos(6, y, -2), Blocks.STAINED_GLASS.white().defaultBlockState(), 3);
        }
        level.setBlock(new BlockPos(1, 81, 1), Blocks.CHEST.defaultBlockState(), 3);
        EntityTypes.COW.spawn(level, new BlockPos(-4, 81, 3), EntitySpawnReason.COMMAND);
        EntityTypes.PIG.spawn(level, new BlockPos(4, 81, 3), EntitySpawnReason.COMMAND);
    }

    private static void prepareWaterFixture(ServerLevel level) {
        for (int x = -14; x <= -5; x++) {
            for (int z = -5; z <= 5; z++) {
                level.setBlock(new BlockPos(x, 62, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                for (int y = 63; y <= 67; y++) {
                    boolean boundary = x == -14 || x == -5 || z == -5 || z == 5;
                    level.setBlock(new BlockPos(x, y, z), (boundary ? Blocks.GLASS : Blocks.WATER).defaultBlockState(), 3);
                }
            }
        }
    }

    private Frame frame(Scenario scenario, Status status) { return new Frame(scenario.label(), status, evidence(scenario)); }

    private Map<String, Object> evidence(Scenario scenario) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", "Scenario state readiness; screenshot and rendering correctness require caller verification");
        result.put("dimension", client.level == null ? "loading" : client.level.dimension().identifier().toString());
        result.put("expectedDimension", target.dimension().identifier().toString());
        result.put("camera", client.player == null ? "loading" : List.of(client.player.getX(), client.player.getY(), client.player.getZ(), client.player.getYRot(), client.player.getXRot()));
        result.put("expectedCamera", List.of(target.x(), target.y(), target.z(), target.yaw(), target.pitch()));
        result.put("time", client.level == null ? -1L : client.level.getDefaultClockTime());
        result.put("rain", client.level == null ? 0.0f : client.level.getRainLevel(1.0f));
        result.put("eyeMedium", client.level == null ? "loading" : client.gameRenderer.mainCamera().getFluidInCamera().name());
        result.put("windowSize", List.of(client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight()));
        result.put("framebufferSize", List.of(client.getWindow().getWidth(), client.getWindow().getHeight()));
        result.put("settledFrames", settledFrames);
        result.put("elapsedMillis", (System.nanoTime() - startedAt) / 1_000_000L);
        result.put("profile", Iris.getCurrentPack().map(pack -> pack.getProfileInfo()).orElse("unloaded"));
        result.put("computeDispatchCount", IrisNativeVulkan.computeDispatchCount());
        result.put("nativeStorageActive", IrisVulkanStorageResources.active());
        if (scenario == Scenario.RELOAD || qualityChange(scenario))
            result.put("packReplaced", Iris.getCurrentPack().orElse(null) != packBeforeReload);
        if (qualityChange(scenario)) {
            result.put("expectedProfile", scenario == Scenario.QUALITY_ULTRA ? "ULTRA" : "HIGH");
            Iris.getCurrentPack().ifPresent(pack -> {
                result.put("selectedOptions", NativeQualityCycle.selectedOptions(pack));
                result.put("declaredStorageImages", pack.getIrisCustomImages().size());
                result.put("declaredStorageBuffers", pack.getBufferObjects().size());
            });
        }
        return result;
    }

    private int resizedWidth() {
        int width = Math.max(640, originalWindowWidth * 3 / 4);
        return width == originalWindowWidth ? originalWindowWidth + 160 : width;
    }
    private int resizedHeight() {
        int height = Math.max(360, originalWindowHeight * 3 / 4);
        return height == originalWindowHeight ? originalWindowHeight + 90 : height;
    }

    private void assertProbeWorld() {
        if (!client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)
                || client.getSingleplayerServer() == null) {
            throw new IllegalStateException("Scenarios require the configured isolated singleplayer probe world");
        }
        String world = client.getSingleplayerServer().getWorldData().getLevelName();
        if (!world.startsWith("iris_native_probe_")) throw new IllegalStateException("Refusing scenario fixture writes outside a disposable probe world");
    }
}
