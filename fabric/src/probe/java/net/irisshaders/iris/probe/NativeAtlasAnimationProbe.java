package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Executes vanilla sprite animation on the actual GPU, without a world or Globals UBO. */
public final class NativeAtlasAnimationProbe {
    private static final int SIZE = 16;
    private static final long START = System.nanoTime();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final Map<String, Object> REPORT = new LinkedHashMap<>();
    private static SpriteFixture blit, interpolate;
    private static GpuTexture target;
    private static GpuTextureView targetView;
    private static GpuBuffer staging;
    private static CompletableFuture<Map<String, Object>> pending;
    private static int readyFrames, test;
    private static boolean done;

    private NativeAtlasAnimationProbe() { }

    public static void frame(Minecraft client) {
        if (done) return;
        Path run = client.gameDirectory.toPath().toAbsolutePath().normalize();
        try {
            Path authorized = Path.of(System.getProperty("iris.atlasSmoke.runDir", "")).toAbsolutePath().normalize();
            require(run.equals(authorized) && run.getFileName().toString().contains("atlas-smoke"), "Atlas test requires its explicit isolated atlas-smoke directory");
            require(client.level == null, "Atlas test must remain at the title screen");
            require(System.nanoTime() - START < 120_000_000_000L, "Atlas GPU test timed out at case " + test);
            if (client.gui.overlay() != null || !(client.gui.screen() instanceof TitleScreen)) return;
            if (++readyFrames < 20) return;
            if (target == null) initialize();
            if (pending != null) {
                if (!pending.isDone()) return;
                Map<String, Object> result = pending.join();
                RESULTS.add(result);
                pending = null;
                require(Boolean.TRUE.equals(result.get("pixelsPassed")), "Atlas GPU pixels failed: " + result);
                test++;
            }
            if (test == 3) { finish(client, run, null); return; }
            drawCase(run);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(client, run, failure);
        }
    }

    private static void initialize() throws Exception {
        var device = RenderSystem.getDevice();
        require(device.getDeviceInfo().backendName().equalsIgnoreCase("Vulkan"), "Atlas fixture requires the actual Vulkan backend");
        REPORT.put("scope", "Actual SpriteContents.createAnimationState and AnimationState.drawToAtlas, stock BLIT/INTERPOLATE shaders, null Globals, fenced RGBA8 GPU readback");
        REPORT.put("backend", device.getDeviceInfo().backendName());
        REPORT.put("gpu", device.getDeviceInfo().name());
        REPORT.put("driver", device.getDeviceInfo().driverInfo());
        REPORT.put("os", System.getProperty("os.name"));
        REPORT.put("productionClasses", classEvidence());
        REPORT.put("spriteUniformBytes", SpriteContents.UBO_SIZE);
        REPORT.put("dummyGlobalsCreated", false);
        REPORT.put("globalsRestoredAfterEachDraw", true);
        blit = new SpriteFixture(false);
        interpolate = new SpriteFixture(true);
        target = device.createTexture(() -> "Atlas regression output", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
            GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        targetView = device.createTextureView(target);
        staging = device.createBuffer(() -> "Atlas regression pixel readback", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, SIZE * SIZE * 4L);
    }

    private static void drawCase(Path run) throws Exception {
        String label = test == 0 ? "blit-red" : test == 1 ? "blit-green" : "interpolate-half";
        int[] expected = test == 0 ? new int[]{255, 0, 0, 255} : test == 1 ? new int[]{0, 255, 0, 255} : new int[]{128, 128, 0, 255};
        SpriteFixture fixture = test == 2 ? interpolate : blit;
        if (test == 1) fixture.advance(2);
        if (test == 2) fixture.advance(1);
        require(fixture.animation.needsToDraw(), "Real sprite animation must be ready for " + label);
        GpuBuffer oldGlobals = RenderSystem.getGlobalSettingsUniform();
        REPORT.put("lastAttemptedCase", label);
        RenderSystem.setGlobalSettingsUniform(null);
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Vanilla atlas regression " + label,
                targetView, Optional.of(new Vector4f(0, 0, 1, 1)))) {
            // Match TextureAtlas.uploadAnimationFrames: defaults are optional;
            // AnimationState itself binds only SpriteAnimationInfo and textures.
            RenderSystem.bindDefaultUniforms(pass);
            require(RenderSystem.getGlobalSettingsUniform() == null, "Fixture unexpectedly supplied Globals");
            fixture.animation.drawToAtlas(pass, fixture.animation.getDrawUbo(0));
        } finally {
            RenderSystem.setGlobalSettingsUniform(oldGlobals);
            require(RenderSystem.getGlobalSettingsUniform() == oldGlobals, "Globals identity was not restored");
        }
        Files.createDirectories(run.resolve("evidence"));
        CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        pending = result;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target, staging, 0L, () -> {
            try {
                Map<String, Object> statistics;
                try (var mapped = staging.slice().map(true, false); NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                    ByteBuffer bytes = mapped.data();
                    int bad = 0, maxError = 0;
                    long[] sums = new long[4];
                    for (int pixel = 0; pixel < SIZE * SIZE; pixel++) {
                        int[] rgba = new int[4];
                        boolean mismatch = false;
                        for (int channel = 0; channel < 4; channel++) {
                            rgba[channel] = Byte.toUnsignedInt(bytes.get(pixel * 4 + channel));
                            sums[channel] += rgba[channel];
                            int difference = Math.abs(rgba[channel] - expected[channel]);
                            maxError = Math.max(maxError, difference);
                            mismatch |= difference > 1;
                        }
                        if (mismatch) bad++;
                        image.setPixel(pixel % SIZE, pixel / SIZE, rgba[3] << 24 | rgba[0] << 16 | rgba[1] << 8 | rgba[2]);
                    }
                    image.writeToFile(run.resolve("evidence").resolve(label + ".png"));
                    statistics = new LinkedHashMap<>();
                    statistics.put("case", label);
                    statistics.put("expectedRgba", expected);
                    statistics.put("meanRgba", java.util.Arrays.stream(sums).mapToDouble(value -> value / (double) (SIZE * SIZE)).toArray());
                    statistics.put("checkedPixels", SIZE * SIZE);
                    statistics.put("mismatchedPixels", bad);
                    statistics.put("maximumChannelError", maxError);
                    statistics.put("pixelsPassed", bad == 0);
                    statistics.put("source", "copyTextureToBuffer completion callback after GPU copy fence");
                }
                result.complete(statistics);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        }, 0);
    }

    private static final class SpriteFixture implements AutoCloseable {
        private final SpriteContents sprite;
        private final GpuBuffer uniforms;
        private final SpriteContents.AnimationState animation;
        private SpriteFixture(boolean interpolate) {
            NativeImage strip = new NativeImage(SIZE, SIZE * 2, false);
            strip.fillRect(0, 0, SIZE, SIZE, 0xffff0000);
            strip.fillRect(0, SIZE, SIZE, SIZE, 0xff00ff00);
            var metadata = new AnimationMetadataSection(Optional.of(List.of(new AnimationFrame(0, Optional.of(2)), new AnimationFrame(1, Optional.of(2)))),
                Optional.empty(), Optional.empty(), 2, interpolate);
            sprite = new SpriteContents(Identifier.fromNamespaceAndPath("iris_probe", interpolate ? "interpolate" : "blit"),
                new FrameSize(SIZE, SIZE), strip, Optional.of(metadata), List.of(), Optional.empty());
            ByteBuffer data = ByteBuffer.allocateDirect(SpriteContents.UBO_SIZE).order(ByteOrder.nativeOrder());
            new Matrix4f().translation(-1, -1, 0).scale(2, 2, 1).get(0, data);
            new Matrix4f().get(64, data);
            data.putFloat(128, 0).putFloat(132, 0).putInt(136, 0);
            uniforms = RenderSystem.getDevice().createBuffer(() -> "Atlas fixture SpriteAnimationInfo", GpuBuffer.USAGE_UNIFORM, data);
            animation = sprite.createAnimationState(uniforms.slice(), SpriteContents.UBO_SIZE);
            require(animation != null, "Vanilla SpriteContents did not create animation state");
        }
        private void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                // Sodium consumes this visibility mark at tick HEAD. The fixture
                // is deliberately off-atlas, so no world renderer marks it for us.
                if (sprite instanceof net.caffeinemc.mods.sodium.client.render.texture.SpriteContentsExtension tracked)
                    tracked.sodium$setActive(true);
                animation.tick();
            }
        }
        @Override public void close() { animation.close(); sprite.close(); uniforms.close(); }
    }

    private static Map<String, Object> classEvidence() throws Exception {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String name : List.of("net.irisshaders.iris.vulkan.IrisVulkanRenderPassBindings", "net.irisshaders.iris.probe.NativeAtlasAnimationProbe")) {
            var resource = NativeAtlasAnimationProbe.class.getClassLoader().getResource(name.replace('.', '/') + ".class");
            try (var bytes = resource.openStream()) {
                evidence.put(name, Map.of("source", resource.toString(), "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()))));
            }
        }
        return evidence;
    }

    private static void finish(Minecraft client, Path run, Throwable failure) {
        done = true;
        REPORT.put("passed", failure == null && RESULTS.size() == 3);
        REPORT.put("cases", RESULTS);
        if (failure != null) REPORT.put("failure", failure.toString());
        try {
            Files.createDirectories(run.resolve("evidence"));
            Files.writeString(run.resolve("evidence/atlas-animation-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            Files.writeString(run.resolve("atlas-smoke-result.txt"), failure == null ? "IRIS_ATLAS_GPU_PASS\n" : "IRIS_ATLAS_GPU_FAIL\n" + failure);
        } catch (Exception outputError) { outputError.printStackTrace(); }
        finally {
            if (blit != null) blit.close();
            if (interpolate != null) interpolate.close();
            if (targetView != null) targetView.close();
            if (target != null) target.close();
            if (staging != null) staging.close();
            client.stop();
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
