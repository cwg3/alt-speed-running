package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.DropSchedule;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HoglinEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces hoglin drops with this match's scheduled sequence.
 *
 * Food decides whether a runner keeps moving, and vanilla hands it out
 * unevenly enough to matter: the same number of kills can leave one
 * player fed and the other hungry. The per-kill count still varies -
 * the texture of killing hoglins is unchanged - but the total over a
 * cycle is fixed and both players get the identical sequence.
 *
 * Two deliberate deviations, both small and both worth stating:
 *
 *   - A hoglin killed by fire drops COOKED porkchop in vanilla. This
 *     always drops raw, because the schedule is about quantity and
 *     threading the damage type through it would buy nothing a runner
 *     would notice.
 *   - Hides are mirrored on the same cycle rather than left to chance.
 *     They matter little, but leaving one drop mirrored and the other
 *     random would be an odd half-measure.
 */
@Mixin(LivingEntity.class)
public abstract class HoglinDropMixin {
	@Inject(method = "dropLoot", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$scheduleHoglinDrops(DamageSource source, boolean causedByPlayer,
			CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof HoglinEntity)) {
			return;
		}
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world - leave vanilla alone
		}
		if (!causedByPlayer) {
			return; // only player kills are part of the run
		}

		// Never let a drop schedule take down the server thread. This
		// runs inside entity ticking, where an exception is a crash
		// report and a dead integrated server - which is exactly what a
		// bad loot context did to a barter earlier.
		try {
			int[] drop = DropSchedule.nextHoglinDrop(MatchState.overworldSeed);
			if (drop[0] > 0) {
				self.dropStack(new ItemStack(Items.PORKCHOP, drop[0]));
			}
			if (drop[1] > 0) {
				self.dropStack(new ItemStack(Items.LEATHER, drop[1]));
			}
			ci.cancel();
		} catch (Exception e) {
			com.speedrunmcalt.SpeedrunMcAlt.LOGGER.error(
					"[speedrunmcalt] Hoglin drop schedule failed - falling back to vanilla", e);
		}
	}
}
