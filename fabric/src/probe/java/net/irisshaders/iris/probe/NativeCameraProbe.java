package net.irisshaders.iris.probe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic camera fixture; never loaded into a release mod or manual world. */
public final class NativeCameraProbe implements AutoCloseable {
    private static final int LAST_FRAME = 160;
    private static final float EPSILON = 0.00002f;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static NativeCameraProbe active;
    private final Minecraft client;
    private final Path evidence;
    private final Vec3 origin;
    private final boolean originalBobView;
    private final CameraType originalCameraType;
    private final boolean ultraMotion;
    private final int lastFrame;
    private final long startedAt = System.nanoTime();
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<Map<String, Object>> trace = new ArrayList<>();
    private final List<String> images = new ArrayList<>();
    private final List<NativeUltraReadback.Capture> storagePending = new ArrayList<>();
    private final List<NativeUltraReadback.Report> storageReports = new ArrayList<>();
    private int renderedFrame;
    private int processedFrame;
    private int nonzeroBobFrames;
    private boolean passed = true;
    private boolean reported;
    private float maxProjectionError;
    private float maxProductError;
    private Map<String, Object> current;
    private Matrix4f rawProjection;
    private Matrix4f rawView;
    private Matrix4f bob;

    public NativeCameraProbe(Minecraft client, Path run) throws Exception {
        this(client, run, false);
    }

    public NativeCameraProbe(Minecraft client, Path run, boolean ultraMotion) throws Exception {
        var server = client.getSingleplayerServer();
        if (!Boolean.getBoolean(ultraMotion ? "iris.vulkan.probe.ultraMotion" : "iris.vulkan.probe.camera") || server == null || client.player == null
                || !server.getWorldData().getLevelName().startsWith("iris_native_probe_")
                || !client.gameDirectory.toPath().toAbsolutePath().normalize().equals(run)) {
            throw new IllegalStateException("Camera regression requires a disposable, isolated probe world");
        }
        this.client = client;
        this.ultraMotion = ultraMotion;
        this.lastFrame = ultraMotion ? 96 : LAST_FRAME;
        this.evidence = run.resolve("evidence");
        Files.createDirectories(evidence);
        this.origin = client.player.position();
        this.originalBobView = client.options.bobView().get();
        this.originalCameraType = client.options.getCameraType();
        if (ultraMotion) {
            NativeUltraReadback.requireUltraOptions(Iris.getCurrentPack().orElseThrow());
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
        client.options.bobView().set(true);
        if (client.gui.hud.isHidden()) client.gui.hud.toggle();
        active = this;
        preparePose(1);
    }

    public static CameraRenderState beginFrame(GameRenderer renderer) {
        var probe = active;
        if (probe == null || probe.renderedFrame >= probe.lastFrame) return null;
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        probe.renderedFrame++;
        int frame = probe.renderedFrame;
        boolean walking = probe.ultraMotion ? (frame > 24 && frame <= 48) || frame > 72 : frame > 80;
        // Exercise vanilla bobView with a controlled walking phase. The player also
        // travels through the world; the input amplitude is independent of FPS/ticks.
        camera.entityRenderState.isPlayer = true;
        camera.entityRenderState.bob = walking ? 0.12f : 0.0f;
        camera.entityRenderState.backwardsInterpolatedWalkDistance = walking ? -(frame - (probe.ultraMotion ? 24 : 80)) * 0.17f : 0.0f;
        probe.rawProjection = new Matrix4f(camera.projectionMatrix);
        probe.rawView = new Matrix4f(camera.viewRotationMatrix);
        probe.current = new LinkedHashMap<>();
        probe.current.put("frame", frame);
        probe.current.put("phase", probe.phase(frame));
        probe.current.put("cameraPosition", List.of(camera.pos.x, camera.pos.y, camera.pos.z));
        probe.current.put("cameraYawPitch", List.of(camera.yRot, camera.xRot));
        probe.current.put("playerPosition", List.of(probe.client.player.getX(), probe.client.player.getY(), probe.client.player.getZ()));
        probe.current.put("cameraBlock", List.of((int) Math.floor(camera.pos.x), (int) Math.floor(camera.pos.y), (int) Math.floor(camera.pos.z)));
        probe.current.put("heldItem", probe.client.player.getMainHandItem().toString());
        probe.current.put("hudHidden", probe.client.gui.hud.isHidden());
        if (probe.ultraMotion) {
            probe.current.put("cameraType", probe.client.options.getCameraType().name());
            probe.current.put("emissiveSourcesInView", Map.of("verdantFroglight", probe.inView(camera, -3.5, 65.5, 0.5),
                    "pearlescentFroglight", probe.inView(camera, 4.5, 65.5, 0.5)));
            List<List<Float>> reflectorCorners = new ArrayList<>();
            boolean aboveViewport = true;
            for (double x : new double[]{-12.0, -5.0}) for (double y : new double[]{64.0, 70.0}) {
                var clip = probe.project(camera, x, y, -7.0);
                reflectorCorners.add(List.of(clip.x, clip.y, clip.z, clip.w));
                aboveViewport &= clip.w > 0 && clip.y > clip.w;
            }
            probe.current.put("reflectorWallClipCorners", reflectorCorners);
            probe.current.put("reflectorEntirelyAboveViewport", aboveViewport);
        }
        probe.current.put("bob", camera.entityRenderState.bob);
        probe.current.put("walkDistance", camera.entityRenderState.backwardsInterpolatedWalkDistance);
        probe.current.put("rawCameraProjection", matrix(probe.rawProjection));
        probe.current.put("rawCameraViewRotation", matrix(probe.rawView));
        probe.current.put("matrixOrder", "Column-major; expected capturedProjection * capturedModelView = rawProjection * bob * rawViewRotation");
        return camera;
    }

    public static void expectedBobbing(Matrix4fc value) {
        if (active != null) active.bob = new Matrix4f(value);
    }

    public static void endFrame(GameRenderer renderer) {
        var probe = active;
        if (probe == null || probe.current == null || probe.current.containsKey("projectionInvariantPassed")) return;
        try {
            Matrix4f capturedProjection = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferProjection());
            Matrix4f capturedView = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());
            Matrix4f sodiumProjection = new Matrix4f(((GameRendererStorage) renderer).sodium$getProjectionMatrix());
            Matrix4f expectedView = new Matrix4f(probe.bob).mul(probe.rawView);
            Matrix4f expectedProduct = new Matrix4f(probe.rawProjection).mul(expectedView);
            Matrix4f actualProduct = new Matrix4f(capturedProjection).mul(capturedView);
            float projectionError = Math.max(error(probe.rawProjection, capturedProjection), error(probe.rawProjection, sodiumProjection));
            float modelViewError = error(expectedView, capturedView);
            float productError = error(expectedProduct, actualProduct);
            boolean projectionPassed = projectionError < EPSILON;
            boolean modelViewPassed = modelViewError < EPSILON;
            boolean productPassed = productError < EPSILON;
            probe.passed &= projectionPassed && modelViewPassed && productPassed;
            probe.maxProjectionError = Math.max(probe.maxProjectionError, projectionError);
            probe.maxProductError = Math.max(probe.maxProductError, productError);
            if (error(probe.bob, new Matrix4f()) > EPSILON) probe.nonzeroBobFrames++;
            probe.current.put("expectedBobMatrix", matrix(probe.bob));
            probe.current.put("expectedModelView", matrix(expectedView));
            probe.current.put("capturedGbufferProjection", matrix(capturedProjection));
            probe.current.put("capturedGbufferModelView", matrix(capturedView));
            probe.current.put("sodiumProjection", matrix(sodiumProjection));
            probe.current.put("projectionMaxAbsError", projectionError);
            probe.current.put("modelViewMaxAbsError", modelViewError);
            probe.current.put("projectionViewProductMaxAbsError", productError);
            probe.current.put("projectionInvariantPassed", projectionPassed);
            probe.current.put("modelViewInvariantPassed", modelViewPassed);
            probe.current.put("projectionViewProductInvariantPassed", productPassed);
            probe.current.put("elapsedNanos", System.nanoTime());
        } catch (Throwable error) {
            probe.failure.compareAndSet(null, error);
        }
    }

    /** Called after the frame was presented; screenshots and matrices refer to that same render. */
    public boolean frame() throws Exception {
        if (failure.get() != null) throw new IllegalStateException("Camera probe capture failed", failure.get());
        if (ultraMotion && System.nanoTime() - startedAt > 20_000_000_000L)
            throw new IllegalStateException("Ultra motion exceeded its 20-second bound at frame " + renderedFrame);
        for (var iterator = storagePending.iterator(); iterator.hasNext();) {
            var report = iterator.next().poll();
            if (report == null) continue;
            storageReports.add(report);
            iterator.remove();
        }
        if (renderedFrame != processedFrame && current != null) {
            if (!current.containsKey("projectionInvariantPassed")) throw new IllegalStateException("Camera render did not finish");
            processedFrame = renderedFrame;
            trace.add(current);
            if (captureFrame(renderedFrame)) capture(current);
            if (ultraMotion && (renderedFrame == 24 || renderedFrame == 72 || renderedFrame == 96)) {
                storagePending.add(NativeUltraReadback.capture(evidence, "ultra-motion-" + phase(renderedFrame), client));
                Iris.logger.info("Ultra camera motion: {} at frame {} ({} seconds)", phase(renderedFrame), renderedFrame, (System.nanoTime() - startedAt) / 1_000_000_000.0);
            }
            if (renderedFrame < lastFrame) preparePose(renderedFrame + 1);
        }
        if (renderedFrame < lastFrame || pending.get() != 0 || !storagePending.isEmpty()) return false;
        if (!reported) {
            reported = true;
            passed &= trace.size() == lastFrame && images.size() == (ultraMotion ? 16 : 24) && nonzeroBobFrames == (ultraMotion ? 48 : 80);
            if (ultraMotion) {
                passed &= storageReports.size() == 3 && storageReports.stream().allMatch(NativeUltraReadback.Report::activityPassed);
                storageReports.sort(java.util.Comparator.comparingLong(report -> report.actualDispatch().sequence()));
                for (int i = 1; i < storageReports.size(); i++)
                    passed &= storageReports.get(i).actualDispatch().sequence() > storageReports.get(i - 1).actualDispatch().sequence();
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("scope", "Simulated walking position and vanilla view bob plus turning; visual correctness requires sequential-image inspection");
            if (ultraMotion) {
                report.put("ultraStorageReports", storageReports);
                report.put("persistenceEvidence", "Three fence-completed GPU samples before movement, after integer camera boundaries with emissive sources offscreen, and on returning. Activity and matrix checks do not establish reflected-image or held-sword visual correctness.");
            }
            report.put("recordOnlyBaseline", Boolean.getBoolean("iris.vulkan.probe.camera.recordOnly"));
            report.put("invariantsPassed", passed);
            report.put("projectionTolerance", EPSILON);
            report.put("maxProjectionError", maxProjectionError);
            report.put("maxProjectionViewProductError", maxProductError);
            report.put("renderedFrames", trace.size());
            report.put("framesWithNonzeroBob", nonzeroBobFrames);
            report.put("screenshots", images);
            report.put("frames", trace);
            report.put("elapsedSeconds", (System.nanoTime() - startedAt) / 1_000_000_000.0);
            Files.writeString(evidence.resolve(ultraMotion ? "ultra-motion-report.json" : "camera-motion-report.json"), JSON.toJson(report));
            close();
        }
        if (!passed && !Boolean.getBoolean("iris.vulkan.probe.camera.recordOnly"))
            throw new IllegalStateException("Camera matrix or storage invariants failed; inspect evidence/" + (ultraMotion ? "ultra-motion-report.json" : "camera-motion-report.json"));
        return true;
    }

    public boolean invariantsPassed() { return passed; }

    private void preparePose(int frame) {
        float yaw = 180.0f;
        float pitch = 17.0f;
        double x = origin.x;
        double z = origin.z;
        if (ultraMotion) {
            yaw = 180.0f;
            pitch = 35.0f;
            if (frame > 24) {
                double travel = frame <= 48 ? (frame - 24) / 24.0 : frame <= 72 ? 1.0 : (96 - frame) / 24.0;
                x -= 2.0 * travel;
                z -= 0.5 * travel;
                if (frame > 48) yaw += frame <= 72 ? (frame - 48) * 7.5f : (96 - frame) * 7.5f;
            }
        } else if (frame > 40 && frame <= 80) {
            double wave = Math.sin(Math.PI * (frame - 40) / 40.0);
            yaw += (float) (75.0 * wave);
            pitch -= (float) (62.0 * wave);
        } else if (frame > 80) {
            z -= (frame - 80) * 0.07;
            if (frame > 120) {
                double wave = Math.sin(Math.PI * (frame - 120) / 40.0);
                yaw += (float) (45.0 * wave);
                pitch -= (float) (45.0 * wave);
                x += 0.9 * wave;
            }
        }
        client.player.setPos(x, origin.y, z);
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.setOldPosAndRot();
    }

    private void capture(Map<String, Object> state) throws Exception {
        String name = (ultraMotion ? "ultra-motion-" : "camera-") + phase(renderedFrame) + "-" + String.format(java.util.Locale.ROOT, "%03d", renderedFrame);
        if (ultraMotion) {
            NativeUltraReadback.requireUltraOptions(Iris.getCurrentPack().orElseThrow());
            state.put("selectedUltraOptions", NativeUltraReadback.ULTRA_OPTIONS);
            if (client.player.getMainHandItem().isEmpty() || client.gui.hud.isHidden())
                throw new IllegalStateException("Ultra motion requires the visible held sword");
        }
        state.put("screenshot", name + ".png");
        Files.writeString(evidence.resolve(name + "-state.json"), JSON.toJson(state));
        images.add(name + ".png");
        pending.incrementAndGet();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(evidence.resolve(name + ".png"));
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    private boolean captureFrame(int frame) {
        if (ultraMotion) return frame % 24 >= 21 || frame % 24 == 0;
        return (frame >= 35 && frame <= 40) || (frame >= 57 && frame <= 62)
                || (frame >= 97 && frame <= 102) || (frame >= 137 && frame <= 142);
    }

    private String phase(int frame) {
        if (ultraMotion) return frame <= 24 ? "water-before" : frame <= 48 ? "boundary-walk" : frame <= 72 ? "lights-offscreen" : "water-return";
        return frame <= 40 ? "still" : frame <= 80 ? "turn" : frame <= 120 ? "walk" : "walk-turn";
    }

    private static float[] matrix(Matrix4fc value) { return value.get(new float[16]); }

    private boolean inView(CameraRenderState camera, double x, double y, double z) {
        var clip = project(camera, x, y, z);
        return clip.w > 0 && Math.abs(clip.x) <= clip.w && Math.abs(clip.y) <= clip.w;
    }

    private Vector4f project(CameraRenderState camera, double x, double y, double z) {
        return new Matrix4f(rawProjection).mul(rawView).transform(new Vector4f((float) (x - camera.pos.x),
                (float) (y - camera.pos.y), (float) (z - camera.pos.z), 1.0f));
    }

    private static float error(Matrix4fc first, Matrix4fc second) {
        float[] a = matrix(first), b = matrix(second);
        float maximum = 0.0f;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return Float.MAX_VALUE;
            maximum = Math.max(maximum, Math.abs(a[i] - b[i]));
        }
        return maximum;
    }

    @Override
    public void close() {
        if (active == this) active = null;
        client.options.bobView().set(originalBobView);
        client.options.setCameraType(originalCameraType);
        storagePending.forEach(NativeUltraReadback.Capture::close);
        storagePending.clear();
    }
}
