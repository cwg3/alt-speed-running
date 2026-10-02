package com.speedrunmcalt.mixin;

import com.speedrunmcalt.debug.HarnessNetherSeed;
import net.minecraft.world.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lets a HARNESS server build its nether the way a match world does.
 *
 * A match nether is not "the nether of netherSeed". MatchWorldCreator
 * builds the nether generator from netherSeed, but vanilla runs the
 * carvers and features with world.getSeed() - the overworld's - so the
 * terrain a player walks is netherSeed's noise with overworldSeed's
 * caves cut through it. A one-seed dedicated server reproduces neither
 * half on its own: found on 2026-10-01 when an arrival the harness
 * priced as sealed in netherrack had been walked straight out of in
 * play, down a carved tunnel.
 *
 * With level-seed set to the overworld seed and nethergen.txt holding
 * the nether seed, this hands the nether generator netherSeed and leaves
 * the world seed alone, which is exactly the match world's split.
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
