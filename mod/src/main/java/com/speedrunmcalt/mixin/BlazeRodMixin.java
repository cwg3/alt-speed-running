package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.DropSchedule;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces blaze rod drops with this match's scheduled sequence.
 *
 * Vanilla is a flat 50% per kill with no floor, so one runner can take
 * seven rods from ten blazes while the other takes three - a gap that
 * decides matches on a coin flip. DropSchedule makes the sequence
 * identical for both players and caps the drought.
 *
 * Blazes drop nothing but rods, so substituting the whole drop is
 * safe. XP is handled separately by the game and is untouched.
 */
@Mixin(LivingEntity.class)
public abstract class BlazeRodMixin {
	@Inject(method = "dropLoot", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$scheduleBlazeRods(DamageSource source, boolean causedByPlayer,
			CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof BlazeEntity)) {
			return;
		}
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world - leave vanilla alone
		}
		if (!causedByPlayer) {
			return; // only player kills are part of the run
		}

		if (DropSchedule.blazeRods(MatchState.overworldSeed).next()) {
			self.dropStack(new ItemStack(Items.BLAZE_ROD, 1));
		}
		ci.cancel();
	}
}
