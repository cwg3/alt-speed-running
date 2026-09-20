package com.speedrunmcalt.mixin;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.mob.PiglinBrain;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 */
@Mixin(PiglinBrain.class)
public class PiglinBarterMixin {
	@Inject(method = "doBarter", at = @At("HEAD"))
	private static void onDoBarter(PiglinEntity piglin, List<ItemStack> list, CallbackInfo ci) {
		if (MatchState.matchStartMillis < 0 || MatchState.firstBarterLogged) {
			return;
		}
		MatchState.firstBarterLogged = true;
		long elapsedMs = System.currentTimeMillis() - MatchState.matchStartMillis;
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] SPLIT piglin_barter at {} ms", elapsedMs);
	}
}
