package com.speedrunmcalt.mixin;

import net.minecraft.client.gui.hud.SpectatorHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Number keys drive the replay, not the spectator menu.
 *
 * A replay puts the viewer in spectator, and in spectator vanilla
 * routes 1-9 to SpectatorHud.selectSlot - the teleport-to-player menu.
 * The replay controls sit on that same strip, so both fired at once:
 * pressing 2 to rewind ten seconds also opened a spectator menu over
 * the thing being watched.
 *
 * Cancelled only while a replay is playing. Outside one, spectator
 * behaves exactly as vanilla does - this is not a general change to
 * how spectator works, just a claim on those keys for the seconds the
 * replay owns the screen.
 */
@Mixin(SpectatorHud.class)
public class SpectatorSlotMixin {
	@Inject(method = "selectSlot", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$replayOwnsNumberKeys(int slot, CallbackInfo ci) {
		if (com.speedrunmcalt.replay.ReplayPlayback.active()) {
			ci.cancel();
		}
	}
}
