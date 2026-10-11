package com.speedrunmcalt.mixin;

import com.speedrunmcalt.menu.MenuMusic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MusicTracker;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.MusicSound;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the music tracker still while MenuMusic says to - through the
 * power-on, and from a match being found until its world is loaded.
 * Holding skips the whole tick, so nothing starts and the next-song
 * timer does not run down.
 */
@Mixin(MusicTracker.class)
public class MusicTrackerMixin {
	@Shadow
	@Final
	private MinecraftClient client;

	@Shadow
	private SoundInstance current;

	@Shadow
	private int timeUntilNextSong;

	/**
	 * alt's menu track is played as MenuMusic's fadeable Track instead
	 * of the PositionedSoundInstance vanilla builds. Everything else
	 * play() does is repeated as it is: remember it as current, play it,
	 * and park the next-song timer until it ends.
	 */
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$playMenuTrack(MusicSound type, CallbackInfo ci) {
		if (!MenuMusic.isMenuMusic(type)) {
			return;
		}
		this.current = MenuMusic.start(this.client);
		this.client.getSoundManager().play(this.current);
		this.timeUntilNextSong = Integer.MAX_VALUE;
		ci.cancel();
	}

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$hold(CallbackInfo ci) {
		if (MenuMusic.holding(this.client)) {
			ci.cancel();
		}
	}
}
