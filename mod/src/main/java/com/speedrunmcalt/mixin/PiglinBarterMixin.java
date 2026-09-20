package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.SplitReporter;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Piglin bartering has no advancement trigger in 1.16.1 (new that
 * version, doesn't seem to have gotten one yet - confirmed by checking
 * every registered Criterion type), so this is a direct hook instead of
 * reusing SplitTrackerMixin's advancement-based approach.
 *
 * doBarter() is called exactly once per successful barter, right before
 * the rolled loot is given to the player - confirmed by reading the
 * real decompiled PiglinBrain source rather than guessing at behavior.
 *
 * Fairness policy is OUR OWN documented choice (window size and
 * guaranteed amounts below), not an attempt to reverse-engineer any
 * other platform's undisclosed exact numbers: every BARTER_WINDOW
 * barters, guarantee at least some obsidian and ender pearls if none
 * were rolled naturally in that window.
 */
@Mixin(PiglinBrain.class)
public class PiglinBarterMixin {
	private static final int BARTER_WINDOW = 20;
	private static final int GUARANTEED_OBSIDIAN = 1;
	private static final int GUARANTEED_PEARLS = 2;

	@Inject(method = "doBarter", at = @At("HEAD"))
	private static void logFirstBarter(PiglinEntity piglin, List<ItemStack> list, CallbackInfo ci) {
		if (MatchState.matchStartMillis < 0 || MatchState.firstBarterLogged) {
			return;
		}
		MatchState.firstBarterLogged = true;
		long elapsedMs = System.currentTimeMillis() - MatchState.matchStartMillis;
		SplitReporter.report("piglin_barter", elapsedMs);
	}

	// One real call site for doBarter passes Collections.singletonList(...)
	// (immutable) - mutating the incoming list in place would throw
	// UnsupportedOperationException there. @ModifyVariable substitutes a
	// genuinely mutable copy instead, which the rest of the method then
	// uses for real (confirmed this actually takes effect, not just
	// compiles, via a live test).
	@ModifyVariable(method = "doBarter", at = @At("HEAD"), argsOnly = true)
	private static List<ItemStack> applyBarterFairness(List<ItemStack> list) {
		if (MatchState.matchStartMillis < 0) {
			return list;
		}

		List<ItemStack> mutable = new ArrayList<>(list);
		for (ItemStack stack : mutable) {
			if (stack.getItem() == Items.OBSIDIAN) {
				MatchState.obsidianThisWindow += stack.getCount();
			} else if (stack.getItem() == Items.ENDER_PEARL) {
				MatchState.pearlsThisWindow += stack.getCount();
			}
		}

		MatchState.barterCount++;
		if (MatchState.barterCount % BARTER_WINDOW == 0) {
			if (MatchState.obsidianThisWindow == 0) {
				mutable.add(new ItemStack(Items.OBSIDIAN, GUARANTEED_OBSIDIAN));
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Fairness: forcing {} obsidian (barter {})",
						GUARANTEED_OBSIDIAN, MatchState.barterCount);
			}
			if (MatchState.pearlsThisWindow == 0) {
				mutable.add(new ItemStack(Items.ENDER_PEARL, GUARANTEED_PEARLS));
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Fairness: forcing {} ender pearls (barter {})",
						GUARANTEED_PEARLS, MatchState.barterCount);
			}
			MatchState.obsidianThisWindow = 0;
			MatchState.pearlsThisWindow = 0;
		}

		return mutable;
	}
}
