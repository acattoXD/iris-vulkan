package net.irisshaders.iris.probe;

import com.google.gson.GsonBuilder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.irisshaders.iris.vulkan.IrisVulkanShadowDrawPolicy;
import net.irisshaders.iris.vulkan.IrisVulkanShadowRenderer;
import net.irisshaders.iris.vulkan.IrisVulkanUniformSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.enchantment.Enchantments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reproduces the exact Wind Burst III held-mace trigger and a dropped foil item. */
public final class NativeEnchantedItemProbe {
    private static long handGlintDraws, worldGlintDraws;
    private static Map<String, Object> lastHandUniforms = Map.of(), lastWorldUniforms = Map.of();
    private NativeEnchantedItemProbe() { }

    public static void prepare(MinecraftServer server) {
        if (!server.getWorldData().getLevelName().startsWith("iris_native_probe_"))
            throw new IllegalStateException("Enchanted fixture is restricted to disposable probe worlds");
        var source = server.createCommandSourceStack().withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "item replace entity @p weapon.mainhand with minecraft:mace");
        server.getCommands().performPrefixedCommand(source, "enchant @p minecraft:wind_burst 3");
        var player = server.getPlayerList().getPlayers().getFirst();
        var windBurst = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.WIND_BURST);
        if (player.getMainHandItem().getEnchantments().getLevel(windBurst) != 3)
            throw new IllegalStateException("Exact Wind Burst III command did not enchant the held mace");
        // Left of center so the first-person mace cannot hide the dropped foil.
        var dropped = new ItemEntity(player.level(), -1.5, 64.8, 11.5, player.getMainHandItem().copy());
        dropped.setPickUpDelay(32767);
        dropped.setNoGravity(true);
        dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.level().addFreshEntity(dropped);
    }

    public static void writeEvidence(Minecraft client, Path evidence, String screenshot) throws Exception {
        var windBurst = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.WIND_BURST);
        int heldLevel = client.player.getMainHandItem().getEnchantments().getLevel(windBurst);
        if (heldLevel != 3) throw new IllegalStateException("Wind Burst III held item missing at capture " + screenshot);
        List<Map<String, Object>> dropped = new ArrayList<>();
        for (var entity : client.level.entitiesForRendering()) {
            if (entity instanceof ItemEntity item && item.getItem().getEnchantments().getLevel(windBurst) == 3)
                dropped.add(Map.of("position", List.of(item.getX(), item.getY(), item.getZ()), "item", item.getItem().toString(), "foil", item.getItem().hasFoil()));
        }
        boolean dropExpected = client.level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
        if (dropExpected && dropped.isEmpty()) throw new IllegalStateException("Dropped Wind Burst III mace missing at capture " + screenshot);
        var report = new LinkedHashMap<String, Object>();
        report.put("heldItem", client.player.getMainHandItem().toString());
        report.put("heldWindBurstLevel", heldLevel);
        report.put("heldFoil", client.player.getMainHandItem().hasFoil());
        report.put("hudHidden", client.gui.hud.isHidden());
        report.put("droppedEnchantedItems", dropped);
        report.put("droppedItemExpectedInThisDimension", dropExpected);
        report.put("handGlintDraws", handGlintDraws);
        report.put("worldGlintDraws", worldGlintDraws);
        report.put("lastHandGlintUniforms", lastHandUniforms);
        report.put("lastWorldGlintUniforms", lastWorldUniforms);
        report.put("screenshot", screenshot);
        report.put("scope", "Exact enchant-command trigger plus held and dropped foil state; continued rendering and images validate the runtime shadow/glint path. No impact attack is simulated.");
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve(screenshot.replace(".png", "") + "-enchanted-items.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    public static void traceGlint(RenderPipeline pipeline) {
        if (IrisVulkanShadowRenderer.active() || !IrisVulkanShadowDrawPolicy.shouldSkip(true, pipeline)) return;
        var fields = List.of(new IrisVulkanUniformSnapshot.Field("iris_NativeHandDraw", "int"),
            new IrisVulkanUniformSnapshot.Field("iris_ProjMat", "mat4"));
        var values = IrisVulkanUniformSnapshot.capture(fields).data();
        int hand = values.getInt(0);
        float[] projection = new float[16];
        for (int i = 0; i < projection.length; i++) projection[i] = values.getFloat(16 + i * 4);
        var state = Map.<String, Object>of("nativeHandDraw", hand, "projection", projection, "pipeline", pipeline.getLocation().toString());
        if (hand != 0) { handGlintDraws++; lastHandUniforms = state; }
        else { worldGlintDraws++; lastWorldUniforms = state; }
    }

    public static void requireMainViewCoverage() {
        if (handGlintDraws == 0 || worldGlintDraws == 0)
            throw new IllegalStateException("Expected actual held and world glint draws; got hand=" + handGlintDraws + ", world=" + worldGlintDraws);
    }
}
