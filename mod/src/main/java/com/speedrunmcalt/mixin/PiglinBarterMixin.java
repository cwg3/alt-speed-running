package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.BarterSchedule;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.SplitReporter;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
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
 * Barter contents come from BarterSchedule, which fixes them in
 * advance from the match seed. That does two things vanilla does not:
 * both players get the same trades in the same order, and every 72
 * barters guarantees 3 pearl trades and 6+ obsidian.
 *
 * The previous policy here - force something if a 20-barter window
 * produced nothing - only ever addressed luck WITHIN a run. It did
 * nothing about the far bigger unfairness of two players getting
 * different luck from each other, which is what decides matches.
 */
@Mixin(PiglinBrain.class)
public class PiglinBarterMixin {

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
	/**
	 * Replaces the rolled loot with this match's scheduled outcome.
	 *
	 * One real call site passes Collections.singletonList(...), which is
	 * immutable, so mutating the incoming list in place would throw
	 * there. @ModifyVariable substitutes a different list entirely,
	 * which the rest of the method then uses.
	 */
	// Signature is (capturedValue, ...originalMethodArgs) - the value
	// being modified comes FIRST, then the target method's own
	// arguments in order. Writing it as (piglin, list) compiles fine
	// and fails at mixin-apply time, which is when a piglin first
	// loads - a hard crash mid-run rather than a startup error.
	@ModifyVariable(method = "doBarter", at = @At("HEAD"), argsOnly = true)
	private static List<ItemStack> applyBarterSchedule(List<ItemStack> value,
			PiglinEntity piglin, List<ItemStack> list) {
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return value; // practice world - leave vanilla alone
		}
		if (!(piglin.world instanceof ServerWorld)) {
			return value;
		}

		// This runs on the server thread inside entity ticking, where an
		// exception is not an error message - it is a crash report and a
		// dead integrated server, with the client left in a world that
		// no longer has anything behind it. That happened for real: a
		// bad loot-context parameter here killed a run at the moment of
		// the player's first ever trade.
		//
		// A scheduled barter is a fairness feature. Losing it costs the
		// match its mirroring guarantee, which is bad; taking the run
		// down with it is worse. So anything unexpected falls back to
		// the vanilla roll the game already produced.
		try {
			List<ItemStack> scheduled = BarterSchedule
					.get((ServerWorld) piglin.world, MatchState.overworldSeed, piglin)
					.next();
			return scheduled.isEmpty() ? value : new ArrayList<>(scheduled);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error(
					"[speedrunmcalt] Barter schedule failed - falling back to vanilla roll", e);
			return value;
		}
	}
}
