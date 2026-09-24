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
	 * The ceiling. SoundSystem.getAdjustedVolume clamps volume times
	 * the category volume to 1.0, so nothing above this exists - a cue
	 * asking for 2.0f gets exactly what 1.0f gets, and the number in
	 * the source would be a lie about how loud it is.
	 *
	 * Four times the amplitude of DEFAULT_VOLUME: +12 dB.
	 */
	private static final float MAX = 1.0f;

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
	 * The seed reveal screen just opened - ten seconds to plan.
	 *
	 * A beacon activating: deep, resonant, and long enough to carry
	 * across the whole reveal rather than blipping and vanishing.
	 */
	public static void seedReveal() {
		play(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.9f, MAX);
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
	 * A beacon powering down - block/beacon/deactivate, 3.50 seconds.
	 *
	 * The counterpart to seedReveal's beacon activate. A match opens
	 * with a beacon coming up and closes with it going down, which
	 * makes the pair read as one idea rather than two borrowed noises.
	 *
	 * Chosen by measuring, not by guessing which sounds are long. Every
	 * candidate's .ogg was timed out of the asset index against a
	 * three-second floor:
	 *
	 *   block.anvil.land               0.75   too short
	 *   entity.generic.burn            1.25   too short
	 *   entity.player.hurt_on_fire     1.52   too short - the previous cue
	 *   entity.elder_guardian.curse    3.00
	 *   block.beacon.deactivate        3.50   this one
	 *   block.conduit.deactivate       4.07
	 *   entity.wither.death            8.00   too much
	 *   entity.ender_dragon.death     18.00   and it means the opposite
	 *
	 * Full volume, matching its twin at the reveal. The same instrument
	 * opening a match loudly and closing it at a quarter volume would
	 * sound like a mistake.
	 */
	public static void defeat() {
		play(SoundEvents.BLOCK_BEACON_DEACTIVATE, 0.9f, MAX);
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
