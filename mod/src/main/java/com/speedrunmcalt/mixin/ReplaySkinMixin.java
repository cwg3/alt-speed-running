package com.speedrunmcalt.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replay ghosts wear the real player's skin.
 *
 * Vanilla's getSkinTexture asks the tab list who this player is, and a
 * replay ghost is never in the tab list - it is a client-side body the
 * mod spawned. So every ghost came out as Steve, and a replay of two
 * runners showed two identical strangers. Watching yourself make a
 * mistake is most of what a replay is for, and it does not work if the
 * figure on screen is not recognisably you.
 *
 * The skin is loaded from the account uuid the replay payload is keyed
 * by, which is the real one - the ghost's own profile uuid is derived
 * from the username and resolves to nothing.
 *
 * getModel too, so a slim-armed skin is not stretched over a
 * classic-armed body.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class ReplaySkinMixin {
	@Inject(method = "getSkinTexture", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$ghostSkin(CallbackInfoReturnable<Identifier> cir) {
		String uuid = com.speedrunmcalt.replay.ReplayPlayback.ghostUuid(
				(AbstractClientPlayerEntity) (Object) this);
		if (uuid == null) {
			return;
		}
		cir.setReturnValue(com.speedrunmcalt.replay.ReplaySkins.forPlayer(
				uuid, com.speedrunmcalt.replay.ReplayPlayback.usernameFor(uuid)));
	}

	@Inject(method = "getModel", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$ghostModel(CallbackInfoReturnable<String> cir) {
		String uuid = com.speedrunmcalt.replay.ReplayPlayback.ghostUuid(
				(AbstractClientPlayerEntity) (Object) this);
		if (uuid == null) {
			return;
		}
		cir.setReturnValue(com.speedrunmcalt.replay.ReplaySkins.modelFor(uuid));
	}
}
