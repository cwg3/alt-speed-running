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
	private Cues() {
	}

	/**
	 * An opponent was found and the world is being built.
	 *
	 * The XP pickup blip - short, unmistakable, and already means
	 * "something arrived" to anyone who has played the game.
	 */
	public static void matchFound() {
		play(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
	}

	/**
	 * The seed reveal screen just opened - five seconds to plan.
	 *
	 * A beacon activating: deep, resonant, and long enough to carry
	 * across the whole reveal rather than blipping and vanishing.
	 */
	public static void seedReveal() {
		play(SoundEvents.BLOCK_BEACON_ACTIVATE, 0.9f);
	}

	/**
	 * The countdown reached zero - the run clock is live.
	 *
	 * Delayed a few ticks so it lands after the reveal screen has
	 * closed and the world is live, rather than in the same frame.
	 *
	 * NOT because of a sound-engine quirk. This cue was reported as
	 * never audible and I attributed it to submissions being dropped
	 * during the unpause transition - a confident explanation for a
	 * symptom whose actual cause was that raceStart() HAD NO CALL SITE.
	 * A patch adding it had failed silently and the cue was listed as
	 * working anyway. The delay is a reasonable precaution; it was
	 * never the bug.
	 */
	public static void raceStart() {
		playDelayed(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.4f, 3);
	}

	/*
	 * There was an opponent-split cue here - an enderman teleport on
	 * every split the opponent reached. Removed: on a fast test pace it
	 * fired six times in fifteen minutes, and a sound a player learns
	 * to tune out is worse than no sound. The HUD already shows their
	 * splits in magenta, which is where that information belongs.
	 */

	/** The advancement chime - the game's own "you did it". */
	public static void victory() {
		play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
	}

	/** An anvil landing. Final, and nobody mistakes it for good news. */
	public static void defeat() {
		play(SoundEvents.BLOCK_ANVIL_LAND, 0.8f);
	}

	/**
	 * Master-channel sound, always on the client thread.
	 *
	 * Called from the poll thread and the matchmaker thread as well as
	 * the client one, and the sound manager is not safe off-thread.
	 */
	/** Sounds waiting on a tick countdown, drained by register(). */
	private static final java.util.List<Object[]> pending =
			java.util.Collections.synchronizedList(new java.util.ArrayList<>());

	/** Call once at client start so delayed cues actually fire. */
	public static void register() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
				.register(client -> {
					synchronized (pending) {
						java.util.Iterator<Object[]> it = pending.iterator();
						while (it.hasNext()) {
							Object[] e = it.next();
							int left = (Integer) e[2] - 1;
							if (left <= 0) {
								play((SoundEvent) e[0], (Float) e[1]);
								it.remove();
							} else {
								e[2] = left;
							}
						}
					}
				});
	}

	private static void playDelayed(SoundEvent sound, float pitch, int ticks) {
		pending.add(new Object[] { sound, pitch, ticks });
	}

	private static void play(SoundEvent sound, float pitch) {
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
			client.getSoundManager().play(PositionedSoundInstance.master(sound, pitch));
			// Logged so "did it fire?" is answerable from the log rather
			// than from whether someone heard it. A cue that silently
			// does nothing is indistinguishable from one that plays too
			// quietly to notice.
			com.speedrunmcalt.SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] cue {} (pitch {})", sound.getId(), pitch);
		});
	}
}
