package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.SplitEvents;
import com.speedrunmcalt.match.SplitReporter;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.PlayerAdvancementTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Detects run milestones (enter nether, obtain rod, kill dragon) via
 * vanilla's own advancement system instead of separate bespoke hooks
 * for dimension-change/item-pickup/mob-death - each of those milestones
 * already corresponds to exactly one vanilla advancement criterion
 * (confirmed against the real advancement JSON files), so hooking the
 * single point where any criterion is granted covers all three.
 */
@Mixin(PlayerAdvancementTracker.class)
public class SplitTrackerMixin {
	@Inject(method = "grantCriterion", at = @At("RETURN"))
	private void onGrantCriterion(Advancement advancement, String criterionName,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return; // criterion was already satisfied, not a new grant
		}
		if (MatchState.matchStartMillis < 0) {
			return; // no active match
		}

		String splitName = SplitEvents.nameFor(advancement.getId());
		if (splitName == null) {
			return; // not one of our tracked milestones
		}

		long elapsedMs = System.currentTimeMillis() - MatchState.matchStartMillis;
		SplitReporter.report(splitName, elapsedMs);
	}
}
