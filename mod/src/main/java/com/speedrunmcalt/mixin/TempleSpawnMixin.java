package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps hostile mobs and bats out of this seed's structure.
 *
 * Not for safety - for sound. Runners locate underground structures by
 * "pie-ray", reading the F3 pie chart for the audio and entity load a
 * nearby structure produces. Mobs rattling around inside a temple
 * pollute that reading, so a technique that should be precise turns
 * into guesswork.
 *
 * Bats are included deliberately: they are harmless and noisy, which
 * is exactly the problem.
 *
 * Only applies inside the structure's own box, and only in a match.
 * Everywhere else, and in every other world, spawning is untouched.
 *
 * Applies to every overworld seed type, not just desert temples. The
 * noise problem is the same wherever a runner is reading the pie chart,
 * and a guarantee that held for one structure type and not the rest
 * would be luck of the draw all over again.
 */
@Mixin(MobEntity.class)
public abstract class TempleSpawnMixin {
	@Inject(method = "canSpawn(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/entity/SpawnReason;)Z",
			at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$keepTempleQuiet(WorldAccess world, SpawnReason reason,
			CallbackInfoReturnable<Boolean> cir) {
		if (MatchState.quietBox == null) {
			return;
		}
		MobEntity self = (MobEntity) (Object) this;
		if (!(self instanceof HostileEntity) && !(self instanceof BatEntity)) {
			return;
		}
		// Natural spawning only. A spawner, an egg, or anything the
		// player causes is left alone - the point is ambient noise, not
		// removing mobs from the game.
		if (reason != SpawnReason.NATURAL && reason != SpawnReason.CHUNK_GENERATION) {
			return;
		}
		if (MatchState.quietBox.contains(self.getBlockPos())) {
			cir.setReturnValue(false);
		}
	}
}
