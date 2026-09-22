package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps how many wither skeletons may crowd one spot in a fortress.
 *
 * Vanilla will happily stack a "choke" horde into a corridor - enough
 * wither skeletons in one place that the corridor cannot be passed and
 * cannot be fought through. Whether a runner meets one is luck, and it
 * can cost a run outright while the other player walks the same
 * fortress unobstructed.
 *
 * The cap is on DENSITY, not on total population. Wither skeletons
 * still spawn, still roam the fortress, and are still dangerous; what
 * cannot happen is a dozen of them occupying the same few blocks. A
 * runner who wants skulls still has to fight for them.
 *
 * **[ours]** - the numbers here are invented, not taken from the
 * standard. Four within twelve blocks is a guess at the line between
 * "a fight" and "a wall", made from first principles rather than
 * measurement, and it should be put to experienced runners before it
 * is treated as settled.
 */
@Mixin(MobEntity.class)
public abstract class WitherSkeletonCrowdMixin {
	/** Most wither skeletons allowed within CROWD_RADIUS of a new one. */
	private static final int MAX_CROWD = 4;
	private static final double CROWD_RADIUS = 12.0;

	@Inject(method = "canSpawn(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/entity/SpawnReason;)Z",
			at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$capWitherSkeletonCrowd(WorldAccess world, SpawnReason reason,
			CallbackInfoReturnable<Boolean> cir) {
		MobEntity self = (MobEntity) (Object) this;
		// Cheapest test first: this runs on every spawn attempt in the
		// world, and all but a handful are not wither skeletons.
		if (!(self instanceof WitherSkeletonEntity)) {
			return;
		}
		if (!MatchState.inMatch() || MatchState.overworldSeed == 0) {
			return; // practice world - leave vanilla alone
		}
		// Natural spawning only. A spawner or a player-caused spawn is
		// left alone; the point is ambient crowding, not removing the
		// mob from the game.
		if (reason != SpawnReason.NATURAL && reason != SpawnReason.CHUNK_GENERATION) {
			return;
		}
		if (!(world instanceof ServerWorld)) {
			return;
		}

		Box around = self.getBoundingBox().expand(CROWD_RADIUS);
		// getEntities(Class, Box, Predicate) is the 1.16.1 name;
		// getEntitiesByClass is a later mapping and does not exist here.
		int nearby = ((ServerWorld) world)
				.getEntities(WitherSkeletonEntity.class, around, e -> e.isAlive())
				.size();
		if (nearby >= MAX_CROWD) {
			cir.setReturnValue(false);
		}
	}
}
