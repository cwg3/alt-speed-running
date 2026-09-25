package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The run ends at the fountain, not at the dragon.
 *
 * Killing the dragon used to complete the match. That is the wrong
 * moment: the dragon dying is a split, and the race is not over until
 * the player has got back to the exit portal and jumped in. Two
 * runners can kill within seconds of each other and the one who is
 * standing on the fountain wins - which is the whole point of the
 * ending.
 *
 * THE STRONGHOLD PORTAL IS THE SAME BLOCK. EndPortalBlock is what a
 * player walks into to ENTER the End, so without the dimension check
 * below this would fire on the way in and end the match at the
 * stronghold. The exit fountain is the one in the End; the entry
 * portal is the one in the Overworld.
 *
 * Injected at the changeDimension call rather than at HEAD, so it
 * fires only where vanilla has actually decided to teleport - past its
 * own checks for riding, passengers and whether the entity may use
 * portals. Replicating those here would be a second copy of vanilla's
 * rules, free to drift from the first.
 *
 * Ordering needs no enforcement: the exit portal does not exist until
 * the dragon is dead, so the game guarantees what a prerequisite check
 * would only restate.
 */
@Mixin(EndPortalBlock.class)
public abstract class EndFountainMixin {
	@Inject(
			method = "onEntityCollision",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/Entity;changeDimension"
							+ "(Lnet/minecraft/server/world/ServerWorld;)Lnet/minecraft/entity/Entity;"))
	private void speedrunmcalt$runEndsAtTheFountain(BlockState state, World world, BlockPos pos,
			Entity entity, CallbackInfo ci) {
		if (world.isClient || !World.END.equals(world.getRegistryKey())) {
			return;
		}
		if (!(entity instanceof ServerPlayerEntity)) {
			return;
		}
		if (!MatchState.inMatch() || MatchState.replayMode) {
			return;
		}
		// The dragon has to be dead. The exit portal does not exist
		// until it is, so this is belt and braces against a future
		// where some other EndPortalBlock stands in the End.
		if (!MatchState.mySplits.containsKey("kill_dragon")) {
			return;
		}
		// Once. A player who re-enters the portal after the credits is
		// not finishing twice. The guard lives in FountainFinish, not
		// here: this class is in the mixin package, which is merged into
		// its target and cannot be referenced from anywhere else.
		com.speedrunmcalt.match.FountainFinish.claim();
	}
}
