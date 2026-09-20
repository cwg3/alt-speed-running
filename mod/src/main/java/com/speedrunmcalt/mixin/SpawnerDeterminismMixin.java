package com.speedrunmcalt.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.MobSpawnerLogic;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Standardizes mob-spawner (blaze spawners included) position/timing
 * across identical seeds - confirmed via decompiled source that
 * MobSpawnerLogic.update() draws every position/delay roll from
 * world.random, a SINGLE Random instance SHARED across every
 * random-consuming system in the game (weather, mob AI, block ticks,
 * every other spawner, etc). Even with an identical world seed, two
 * players' shared random state diverges the moment their play differs
 * even slightly - so "the same" spawner produces different actual
 * spawn positions and timing for each of them, despite racing the same
 * seed.
 *
 * Fix: swap in a deterministic Random - seeded by the spawner's
 * absolute block position, which is identical for both players racing
 * the same seed pair - for the duration of update(), then restore the
 * real world random afterward. update() covers updateSpawns() too,
 * since that's called synchronously from within it, not separately.
 *
 * Self-healing note: if update() ever throws between the swap-in and
 * swap-out (not expected in normal play), the HEAD injection detects
 * world.random is still our own deterministic instance on the next
 * call and skips re-capturing it as "the real one" - spawners tick
 * every game tick while a player is nearby, so this limits the blast
 * radius of that edge case to at most a few ticks rather than leaving
 * global randomness permanently substituted.
 */
@Mixin(MobSpawnerLogic.class)
public abstract class SpawnerDeterminismMixin {
	@Shadow
	public abstract World getWorld();

	@Shadow
	public abstract BlockPos getPos();

	private Random speedrunmcalt$deterministicRandom;
	private Random speedrunmcalt$realRandom;

	@Inject(method = "update", at = @At("HEAD"))
	private void speedrunmcalt$swapIn(CallbackInfo ci) {
		World world = this.getWorld();
		if (world == null || world.isClient) {
			return;
		}
		if (speedrunmcalt$deterministicRandom == null) {
			speedrunmcalt$deterministicRandom = new Random(this.getPos().asLong());
		}
		if (world.random != speedrunmcalt$deterministicRandom) {
			speedrunmcalt$realRandom = world.random;
			((WorldRandomAccessor) world).speedrunmcalt$setRandom(speedrunmcalt$deterministicRandom);
		}
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void speedrunmcalt$swapOut(CallbackInfo ci) {
		World world = this.getWorld();
		if (world == null || world.isClient || speedrunmcalt$realRandom == null) {
			return;
		}
		((WorldRandomAccessor) world).speedrunmcalt$setRandom(speedrunmcalt$realRandom);
		speedrunmcalt$realRandom = null;
	}
}
