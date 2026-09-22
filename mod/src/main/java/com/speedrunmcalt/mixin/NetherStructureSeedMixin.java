package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.server.world.ServerWorld;

/**
 * Places nether structures from the NETHER seed, not the world seed.
 *
 * A match world uses two seeds: the overworld generator gets
 * overworldSeed and the nether generator gets netherSeed. That is the
 * Divine Travel countermeasure - knowing one dimension must not tell
 * you the other.
 *
 * Biomes honoured it. Structures never did. ChunkStatus.STRUCTURE_STARTS
 * calls
 *
 *     generator.setStructureStarts(accessor, chunk, manager, world.getSeed())
 *
 * and world.getSeed() is the WORLD seed - the overworld's - regardless
 * of which dimension's generator is running. So every bastion and
 * fortress in a match nether was laid out by overworldSeed, while every
 * coordinate we computed and shipped came from netherSeed. They are
 * different numbers, so the coordinates were simply wrong.
 *
 * The cost was not subtle and took far too long to find: a player
 * walked to three shipped bastion coordinates and found empty nether,
 * and the loot top-up scanned that emptiness and reported "no chests"
 * while working exactly as designed. It was explained away three times
 * - as an anchor-versus-centre offset, then as cubiomes being wrong,
 * then as locateStructure being wrong - because every probe used a
 * world generated from a SINGLE seed, which cannot reproduce a
 * two-seed bug. The harness did not match the product.
 *
 * This substitutes the nether generator's own seed. Identified by its
 * biome source: MultiNoiseBiomeSource is the nether's alone in 1.16.1.
 *
 * Scoped to match worlds. A practice world has one seed for both
 * dimensions, so there is nothing to correct and nothing is touched.
 */
@Mixin(ChunkGenerator.class)
public abstract class NetherStructureSeedMixin {
	private static boolean speedrunmcalt$logged = false;

	@ModifyVariable(method = "setStructureStarts", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private long speedrunmcalt$useNetherSeed(long worldSeed) {
		if (!MatchState.inMatch() || MatchState.netherSeed == 0) {
			return worldSeed;
		}
		BiomeSource source = ((ChunkGenerator) (Object) this).getBiomeSource();
		if (!(source instanceof MultiNoiseBiomeSource)) {
			return worldSeed; // overworld or end - leave alone
		}
		if (worldSeed == MatchState.netherSeed) {
			return worldSeed; // already right, nothing to say
		}
		if (!speedrunmcalt$logged) {
			speedrunmcalt$logged = true;
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Nether structures seeded {} -> {} (was using the overworld seed)",
					worldSeed, MatchState.netherSeed);
		}
		return MatchState.netherSeed;
	}

	/**
	 * The other half of the same bug: LOCATING a nether structure.
	 *
	 * ChunkGenerator.locateStructure passes world.getSeed() to
	 * StructureFeature.locateStructure, exactly as setStructureStarts
	 * does - so with only the fix above, a match world GENERATES its
	 * nether from netherSeed and then SEARCHES it with overworldSeed.
	 * Those disagree, so the search walks a grid of candidate positions
	 * that belong to a different world and reports nothing there.
	 *
	 * Caught by a probe that asked both questions at once: it found
	 * twelve containers standing at the shipped bastion coordinate and
	 * locateStructure, in that same world, said there was no bastion
	 * within a hundred regions. The structure was real; the locator was
	 * looking in the wrong world.
	 *
	 * That mattered once before. "locateStructure is unreliable for
	 * bastions" was the third of three wrong explanations for empty
	 * nether, and it was wrong in an instructive way - the locator is
	 * perfectly reliable, it was just being handed the wrong seed, and
	 * so was everything else.
	 */
	@Redirect(
			method = "locateStructure",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/server/world/ServerWorld;getSeed()J"))
	private long speedrunmcalt$locateWithNetherSeed(ServerWorld world) {
		long worldSeed = world.getSeed();
		if (!MatchState.inMatch() || MatchState.netherSeed == 0) {
			return worldSeed;
		}
		BiomeSource source = ((ChunkGenerator) (Object) this).getBiomeSource();
		if (!(source instanceof MultiNoiseBiomeSource)) {
			return worldSeed;
		}
		return MatchState.netherSeed;
	}
}
