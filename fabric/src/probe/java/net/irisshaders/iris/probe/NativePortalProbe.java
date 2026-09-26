package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.IrisVulkanEntityContext;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Actual Ender Eye activation and native portal draws in a newly created disposable world. */
public final class NativePortalProbe {
    private static final String[] LABELS = {"portal-inactive", "portal-activated", "portal-gateway-only",
            "portal-removed", "portal-recreated", "portal-shader-reload"};
    private static final BlockPos LAST_EYE = new BlockPos(-1, 64, 0);
    private static final BlockPos GATEWAY = new BlockPos(4, 65, 2);
    private static NativePortalProbe active;
    private final Minecraft client;
    private final Path run;
    private final Path evidence;
    private final AtomicBoolean screenshotPending = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Map<String, Object>> captures = new ArrayList<>();
    private final Map<String, Long> drawCounts = new LinkedHashMap<>();
    private final Map<String, Object> drawContracts = new LinkedHashMap<>();
    private final Map<String, Long> beamDrawCounts = new LinkedHashMap<>();
    private final Map<String, Object> beamDrawContracts = new LinkedHashMap<>();
    private CompletableFuture<Void> serverWork;
    private Object packBeforeReload;
    private int step, settledFrames, collectedFrames;
    private long stepStarted = System.nanoTime();
    private long portalSubmits, gatewaySubmits, completedDraws;
    private boolean collecting, captured, reloaded;
    private String activationResult;

    public NativePortalProbe(Minecraft client, Path run) throws Exception {
        this.client = client;
        this.run = run;
        evidence = run.resolve("evidence");
        assertIsolated();
        if (!List.of("HIGH", "ULTRA").contains(System.getProperty("iris.vulkan.probe.expectedProfile")))
            throw new IllegalStateException("Portal fixture requires the exact HIGH or ULTRA profile");
        Files.createDirectories(evidence);
        client.options.setCameraType(CameraType.FIRST_PERSON);
        client.options.fov().set(70);
        if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
        active = this;
        begin();
    }

    private void assertIsolated() {
        var server = client.getSingleplayerServer();
        if (!Boolean.getBoolean("iris.vulkan.probe.portal") || server == null || client.player == null
                || !server.getWorldData().getLevelName().startsWith("iris_native_probe_")
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)
                || !RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan"))
            throw new IllegalStateException("Portal fixture requires a disposable isolated Vulkan world");
    }

    public boolean frame() throws Exception {
        assertIsolated();
        if (failure.get() != null) throw new IllegalStateException("Portal fixture failed", failure.get());
        if (System.nanoTime() - stepStarted > 75_000_000_000L)
            throw new IllegalStateException("Portal fixture timed out: " + state());
        if (screenshotPending.get() || !serverWork.isDone()) return false;
        serverWork.join();
        if (captured) {
            collecting = false;
            if (++step == LABELS.length) {
                write(evidence.resolve("portal-report.json"), Map.of("captures", captures,
                        "scope", "Actual Ender Eye activation twice, main-view portal and gateway submissions, completed native draws, texture/material/format checks, removal and shader reload",
                        "visualVerificationRequired", true));
                active = null;
                return true;
            }
            begin();
            return false;
        }
        if (client.gui.screen() != null || client.gui.overlay() != null || !worldMatches()) {
            settledFrames = collectedFrames = 0;
            collecting = false;
            return false;
        }
        client.player.setYRot(180.0f);
        client.player.setXRot(35.0f);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setOldPosAndRot();
        if (step == 5 && !reloaded) {
            packBeforeReload = Iris.getCurrentPack().orElseThrow();
            Iris.reload();
            reloaded = true;
            return false;
        }
        if (step == 5 && Iris.getCurrentPack().orElseThrow() == packBeforeReload)
            throw new IllegalStateException("Portal shader reload retained the old shader pack instance");
        if (!collecting) {
            // Client block updates precede extracted/submitted draw state. Discard a full
            // settling interval before resetting counters, including any previous-scene draws.
            if (++settledFrames < 120) return false;
            portalSubmits = gatewaySubmits = completedDraws = 0;
            drawCounts.clear(); drawContracts.clear(); beamDrawCounts.clear(); beamDrawContracts.clear();
            collectedFrames = 0;
            collecting = true;
            return false;
        }
        if (++collectedFrames < 60) return false;
        boolean expectedPortal = portalExpected();
        boolean expectedGateway = gatewayExpected();
        if ((portalSubmits > 0) != expectedPortal || (gatewaySubmits > 0) != expectedGateway
                || (completedDraws > 0) != (expectedPortal || expectedGateway)
                || (!expectedGateway && !beamDrawCounts.isEmpty()))
            throw new IllegalStateException("Portal render coverage mismatch: " + state());
        NativePackResourceEvidence.write(client, run, LABELS[step]);
        var state = state();
        collecting = false;
        captures.add(state);
        write(evidence.resolve(LABELS[step] + "-state.json"), state);
        screenshotPending.set(true);
        String label = LABELS[step];
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) { image.writeToFile(evidence.resolve(label + ".png")); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
            finally { screenshotPending.set(false); }
        });
        captured = true;
        Iris.logger.info("Portal probe captured {}: portal submits={}, gateway submits={}, completed main-view draws={}",
                label, portalSubmits, gatewaySubmits, completedDraws);
        return false;
    }

    private void begin() {
        captured = collecting = false;
        settledFrames = collectedFrames = 0;
        stepStarted = System.nanoTime();
        serverWork = new CompletableFuture<>();
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = server.overworld();
                var source = server.createCommandSourceStack().withSuppressedOutput();
                if (step == 0) {
                    server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                    server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                    for (String command : List.of("time set 4000", "weather clear",
                            "fill -18 64 -18 18 76 20 air", "fill -18 63 -18 18 63 20 smooth_stone",
                            "kill @e[type=!minecraft:player]", "setblock 0 66 8 barrier",
                            "tp @p 0.5 67.0 8.5 180 35"))
                        server.getCommands().performPrefixedCommand(source, command);
                    for (int i = -1; i <= 1; i++) {
                        frame(level, new BlockPos(i, 64, 0), Direction.SOUTH);
                        frame(level, new BlockPos(i, 64, 4), Direction.NORTH);
                        frame(level, new BlockPos(-2, 64, i + 2), Direction.EAST);
                        frame(level, new BlockPos(2, 64, i + 2), Direction.WEST);
                    }
                    level.setBlock(LAST_EYE, level.getBlockState(LAST_EYE).setValue(EndPortalFrameBlock.HAS_EYE, false), 3);
                }
                if (step == 1 || step == 4) {
                    var player = server.getPlayerList().getPlayer(client.player.getUUID());
                    if (player == null) throw new IllegalStateException("Missing fixture player");
                    ItemStack original = player.getMainHandItem();
                    try {
                        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_EYE));
                        activationResult = String.valueOf(player.getMainHandItem().useOn(new UseOnContext(player,
                                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(LAST_EYE), Direction.UP, LAST_EYE, false))));
                    } finally { player.setItemInHand(InteractionHand.MAIN_HAND, original); }
                    for (int x = -1; x <= 1; x++) for (int z = 1; z <= 3; z++)
                        if (!level.getBlockState(new BlockPos(x, 64, z)).is(Blocks.END_PORTAL))
                            throw new IllegalStateException("Actual Ender Eye use did not activate all nine portal blocks: " + activationResult);
                }
                if (step == 2 || step == 3) {
                    // The first gateway view must prove its own cube surface draws. Both surfaces
                    // share material 5025, so keeping the portal would let it mask a missing cube.
                    for (int x = -1; x <= 1; x++) for (int z = 1; z <= 3; z++)
                        level.setBlock(new BlockPos(x, 64, z), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(LAST_EYE, level.getBlockState(LAST_EYE).setValue(EndPortalFrameBlock.HAS_EYE, false), 3);
                }
                if (step == 2 || step == 4) level.setBlock(GATEWAY, Blocks.END_GATEWAY.defaultBlockState(), 3);
                if (step == 3) level.setBlock(GATEWAY, Blocks.AIR.defaultBlockState(), 3);
                server.forceGameTimeSynchronization();
                serverWork.complete(null);
            } catch (Throwable error) { serverWork.completeExceptionally(error); }
        });
    }

    private static void frame(net.minecraft.server.level.ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlock(pos, Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, facing)
                .setValue(EndPortalFrameBlock.HAS_EYE, true), 3);
    }

    private boolean portalExpected() { return step == 1 || step >= 4; }
    private boolean gatewayExpected() { return step == 2 || step >= 4; }
    private boolean worldMatches() {
        if (client.level == null || client.player == null || client.player.distanceToSqr(0.5, 67.0, 8.5) > 0.1) return false;
        for (int x = -1; x <= 1; x++) for (int z = 1; z <= 3; z++)
            if (client.level.getBlockState(new BlockPos(x, 64, z)).is(Blocks.END_PORTAL) != portalExpected()) return false;
        return client.level.getBlockState(GATEWAY).is(Blocks.END_GATEWAY) == gatewayExpected();
    }

    public static void submitted(boolean gateway) {
        var probe = active;
        if (probe == null || !probe.collecting || IrisVulkanShadowRenderer.active()) return;
        if (gateway) probe.gatewaySubmits++; else probe.portalSubmits++;
    }

    /** Called only after the real drawFromBuffer method returns without a pipeline/binding exception. */
    public static void drawn(PreparedRenderType prepared, int indexCount) {
        var probe = active;
        if (probe == null || !probe.collecting || IrisVulkanShadowRenderer.active() || indexCount <= 0) return;
        if (!((Object) prepared instanceof IrisVulkanEntityContext.MaterialDraw stored)) return;
        var material = stored.iris$material();
        if (!material.blockEntity() || material.blockEntityId() != 5025) return;
        var format = prepared.pipeline().getVertexFormatBinding(0);
        String pipeline = prepared.pipeline().getLocation().toString();
        if (prepared.pipeline() == RenderPipelines.BEACON_BEAM_OPAQUE
                || prepared.pipeline() == RenderPipelines.BEACON_BEAM_TRANSLUCENT) {
            // Gateway beams inherit material 5025 but are not portal cube surfaces. Recognize
            // only these exact vanilla pipelines; END_PORTAL/END_GATEWAY must still be checked.
            var beamTexture = probe.client.getTextureManager().getTexture(Identifier.withDefaultNamespace(
                    "textures/entity/end_portal/end_gateway_beam.png")).getTextureView();
            boolean correctBeamTexture = prepared.textures().stream()
                    .anyMatch(t -> t.name().equals("Sampler0") && t.textureView() == beamTexture);
            boolean correctBeamFormat = List.of("Position", "Color", "UV0", "UV2").stream().allMatch(format::contains);
            if (!correctBeamFormat || !correctBeamTexture) probe.failure.compareAndSet(null, new IllegalStateException(
                    "Gateway beam draw has incorrect contract: " + pipeline + ", " + format + ", gateway beam Sampler0=" + correctBeamTexture));
            probe.beamDrawCounts.merge(pipeline, 1L, Long::sum);
            probe.beamDrawContracts.put(pipeline, Map.of("material", material, "vertexFormat", format.toString(),
                    "endGatewayBeamSampler0", correctBeamTexture, "indexCount", indexCount));
            return;
        }
        var texture = probe.client.getTextureManager().getTexture(TheEndPortalRenderer.END_PORTAL_LOCATION).getTextureView();
        boolean correctTexture = prepared.textures().stream().anyMatch(t -> t.name().equals("Sampler0") && t.textureView() == texture);
        boolean correctFormat = List.of("Position", "Color", "UV0", "UV1", "UV2", "Normal").stream().allMatch(format::contains);
        if (!correctFormat || !correctTexture) probe.failure.compareAndSet(null, new IllegalStateException(
                "Portal draw has incorrect vertex/texture contract: " + pipeline + ", " + format + ", end_portal Sampler0=" + correctTexture));
        probe.completedDraws++;
        probe.drawCounts.merge(pipeline, 1L, Long::sum);
        probe.drawContracts.put(pipeline, Map.of("material", material, "vertexFormat", format.toString(),
                "endPortalSampler0", correctTexture, "indexCount", indexCount,
                "samplers", prepared.textures().stream().map(PreparedRenderType.Texture::name).toList()));
    }

    private Map<String, Object> state() throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("label", LABELS[step]); result.put("profile", System.getProperty("iris.vulkan.probe.expectedProfile"));
        result.put("pack", Iris.getCurrentPackName());
        Path pack = Iris.getShaderpacksDirectory().resolve(Iris.getCurrentPackName());
        if (Files.isRegularFile(pack)) result.put("packSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pack))));
        result.put("world", client.getSingleplayerServer().getWorldData().getLevelName());
        result.put("cameraPosition", List.of(client.player.getX(), client.player.getY(), client.player.getZ()));
        result.put("yawPitch", List.of(client.player.getYRot(), client.player.getXRot()));
        result.put("settledFrames", settledFrames); result.put("collectedFrames", collectedFrames);
        result.put("expectedPortalBlocks", portalExpected() ? 9 : 0);
        result.put("expectedGatewayBlocks", gatewayExpected() ? 1 : 0); result.put("activationResult", activationResult);
        result.put("portalMainViewSubmits", portalSubmits); result.put("gatewayMainViewSubmits", gatewaySubmits);
        result.put("completedMainViewDraws", completedDraws); result.put("drawCounts", new LinkedHashMap<>(drawCounts));
        result.put("gatewayBeamDrawCounts", new LinkedHashMap<>(beamDrawCounts));
        result.put("gatewayBeamDrawContracts", new LinkedHashMap<>(beamDrawContracts));
        result.put("drawContracts", new LinkedHashMap<>(drawContracts)); result.put("reloadProducedNewPackInstance", step == 5 && Iris.getCurrentPack().orElseThrow() != packBeforeReload);
        result.put("visualVerificationRequired", true);
        return result;
    }

    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(value));
    }
}
