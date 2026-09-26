package net.irisshaders.iris.probe;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicitly invoked fixture for disposable probe worlds; no hook or automatic game changes. */
public final class NativeTransientEffectsProbe {
	private final Minecraft client;
	private final UUID playerId;
	private GameType previousPlayerMode;
	private UUID burningEntity;
	private int emissionFrames;

	public NativeTransientEffectsProbe(Minecraft client) {
		this.client = client;
		assertDisposableProbe();
		if (client.player == null || client.level == null || client.level.dimension() != Level.OVERWORLD) {
			throw new IllegalStateException("Run transient-effect probes from the prepared Overworld scene");
		}
		playerId = client.player.getUUID();
	}

	/** Places normal/soul fire and a stationary burning pig on the existing y=63 probe platform. */
	public CompletableFuture<Void> prepareFireScene() {
		MinecraftServer server = assertDisposableProbe();
		return onServer(server, () -> {
			var level = server.getLevel(Level.OVERWORLD);
			if (level == null) throw new IllegalStateException("Probe Overworld is unavailable");
			level.setBlock(new BlockPos(-6, 63, 3), Blocks.NETHERRACK.defaultBlockState(), 3);
			level.setBlock(new BlockPos(-6, 64, 3), Blocks.FIRE.defaultBlockState(), 3);
			level.setBlock(new BlockPos(-3, 63, 3), Blocks.SOUL_SOIL.defaultBlockState(), 3);
			level.setBlock(new BlockPos(-3, 64, 3), Blocks.SOUL_FIRE.defaultBlockState(), 3);
			var pig = EntityTypes.PIG.spawn(level, new BlockPos(2, 64, 3), EntitySpawnReason.COMMAND);
			if (pig == null) throw new IllegalStateException("Could not spawn burning probe entity");
			pig.setNoAi(true);
			pig.setInvulnerable(true);
			pig.igniteForSeconds(120);
			burningEntity = pig.getUUID();
		});
	}

	/**
	 * Call during the capture window. Mixed terrain/item/normal atlases intentionally
	 * share QuadParticleFeatureRenderer passes, exercising texture rebinding between layers.
	 */
	public Map<String, Integer> emitParticles() {
		assertDisposableProbe();
		if ((emissionFrames++ % 6) != 0) return Map.of();
		int terrain = 0, items = 0, flames = 0;
		for (int i = 0; i < 6; i++) {
			double y = 65.0 + (i % 3) * 0.18;
			double z = 8.0 + (i / 3) * 0.18;
			terrain += emit(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState()), -1.5, y, z);
			terrain += emit(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), -0.8, y, z);
			items += emit(new ItemParticleOption(ParticleTypes.ITEM, Items.DIAMOND), 0.0, y, z);
			flames += emit(ParticleTypes.FLAME, 0.8, y, z);
		}
		return Map.of("terrainParticlesCreated", terrain, "itemParticlesCreated", items, "flameParticlesCreated", flames);
	}

	private int emit(ParticleOptions options, double x, double y, double z) {
		var particle = client.particleEngine.createParticle(options, x, y, z, 0, 0.025, 0);
		if (particle == null) return 0;
		particle.setLifetime(80);
		return 1;
	}

	/** Capture separately after entity/placed-fire images, then call with false to restore the probe player. */
	public CompletableFuture<Void> setPlayerFireOverlay(boolean burning) {
		MinecraftServer server = assertDisposableProbe();
		return onServer(server, () -> {
			var player = server.getPlayerList().getPlayer(playerId);
			if (player == null) throw new IllegalStateException("Probe player is unavailable");
			if (burning) {
				if (previousPlayerMode == null) previousPlayerMode = player.gameMode.getGameModeForPlayer();
				player.setGameMode(GameType.SURVIVAL);
				player.igniteForSeconds(15);
			} else {
				player.clearFire();
				if (previousPlayerMode != null) player.setGameMode(previousPlayerMode);
				previousPlayerMode = null;
			}
		});
	}

	public UUID burningEntityId() {
		return burningEntity;
	}

	private MinecraftServer assertDisposableProbe() {
		if (Boolean.getBoolean("iris.vulkan.probe.interactive")) {
			throw new IllegalStateException("Transient fixtures never modify an interactive user game");
		}
		String configured = System.getProperty("iris.vulkan.probe.runDir");
		MinecraftServer server = client.getSingleplayerServer();
		if (configured == null || server == null
			|| !client.gameDirectory.toPath().toAbsolutePath().normalize().equals(Path.of(configured).toAbsolutePath().normalize())
			|| !server.getWorldData().getLevelName().startsWith("iris_native_probe_")) {
			throw new IllegalStateException("Transient fixtures require the configured disposable singleplayer probe world");
		}
		return server;
	}

	private static CompletableFuture<Void> onServer(MinecraftServer server, Runnable action) {
		CompletableFuture<Void> result = new CompletableFuture<>();
		server.execute(() -> {
			try { action.run(); result.complete(null); }
			catch (Throwable failure) { result.completeExceptionally(failure); }
		});
		return result;
	}
}
