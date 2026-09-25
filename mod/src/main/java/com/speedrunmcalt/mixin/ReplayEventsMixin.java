package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.ReplayRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The moments a replay is actually watched for.
 *
 * Positions say where somebody was. They cannot say that this was the
 * death that ended the run, or the barter that finally gave up the
 * pearls - and none of that is reproducible from the seed, so it has
 * to be recorded as it happens.
 *
 * Server-side, because that is where deaths and pickups are decided.
 * ReplayRecorder ignores anything outside a live match, so these are
 * inert in a replay and in ordinary singleplayer.
 */
@Mixin(LivingEntity.class)
public abstract class ReplayEventsMixin {
	/**
	 * Anything dying, named by what killed it.
	 *
	 * Both directions matter and they read differently: the player
	 * dying is usually the end of the run, and a mob dying is usually
	 * the player getting something. Recording both and letting
	 * playback distinguish them beats guessing here.
	 */
	@Inject(method = "onDeath", at = @At("HEAD"))
	private void speedrunmcalt$recordDeath(DamageSource source, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.world.isClient) {
			return;
		}
		String what = self.getType().getTranslationKey();
		String by = source.getName();

		if (self instanceof PlayerEntity) {
			// The death MESSAGE, not just the source: "tried to swim in
			// lava" is what a viewer needs, and reconstructing it from
			// a source name loses the distinction between falling and
			// being knocked off.
			ReplayRecorder.event("death", source.getDeathMessage(self).getString());
		} else {
			Entity attacker = source.getAttacker();
			if (attacker instanceof PlayerEntity) {
				// Only kills the PLAYER caused. A zombie burning at
				// dawn thirty blocks away is not part of anybody's run.
				ReplayRecorder.event("kill", what);
			}
		}
	}
}
