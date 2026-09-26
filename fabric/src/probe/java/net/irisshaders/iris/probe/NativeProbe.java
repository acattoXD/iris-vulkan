package net.irisshaders.iris.probe;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated development mod; excluded from published Iris jars. */
public final class NativeProbe {
    private static final long START = System.nanoTime();
    private static final AtomicBoolean PENDING = new AtomicBoolean();
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static int stage, frames;
    private static long captureAt;
    private static boolean done;
    private static boolean scenePrepared;
	private static boolean buildRecorded;
	private static boolean handShown;
	private static java.util.concurrent.CompletableFuture<NativeShadowReadback.Report> shadowReadback;
	private static NativeWorldScenarios scenarios;
	private static String scenarioCapture;
	private static boolean scenariosComplete;
	private static int motionFrame;
	private static NativeTransientEffectsProbe effects;
	private static java.util.concurrent.CompletableFuture<Void> effectSetup;
	private static boolean qualityTested;
	private static NativeUltraReadback.Capture ultraReadback;
	private static int ultraReports;
	private static long previousUltraDispatch;
	private static long previousUltraPipelineDispatches;
	private static NativeCameraProbe cameraProbe;
	private static NativePlayerShadowProbe playerShadowProbe;
	private static boolean ultraMotionComplete;
	private static java.util.concurrent.CompletableFuture<Void> ultraMotionSetup;
	private static boolean enchantedAnimationComplete;
	private static NativePortalProbe portalProbe;
	private static NativeEntityFacesProbe entityFacesProbe;

    public static void frame(Minecraft client) {
        if (Boolean.getBoolean("iris.atlasSmoke")) {
            NativeAtlasAnimationProbe.frame(client);
            return;
        }
        if (Boolean.getBoolean("iris.benchmark")) {
            NativeBenchmarkProbe.frame(client);
            return;
        }
        if (System.getProperty("iris.uiSmoke") != null) {
            NativeVulkanWarningProbe.frame(client);
            return;
        }
        if (!buildRecorded) {
            try {
                recordLoadedBuild(Path.of(System.getProperty("iris.vulkan.probe.runDir")));
                buildRecorded = true;
            } catch (Exception failure) { throw new IllegalStateException("Cannot verify the loaded probe build", failure); }
        }
        if (Boolean.getBoolean("iris.vulkan.probe.interactive")) { InteractiveWorld.frame(client); return; }
        if (done) return;
        Path run = Path.of(System.getProperty("iris.vulkan.probe.runDir")).toAbsolutePath().normalize();
        try {
            if (!client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)) throw new IllegalStateException("Wrong probe directory");
			client.getWindow().setTitle("Iris " + System.getProperty("iris.vulkan.probe.backend", "Vulkan") + " "
				+ (System.getProperty("iris.vulkan.probe.backend", "Vulkan").equalsIgnoreCase("opengl") ? "REFERENCE" : "TEST")
				+ " | Minecraft 26.2 | " + Iris.getCurrentPackName());
			if (scenePrepared && stage < 4 && client.player != null) {
				client.player.setYRot(180.0f);
				client.player.setXRot(17.0f);
				client.player.setOldPosAndRot();
			}
            if (FAILURE.get() != null) throw new IllegalStateException("Capture failed", FAILURE.get());
            long timeout = Boolean.getBoolean("iris.vulkan.probe.scenarios") ? 1_200_000_000_000L
                    : Boolean.getBoolean("iris.vulkan.probe.portal") ? 480_000_000_000L : 240_000_000_000L;
            if (System.nanoTime() - START > timeout) throw new IllegalStateException("Probe timed out at stage " + stage);
			if (shadowReadback != null) {
				if (!shadowReadback.isDone()) return;
				var report = shadowReadback.join();
				if (!report.bothTexturesContainCasters() || !report.depthRangeAndOrderingValid())
					throw new IllegalStateException("Native shadow depth readback failed; inspect first-shadow-report.json");
			}
			if (ultraReadback != null) {
				var report = ultraReadback.poll();
				if (report == null) return;
				ultraReadback = null;
				if (!report.activityPassed()) throw new IllegalStateException("Ultra storage activity check failed; inspect " + report.label() + "-ultra-storage-report.json");
				if (report.actualDispatch().sequence() <= previousUltraDispatch) throw new IllegalStateException("Ultra shadowcomp dispatch sequence did not advance");
				if (report.currentPipelineDispatchCount() <= previousUltraPipelineDispatches) throw new IllegalStateException("The active Ultra pipeline's dispatch count did not advance");
				previousUltraDispatch = report.actualDispatch().sequence();
				previousUltraPipelineDispatches = report.currentPipelineDispatchCount();
				ultraReports++;
			}
            if (PENDING.get() || client.gui.overlay() != null) return;
            frames++;
            if (stage == 0) {
                if (frames < 90 || client.gui.screen() == null) return;
                if (!RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase(System.getProperty("iris.vulkan.probe.backend", "Vulkan"))) throw new IllegalStateException("Unexpected actual graphics backend");
                if (Iris.getCurrentPack().isEmpty()) throw new IllegalStateException("Iris did not load the probe shaderpack");
				if (Boolean.getBoolean("iris.vulkan.probe.ultra")) NativeUltraReadback.requireUltraOptions(Iris.getCurrentPack().orElseThrow());
                if (!client.gui.hud.isHidden()) client.gui.hud.toggle();
                String world = "iris_native_probe_" + System.currentTimeMillis();
                LevelSettings settings = new LevelSettings(world, GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true, WorldDataConfiguration.DEFAULT);
                client.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(262L, false, false),
                        registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        new TitleScreen());
                stage = 1; frames = 0;
            } else if (stage == 1) {
                if (client.level != null && client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) client.gui.setScreen(null);
                if (client.level == null || client.player == null || client.gui.screen() != null || frames < 250) return;
				if (Boolean.getBoolean("iris.vulkan.probe.qualityChange") && !qualityTested) {
					NativeQualityChangeProbe.run(run);
					qualityTested = true;
				}
                if (Boolean.getBoolean("iris.vulkan.probe.world") && !scenePrepared) {
                    scenePrepared = true;
                    var server = client.getSingleplayerServer();
                    if (server == null) throw new IllegalStateException("World probe must be isolated singleplayer");
                    server.execute(() -> {
                        var source = server.createCommandSourceStack().withSuppressedOutput();
                        String[] commands = {
                            "time set 4000", "weather clear", "fill -18 63 -18 18 63 20 smooth_stone", "fill -12 62 -5 -5 62 4 smooth_stone",
                            "fill -12 63 -5 -5 63 4 water", "fill -3 64 -4 -1 69 -2 oak_log",
                            "fill 2 64 -6 6 67 -3 stone_bricks", "fill 8 64 -4 8 67 3 white_stained_glass",
                            "setblock 2 64 2 chest", "summon cow -4 64 5 {NoAI:1b,PersistenceRequired:1b}", "summon pig 5 64 6 {NoAI:1b,PersistenceRequired:1b}",
                            "tp @p 0 64 15 180 17", "give @p diamond_sword"
                        };
                        for (String command : commands) server.getCommands().performPrefixedCommand(source, command);
						if (Boolean.getBoolean("iris.vulkan.probe.ultra"))
							for (String command : NativeUltraReadback.sceneCommands()) server.getCommands().performPrefixedCommand(source, command);
						if (Boolean.getBoolean("iris.vulkan.probe.enchanted")) {
							try { NativeEnchantedItemProbe.prepare(server); }
							catch (Throwable error) { FAILURE.compareAndSet(null, error); }
						}
						server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
						var dayClock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
						server.clockManager().setPaused(dayClock, true);
						server.forceGameTimeSynchronization();
                    });
                    frames = 0;
                    return;
                }
                if (Boolean.getBoolean("iris.vulkan.probe.auditOnly")) {
                    var programs = Iris.getCurrentPack().orElseThrow().getProgramSet(Iris.getCurrentDimension());
                    PackRequirementsReport.write(run, programs);
                    var missing = net.irisshaders.iris.vulkan.IrisNativeVulkan.unsupportedActiveWorldPrograms(programs);
                    var report = new java.util.LinkedHashMap<String, Object>();
                    report.put("pack", Iris.getCurrentPackName());
                    report.put("backend", RenderSystem.getDevice().getDeviceInfo().backendName());
                    report.put("mode", "capability audit; shader execution disabled");
                    report.put("unsupportedActiveWorldPrograms", missing);
                    report.put("shaderPackCompatibilityEstablished", false);
                    Files.writeString(run.resolve("capability-report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
                    Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_NATIVE_CAPABILITY_AUDIT_COMPLETE\nNot a shader-rendering validation\n");
                    done = true; client.stop(); return;
                }
				if (Boolean.getBoolean("iris.vulkan.probe.entityFaces")) {
					entityFacesProbe = new NativeEntityFacesProbe(client, run);
					stage = 13;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.portal")) {
					portalProbe = new NativePortalProbe(client, run);
					stage = 12;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.playerShadow")) {
					playerShadowProbe = new NativePlayerShadowProbe(client, run);
					stage = 8;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.camera")) {
					cameraProbe = new NativeCameraProbe(client, run);
					stage = 7;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.motion")) { stage = 5; motionFrame = 0; return; }
                capture(client, run, "first.png");
				if (Boolean.getBoolean("iris.vulkan.probe.world")
					&& RenderSystem.getDevice().getDeviceInfo().backendName().equalsIgnoreCase("Vulkan")
					&& Iris.getPipelineManager().getPipelineNullable() instanceof net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline nativePipeline
					&& nativePipeline.usesShadowMaps()) {
					shadowReadback = NativeShadowReadback.capture(run.resolve("evidence"), "first");
				}
				if (Boolean.getBoolean("iris.vulkan.probe.ultra")) ultraReadback = NativeUltraReadback.capture(run.resolve("evidence"), "first", client);
                captureAt = System.nanoTime(); stage = 2;
            } else if (stage == 2) {
                if (System.nanoTime() - captureAt < 1_200_000_000L) return;
				if (Boolean.getBoolean("iris.vulkan.probe.world") && !handShown) {
					handShown = true;
					if (client.gui.hud.isHidden()) client.gui.hud.toggle();
					captureAt = System.nanoTime();
					return;
				}
                capture(client, run, "second.png");
				if (Boolean.getBoolean("iris.vulkan.probe.ultra")) ultraReadback = NativeUltraReadback.capture(run.resolve("evidence"), "second", client);
				stage = 3;
            } else if (stage == 3) {
				if (Boolean.getBoolean("iris.vulkan.probe.enchanted") && !enchantedAnimationComplete) {
					frames = 0;
					stage = 11;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.ultraMotion") && !ultraMotionComplete) {
					ultraMotionSetup = new java.util.concurrent.CompletableFuture<>();
					var server = client.getSingleplayerServer();
					server.execute(() -> {
						try {
							server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "tp @p -8.5 64.0 5.5 180 35");
							ultraMotionSetup.complete(null);
						} catch (Throwable error) { ultraMotionSetup.completeExceptionally(error); }
					});
					frames = 0;
					stage = 9;
					return;
				}
				if (Boolean.getBoolean("iris.vulkan.probe.scenarios") && !scenariosComplete) {
					scenarios = new NativeWorldScenarios(client);
					stage = 4;
					return;
				}
                String result = Boolean.getBoolean("iris.vulkan.probe.world") ? "IRIS_WORLD_CAPTURED\nRequires image and shader coverage verification" : "IRIS_NATIVE_PROBE_PASS";
				if (Boolean.getBoolean("iris.vulkan.probe.ultra")) {
					if (ultraReports != 2) throw new IllegalStateException("Ultra requires two fence-completed GPU storage reports");
					result = "IRIS_ULTRA_STORAGE_ACTIVITY_PASS\nTwo actual GPU readbacks passed; requires visual verification";
					if (ultraMotionComplete) result += "\nUltraMotion=PASS (96 frames, 16 screenshots, three additional GPU storage readbacks)";
				}
                Files.writeString(run.resolve("native-probe-result.txt"), result + "\nBackend=" + RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\n");
                done = true; client.stop();
			} else if (stage == 13) {
				if (entityFacesProbe.frame()) {
					Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_ENTITY_FACES_CAPTURED\nSix named-mob/falling-block captures with completed draw coverage; visual verification required\nBackend="
						+ RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\n");
					done = true;
					client.stop();
				}
			} else if (stage == 12) {
				if (portalProbe.frame()) {
					Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_PORTAL_CAPTURED\nSix portal/gateway activation, removal, recreation and shader-reload captures; actual main-view draw contracts passed\nRequires visual verification\nBackend="
						+ RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\n");
					done = true;
					client.stop();
				}
			} else if (stage == 11) {
				if (frames == 24) capture(client, run, "enchanted-held-world-a.png");
				if (frames == 48) capture(client, run, "enchanted-held-world-b.png");
				if (frames > 48) {
					NativeEnchantedItemProbe.requireMainViewCoverage();
					enchantedAnimationComplete = true;
					stage = 3;
				}
			} else if (stage == 9) {
				if (cameraProbe == null) {
					if (!ultraMotionSetup.isDone() || frames < 15) return;
					ultraMotionSetup.join();
					if (Math.abs(client.player.getX() + 8.5) > 0.2 || Math.abs(client.player.getZ() - 5.5) > 0.2) {
						if (frames > 120) throw new IllegalStateException("Ultra motion water-edge teleport did not settle: " + client.player.position());
						return;
					}
					cameraProbe = new NativeCameraProbe(client, run, true);
					return;
				}
				if (cameraProbe.frame()) {
					ultraMotionComplete = true;
					stage = 3;
				}
			} else if (stage == 8) {
				if (playerShadowProbe.frame()) {
					Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_PLAYER_SHADOW_CAPTURED\nTwo third-person angles, moved-player and changed-sun views, with four GPU shadow readbacks completed\nRequires visual verification of player silhouette and nearby ground\nBackend="
						+ RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\n");
					done = true;
					client.stop();
				}
			} else if (stage == 7) {
				if (cameraProbe.frame()) {
					Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_CAMERA_MOTION_CAPTURED\nMatrixInvariants="
						+ (cameraProbe.invariantsPassed() ? "PASS" : "FAIL (record-only baseline)")
						+ "\nRequires visual verification of sequential screenshots\nBackend="
						+ RenderSystem.getDevice().getDeviceInfo().backendName() + "\nPack=" + Iris.getCurrentPackName() + "\n");
					done = true;
					client.stop();
				}
			} else if (stage == 5) {
				motionFrame++;
				if (client.gui.hud.isHidden()) client.gui.hud.toggle();
				int movement = Math.clamp(motionFrame - 120, 0, 120);
				client.player.setYRot(180.0f + movement * 0.8f);
				client.player.setXRot(-55.0f + (motionFrame > 120 && motionFrame <= 240 ? 5.0f * (float)Math.sin(movement / 15.0f) : 0.0f));
				client.player.setOldPosAndRot();
				if (motionFrame > 120 && motionFrame <= 240 && motionFrame % 20 == 0) client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				if (motionFrame == 120 || motionFrame == 160 || motionFrame == 200 || motionFrame == 240 || motionFrame == 360)
					capture(client, run, "motion-" + motionFrame + ".png");
				if (motionFrame == 360) {
					NativePresentationReport.write(run.resolve("presentation-report.json"), client);
					effects = new NativeTransientEffectsProbe(client);
					effectSetup = effects.prepareFireScene();
					frames = 0;
					stage = 6;
				}
			} else if (stage == 6) {
				if (!effectSetup.isDone()) return;
				effectSetup.join();
				client.player.setYRot(180.0f);
				client.player.setXRot(17.0f);
				client.player.setOldPosAndRot();
				effects.emitParticles();
				if (frames == 120) { capture(client, run, "particles-fire.png"); effectSetup = effects.setPlayerFireOverlay(true); }
				if (frames == 240) { capture(client, run, "fire-overlay.png"); effectSetup = effects.setPlayerFireOverlay(false); }
				if (frames > 240) stage = 3;
			} else if (stage == 4) {
				if (scenarioCapture != null) {
					scenarios.captureComplete(scenarioCapture);
					scenarioCapture = null;
				}
				var frame = scenarios.tick();
				if (frame.complete()) {
					scenariosComplete = true;
					stage = 3;
				} else if (frame.ready()) {
					capture(client, run, frame.scenario() + ".png");
					Files.writeString(run.resolve("evidence").resolve(frame.scenario() + "-state.json"),
						new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(frame.evidence()));
					scenarioCapture = frame.scenario();
				}
            }
        } catch (Throwable failure) {
            done = true;
			if (cameraProbe != null) cameraProbe.close();
			if (playerShadowProbe != null) playerShadowProbe.close();
			if (ultraReadback != null) { ultraReadback.close(); ultraReadback = null; }
            failure.printStackTrace();
            try { Files.writeString(run.resolve("native-probe-result.txt"), "IRIS_NATIVE_PROBE_FAIL\n" + failure); }
            catch (Exception ignored) { }
            client.stop();
        }
    }

    private static void recordLoadedBuild(Path run) throws Exception {
        var report = new java.util.LinkedHashMap<String, Object>();
        for (String name : java.util.List.of("net.irisshaders.iris.vulkan.IrisNativeVulkan",
                "net.irisshaders.iris.vulkan.IrisNativeVulkan$1",
                "net.irisshaders.iris.vulkan.IrisVulkanShaderResources",
                "net.irisshaders.iris.vulkan.IrisVulkanRenderPassBindings",
                "net.irisshaders.iris.vulkan.IrisVulkanStoragePipeline",
                "net.irisshaders.iris.vulkan.IrisVulkanShaderCompatibility",
                "net.irisshaders.iris.vulkan.IrisVulkanComputeExecutor",
                "net.irisshaders.iris.vulkan.IrisVulkanStorageResources",
                "net.irisshaders.iris.vulkan.IrisVulkanUniformSnapshot",
                "net.irisshaders.iris.mixin.IrisMixinPlugin",
                "net.irisshaders.iris.mixin.MixinModelViewBobbing",
                "net.irisshaders.iris.pipeline.NativeVulkanWorldRenderingPipeline",
                "net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer",
                "net.irisshaders.iris.vulkan.IrisVulkanShadowDrawPolicy",
                "net.irisshaders.iris.mixin.vulkan.VKOnly_MixinPreparedRenderType_Materials",
                "net.irisshaders.iris.probe.NativeCameraProbe",
                "net.irisshaders.iris.probe.NativePlayerShadowProbe",
                "net.irisshaders.iris.probe.NativePortalProbe",
                "net.irisshaders.iris.probe.NativeEntityFacesProbe",
                "net.irisshaders.iris.probe.mixin.ProbeEntityFacesDrawMixin",
                "net.irisshaders.iris.probe.mixin.ProbePortalSubmitMixin",
                "net.irisshaders.iris.probe.mixin.ProbePortalGatewayMixin",
                "net.irisshaders.iris.probe.mixin.ProbePortalDrawMixin",
                "net.irisshaders.iris.probe.mixin.ProbeCameraMotionMixin")) {
            String resource = name.replace('.', '/') + ".class";
            var location = NativeProbe.class.getClassLoader().getResource(resource);
            // Reading mixin resources must not load their forbidden implementation classes.
            try (var input = NativeProbe.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing class resource " + name);
                String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                report.put(name, java.util.Map.of("source", location.toString(), "sha256", sha));
            }
        }
        Files.createDirectories(run.resolve("evidence"));
        Files.writeString(run.resolve("evidence/loaded-build.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static void capture(Minecraft client, Path run, String name) {
        try { NativePackResourceEvidence.write(client, run, name); }
        catch (Throwable error) { FAILURE.compareAndSet(null, error); return; }
		if (Boolean.getBoolean("iris.vulkan.probe.enchanted")) {
			try { NativeEnchantedItemProbe.writeEvidence(client, run.resolve("evidence"), name); }
			catch (Throwable error) { FAILURE.compareAndSet(null, error); return; }
		}
		if (Boolean.getBoolean("iris.vulkan.probe.shaderDumps") && Files.isDirectory(run.resolve("patched_shaders"))) {
			try (var files = Files.list(run.resolve("patched_shaders"))) {
				Path destination = run.resolve("evidence/shader-sources").resolve(name.replace(".png", ""));
				Files.createDirectories(destination);
				for (Path source : files.filter(Files::isRegularFile).toList()) Files.copy(source, destination.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception error) { FAILURE.compareAndSet(null, error); }
		}
        PENDING.set(true);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Files.createDirectories(run.resolve("evidence"));
                image.writeToFile(run.resolve("evidence").resolve(name));
                int hits = 0, samples = 0;
                for (int y = image.getHeight() / 10; y < image.getHeight() * 4 / 5; y++) {
                    for (int x = image.getWidth() / 2 - 2; x < image.getWidth() / 2 + 2; x++) {
                        int pixel = image.getPixel(x, y);
                        if (((pixel >>> 16) & 255) > 200 && ((pixel >>> 8) & 255) < 60 && (pixel & 255) > 200) hits++;
                        samples++;
                    }
                }
                if (!Boolean.getBoolean("iris.vulkan.probe.world") && hits < samples * 0.8) throw new IllegalStateException("Actual shader marker missing: " + hits + "/" + samples);
            } catch (Throwable failure) { FAILURE.compareAndSet(null, failure); }
            finally { PENDING.set(false); }
        });
    }
}
