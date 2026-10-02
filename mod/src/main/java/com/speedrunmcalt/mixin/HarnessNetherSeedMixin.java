package com.speedrunmcalt.mixin;

import com.speedrunmcalt.debug.HarnessNetherSeed;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets a HARNESS server build a nether the way a match world does.
 *
 * A match world's nether generator is built from netherSeed while the
 * world seed is the overworld's. A dedicated server has one seed for
 * both, so on its own it reproduces neither half. With level-seed set
 * to the overworld seed and nethergen.txt holding the nether seed, this
 * hands the nether generator netherSeed and leaves the world seed alone.
 *
 * Which nether that gives depends on MatchState:
 *
 *   - left unset: a WorldSetupVersion 1 nether, where vanilla carved
 *     caves and laid surface and features with the overworld seed. The
 *     only way to rebuild what a version 1 match actually looked like;
 *     the nether arrival guard was calibrated in exactly this world.
 *   - set as a match (ArrivalCheckHook's "match" line): the current
 *     nether, which NetherStructureSeedMixin and NetherRegionSeedMixin
 *     make identical to the one-seed world of netherSeed. Checked by
 *     hashing chunks of both.
 *
 * Dedicated server only. The client calls the same factory when it
 * builds a match world (for the defaults it then discards), and must
 * never see this.
 */
@Mixin(DimensionType.class)
public abstract class HarnessNetherSeedMixin {
	@ModifyVariable(method = "createNetherGenerator", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static long speedrunmcalt$harnessNetherSeed(long seed) {
		Long override = HarnessNetherSeed.get();
		return override == null ? seed : override;
	}
}
