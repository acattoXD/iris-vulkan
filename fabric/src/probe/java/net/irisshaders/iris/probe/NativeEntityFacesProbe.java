package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
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

/** Named mobs and real falling-block entities in a disposable visible world. No production hooks. */
public final class NativeEntityFacesProbe {
    private static final String[] LABELS = {"entity-tags-sky", "entity-tags-water", "entity-faces-upper",
            "entity-faces-lower-opposite", "entity-faces-upper-no-blob", "entity-faces-falling-stepped"};
    private static final Vec3[] POSITIONS = {new Vec3(0.5, 64, 9.5), new Vec3(0.5, 73, 9.5),
            new Vec3(11.5, 73, 10.5), new Vec3(-7.5, 64, -9.5),
            new Vec3(11.5, 73, 10.5), new Vec3(11.5, 73, 10.5)};
    private static NativeEntityFacesProbe active;
    private final Minecraft client;
    private final Path run, evidence;
    private final AtomicBoolean screenshotPending = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Map<String, Object>> captures = new ArrayList<>();
    private final Map<String, Long> draws = new LinkedHashMap<>();
    private final Map<String, Object> contracts = new LinkedHashMap<>();
    private final List<FallingBlockEntity> falling = new ArrayList<>();
    private CompletableFuture<Void> serverWork;
    private boolean captured, collecting;
    private int step, settledFrames, collectedFrames;
    private long started;

    public NativeEntityFacesProbe(Minecraft client, Path run) throws Exception {
        this.client = client; this.run = run; evidence = run.resolve("evidence");
        assertIsolated();
        Files.createDirectories(evidence);
        client.options.setCameraType(CameraType.FIRST_PERSON);
        client.options.fov().set(70);
        // F1 suppresses entity name tags as well as the HUD.
        if (client.gui.hud.isHidden()) client.gui.hud.toggle();
        active = this;
        begin();
    }

    private void assertIsolated() {
        var server = client.getSingleplayerServer();
        if (!Boolean.getBoolean("iris.vulkan.probe.entityFaces") || server == null || client.player == null
                || !server.getWorldData().getLevelName().startsWith("iris_native_probe_")
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)
                || !RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan"))
            throw new IllegalStateException("Entity-face fixture requires a new disposable isolated Vulkan world");
    }

    public boolean frame() throws Exception {
        assertIsolated();
        if (failure.get() != null) throw new IllegalStateException("Entity-face capture failed", failure.get());
        if (System.nanoTime() - started > 75_000_000_000L)
            throw new IllegalStateException("Entity-face fixture timed out: " + state());
        if (screenshotPending.get() || !serverWork.isDone()) return false;
        serverWork.join();
        if (captured) {
            if (++step == LABELS.length) {
                write(evidence.resolve("entity-faces-report.json"), Map.of("captures", captures,
                        "scope", "Named mobs over sky/water; sand/gravel falling entities and placed comparisons; opposite camera angles; vanilla entity-shadow toggle; five actual physics ticks",
                        "visualVerificationRequired", true));
                active = null;
                return true;
            }
            begin();
            return false;
        }
        if (client.gui.screen() != null || client.gui.overlay() != null || !worldMatches()) {
            collecting = false; settledFrames = collectedFrames = 0;
            return false;
        }
        setPose();
        if (!collecting) {
            if (++settledFrames < 120) return false;
            draws.clear(); contracts.clear(); collectedFrames = 0; collecting = true;
            return false;
        }
        if (++collectedFrames < 40) return false;
        // Vanilla's PreparedTextBuilder can put its white-glyph background in
        // the same TEXT atlas batch. TEXT_BG is optional diagnostic coverage.
        if (step <= 1 && draws.getOrDefault("TEXT", 0L) == 0)
            throw new IllegalStateException("Missing completed world name-tag text draws: " + state());
        if (step >= 2 && draws.getOrDefault("TERRAIN_SOLID", 0L) == 0)
            throw new IllegalStateException("No completed falling-block solid draws: " + state());
        if (step >= 4 && draws.getOrDefault("ENTITY_SHADOW", 0L) != 0)
            throw new IllegalStateException("Vanilla entity-shadow draws persisted with the option off: " + state());
        collecting = false;
        NativePackResourceEvidence.write(client, run, LABELS[step]);
        var snapshot = state(); captures.add(snapshot);
        write(evidence.resolve(LABELS[step] + "-state.json"), snapshot);
        String label = LABELS[step];
        screenshotPending.set(true);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) { image.writeToFile(evidence.resolve(label + ".png")); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { screenshotPending.set(false); }
        });
        captured = true;
        Iris.logger.info("Entity-face probe captured {}: {}", label, draws);
        return false;
    }

    private void begin() {
        captured = collecting = false; settledFrames = collectedFrames = 0;
        started = System.nanoTime();
        client.options.entityShadows().set(step < 4);
        serverWork = new CompletableFuture<>();
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = server.overworld();
                var source = server.createCommandSourceStack().withSuppressedOutput();
                if (step == 0) {
                    server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                    server.getGameRules().set(GameRules.MOB_DROPS, false, server);
                    server.tickRateManager().setFrozen(true);
                    for (String command : List.of("time set 4000", "weather clear",
                            "fill -18 64 -22 18 77 20 air", "fill -18 63 -22 18 63 20 smooth_stone",
                            "fill -12 63 -20 12 63 -2 water", "clear @p"))
                        server.getCommands().performPrefixedCommand(source, command);
                    var oldEntities = new ArrayList<net.minecraft.world.entity.Entity>();
                    for (var entity : level.getAllEntities()) oldEntities.add(entity);
                    for (var entity : oldEntities)
                        if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) entity.discard();
                    for (var pos : POSITIONS) level.setBlock(BlockPos.containing(pos).below(), Blocks.BARRIER.defaultBlockState(), 3);
                    var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                    var pig = EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
                    if (cow == null || pig == null) throw new IllegalStateException("Cannot create named fixture mobs");
                    cow.setPos(-3.5, 64, -6.5); pig.setPos(3.5, 64, -6.5);
                    cow.setNoAi(true); pig.setNoAi(true); cow.setNoGravity(true); pig.setNoGravity(true);
                    cow.setCustomName(Component.literal("SKY / WATER NAME A")); pig.setCustomName(Component.literal("SKY / WATER NAME B"));
                    cow.setCustomNameVisible(true); pig.setCustomNameVisible(true);
                    level.addFreshEntity(cow); level.addFreshEntity(pig);
                    falling.add(FallingBlockEntity.fall(level, new BlockPos(-2, 68, 0), Blocks.SAND.defaultBlockState()));
                    falling.add(FallingBlockEntity.fall(level, new BlockPos(1, 68, 0), Blocks.GRAVEL.defaultBlockState()));
                    for (var entity : falling) entity.setNoGravity(true);
                    level.setBlock(new BlockPos(4, 67, 0), Blocks.BARRIER.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(7, 67, 0), Blocks.BARRIER.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(4, 68, 0), Blocks.SAND.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(7, 68, 0), Blocks.GRAVEL.defaultBlockState(), 3);
                    // Let initial tracking/chunk updates advance before the static capture interval.
                    if (!server.tickRateManager().stepGameIfPaused(2)) throw new IllegalStateException("Cannot settle fixture entities");
                }
                var pos = POSITIONS[step];
                server.getCommands().performPrefixedCommand(source, "tp @p " + pos.x + " " + pos.y + " " + pos.z);
                if (step == 5) {
                    for (var entity : falling) entity.setNoGravity(false);
                    if (!server.tickRateManager().stepGameIfPaused(5)) throw new IllegalStateException("Cannot step falling physics");
                }
                server.forceGameTimeSynchronization();
                serverWork.complete(null);
            } catch (Throwable error) { serverWork.completeExceptionally(error); }
        });
    }

    private boolean worldMatches() {
        if (client.level == null || client.player == null || client.player.position().distanceToSqr(POSITIONS[step]) > 0.05) return false;
        if (!client.level.getBlockState(new BlockPos(4, 68, 0)).is(Blocks.SAND)
                || !client.level.getBlockState(new BlockPos(7, 68, 0)).is(Blocks.GRAVEL)) return false;
        int named = 0, blocks = 0;
        for (var entity : client.level.entitiesForRendering()) {
            if (entity.hasCustomName()) named++;
            if (entity instanceof FallingBlockEntity block) {
                blocks++;
                if (step == 5 && block.getY() >= 67.9) return false;
            }
        }
        return named >= 2 && blocks == 2;
    }

    private void setPose() {
        Vec3 target = step <= 1 ? new Vec3(0, 66, -6.5) : new Vec3(2.5, step == 5 ? 67.8 : 68.5, 0.5);
        Vec3 delta = target.subtract(client.player.getEyePosition());
        client.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
        client.player.setDeltaMovement(Vec3.ZERO); client.player.setOldPosAndRot();
    }

    /** Main-view completed draws only. GUI text and pack shadow passes cannot satisfy coverage. */
    public static void drawn(PreparedRenderType prepared, int indexCount) {
        var probe = active;
        if (probe == null || !probe.collecting || indexCount <= 0 || IrisVulkanShadowRenderer.active()) return;
        RenderPipeline pipeline = prepared.pipeline();
        String category = pipeline == RenderPipelines.ENTITY_SHADOW ? "ENTITY_SHADOW"
                : pipeline == RenderPipelines.SOLID_TERRAIN || pipeline == RenderPipelines.SOLID_BLOCK ? "TERRAIN_SOLID"
                : pipeline == RenderPipelines.TEXT_BACKGROUND || pipeline == RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH ? "TEXT_BG"
                : pipeline == RenderPipelines.TEXT || pipeline == RenderPipelines.TEXT_SEE_THROUGH
                    || pipeline == RenderPipelines.TEXT_POLYGON_OFFSET || pipeline == RenderPipelines.TEXT_GRAYSCALE
                    || pipeline == RenderPipelines.TEXT_GRAYSCALE_SEE_THROUGH ? "TEXT" : null;
        if (category == null) return;
        probe.draws.merge(category, 1L, Long::sum);
        var contract = new LinkedHashMap<String, Object>();
        contract.put("category", category); contract.put("vertexFormat", String.valueOf(pipeline.getVertexFormatBinding(0)));
        contract.put("indexCount", indexCount); contract.put("samplers", prepared.textures().stream().map(PreparedRenderType.Texture::name).toList());
        if ((Object) prepared instanceof IrisVulkanEntityContext.MaterialDraw stored) contract.put("material", stored.iris$material());
        probe.contracts.put(pipeline.getLocation().toString(), contract);
    }

    private Map<String, Object> state() {
        var result = new LinkedHashMap<String, Object>();
        result.put("label", LABELS[step]); result.put("pack", Iris.getCurrentPackName());
        result.put("profile", System.getProperty("iris.vulkan.probe.expectedProfile", "pack default"));
        result.put("cameraPosition", client.player.position()); result.put("yawPitch", List.of(client.player.getYRot(), client.player.getXRot()));
        result.put("settledFrames", settledFrames); result.put("collectedFrames", collectedFrames);
        result.put("completedDraws", new LinkedHashMap<>(draws)); result.put("drawContracts", new LinkedHashMap<>(contracts));
        result.put("entityShadowsOption", client.options.entityShadows().get());
        result.put("pipelineDisablesVanillaEntityShadows", Iris.getPipelineManager().getPipelineNullable().shouldDisableVanillaEntityShadows());
        result.put("worldFrozen", client.getSingleplayerServer().tickRateManager().isFrozen());
        result.put("actualFallingPhysicsTicksRequested", step == 5 ? 5 : 0);
        var entities = new ArrayList<Map<String, Object>>();
        if (client.level != null) for (var entity : client.level.entitiesForRendering()) {
            if (!entity.hasCustomName() && !(entity instanceof FallingBlockEntity)) continue;
            var info = new LinkedHashMap<String, Object>();
            info.put("type", entity.getType().toString()); info.put("name", entity.getName().getString());
            info.put("position", entity.position());
            if (entity instanceof FallingBlockEntity block) info.put("blockState", block.getBlockState().toString());
            entities.add(info);
        }
        result.put("fixtureEntities", entities);
        result.put("placedComparisons", List.of("sand at 4,68,0", "gravel at 7,68,0"));
        result.put("visualVerificationRequired", true);
        result.put("shadowScope", "ENTITY_SHADOW counts only vanilla entity-shadow quads; the option does not disable shader-pack shadow maps");
        result.put("textBackgroundScope", "Name-tag backgrounds may use a white glyph in the TEXT atlas batch; TEXT_BG is recorded when present but is not required");
        return result;
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }
}
