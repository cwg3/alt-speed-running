package com.speedrunmcalt.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * Short audio cues for match events.
 *
 * The HUD is in the corner and a runner is looking at the world, so
 * anything that only appears on screen is missed. These are all
 * existing vanilla sounds, played on the master channel so they sit
 * above ambience but respect the player's volume settings.
 *
 * Deliberately short and few. A sound for every split would become
 * noise a player learns to ignore, which is worse than silence - so
 * these mark only the moments where something changed that a runner
 * would otherwise have to check for: a match forming, the race
 * starting, the opponent taking a lead, and the result.
 */
public final class Cues {
	/**
	 * What PositionedSoundInstance.master(sound, pitch) uses.
	 *
	 * Named rather than left implicit because it is surprisingly quiet
	 * and it is not obvious from the call: the two-argument master()
	 * hardcodes 0.25f. Every cue that does not say otherwise is
	 * playing at a quarter volume.
	 */
	private static final float DEFAULT_VOLUME = 0.25f;

	/**
	 * Twice the amplitude of DEFAULT_VOLUME - +6 dB.
	 *
	 * SoundSystem.getAdjustedVolume clamps volume * categoryVolume to
	 * 1.0, so 0.25 leaves 12 dB of headroom and this spends half of
	 * it. Anything above 1.0f is silently discarded by the clamp, so
	 * that is the ceiling for any future cue.
	 */
	private static final float LOUD = 0.5f;

	private Cues() {
	}

	/**
	 * An opponent was found and the world is being built.
	 *
	 * The level-up chime. This is the moment a player is waiting for
	 * while staring at a queue timer, so it gets the game's own "this
	 * is good news" sound rather than a blip.
	 */
	public static void matchFound() {
		play(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f);
	}

	/**
	 * The seed reveal screen just opened - five seconds to plan.
	 *
	 * A beacon activating: deep, resonant, and long enough to carry
	 * across the whole reveal rather than blipping and vanishing.
	 */
	public static void seedReveal() {
		play(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.9f, LOUD);
	}

	/*
	 * There is deliberately NO cue at zero on the countdown. The reveal
	 * sound already says "get ready", the number on screen says the
	 * rest, and a runner about to move does not need another noise in
	 * the same three seconds.
	 *
	 * There was also an opponent-split cue - an enderman teleport on
	 * every split. Removed: on a fast test pace it fired six times in
	 * fifteen minutes, and a sound a player learns to tune out is worse
	 * than none. Their splits are on the HUD in magenta, which is where
	 * that information belongs.
	 */

	/** The advancement chime - the game's own "you did it". */
	public static void victory() {
		play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
	}

	/**
	 * The burning sizzle - random/fizz.
	 *
	 * Asked for as "the loop that happens when PLAYER tried to swim in
	 * lava". Nothing is attached to a death MESSAGE, so the question is
	 * which FILE that moment sounds like, and the asset index answers
	 * it: entity.generic.burn plays random/fizz, the hiss that repeats
	 * the whole time a player is cooking. That is the loop.
	 *
	 * The previous attempt was entity.player.death, on the reasoning
	 * that it lands when the death text does. It does - but the index
	 * shows entity.player.death and entity.player.hurt resolve to the
	 * SAME three files (damage/hit1-3). There is no distinct death
	 * sound in Java Edition, so a defeat cue built on it is a damage
	 * sound, which is what it was heard as. Guessing from the event
	 * NAME was the mistake; the files are what a player hears.
	 */
	public static void defeat() {
		play(SoundEvents.ENTITY_GENERIC_BURN, 1.0f);
	}

	/**
	 * Master-channel sound, always on the client thread.
	 *
	 * Called from the poll thread and the matchmaker thread as well as
	 * the client one, and the sound manager is not safe off-thread.
	 */
	private static void play(SoundEvent sound, float pitch) {
		play(sound, pitch, DEFAULT_VOLUME);
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			return;
		}
		client.execute(() -> {
			if (client.getSoundManager() == null) {
				com.speedrunmcalt.SpeedrunMcAlt.LOGGER.warn(
						"[speedrunmcalt] cue {} skipped - no sound manager", sound.getId());
				return;
			}
			client.getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
			// Logged so "did it fire?" is answerable from the log rather
			// than from whether someone heard it. A cue that silently
			// does nothing is indistinguishable from one that plays too
			// quietly to notice.
			com.speedrunmcalt.SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] cue {} (pitch {} vol {})", sound.getId(), pitch, volume);
		});
	}
}
