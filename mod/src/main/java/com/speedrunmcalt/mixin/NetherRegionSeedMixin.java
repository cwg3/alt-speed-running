package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Seeds a match nether's chunk regions with netherSeed.
 *
 * A ChunkRegion is the window worldgen writes through, and it takes its
 * seed from world.getSeed() - the overworld's, in every dimension. Three
 * passes read it: generateFeatures seeds ore, fire, glowstone, basalt,
 * fungi and structure pieces from it; buildSurface hands it to the
 * surface builders that lay nylium, soul sand and gravel; and the
 * region's biome access, which decides which biome a column near a
 * border belongs to, is hashed from it. So a match nether had the
 * overworld seed's decoration and surface on netherSeed's terrain.
 *
 * One read covers all three. With this and the carver fix in
 * NetherStructureSeedMixin, a match nether is block for block the
 * one-seed world of netherSeed - checked by hashing chunks of both.
 */
@Mixin(ChunkRegion.class)
public abstract class NetherRegionSeedMixin {
	@Redirect(
			method = "<init>",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/world/ServerWorld;getSeed()J"))
	private long speedrunmcalt$netherRegionSeed(ServerWorld world) {
		long worldSeed = world.getSeed();
		if (!MatchState.ourWorld() || MatchState.netherSeed == 0
				|| world.getRegistryKey() != World.NETHER) {
			return worldSeed;
		}
		return MatchState.netherSeed;
	}
}
