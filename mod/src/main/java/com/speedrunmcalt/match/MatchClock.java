package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.menu.SeedRevealScreen;
import com.speedrunmcalt.net.BackendClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;

/**
 * Starts the run timer when the player can actually play, once per run.
 *
 * Two requirements pull against each other here, and the history of
 * this file is one of them being fixed at the other's expense.
 *
 * FAIRNESS. The clock used to start the moment the backend created the
 * match, which charged the player for world generation, the mod's setup
 * pass, and loading - a player arrived in the world with fifty seconds
 * already gone. Loading time depends on hardware, so two players racing
 * the same seed lose different amounts of run time to it and the faster
 * machine wins ground that has nothing to do with skill.
 *
 * INTEGRITY. Fixing that by having the client mint its own start time
 * on the first playable tick moved the authority onto the client, where
 * it can simply be done again. Quitting to the title screen leaves the
 * match pending, rejoining it minted a fresh 0:00 - on a seed whose
 * structures you had already located. Free resets, unlimited, and the
 * server's wall-clock check could not see it: that check only rejects
 * claiming MORE elapsed time than has really passed, and a reset clock
 * claims less.
 *
 * Both hold if the client chooses the MOMENT and the server owns the
 * RECORD. The first playable tick still decides when the run starts, so
 * nobody is charged for their loading; the server stores that instant
 * once and returns it forever after, so nobody can claim it twice. A
 * player who crashes and rejoins resumes the run they were on, which is
 * the honest reading of a crash - it is a misfortune, not a reset.
 */
public final class MatchClock {
	/**
	 * Set while the claim request is in flight, so a request is not
	 * fired on every tick of the round trip. Not a lock - it is only
	 * ever touched from the client thread and the network thread's one
	 * write at the end.
	 */
	private static volatile boolean claiming = false;

	/** So the broken-seed warning is said once, not every tick. */
	private static volatile boolean setupFailureAnnounced = false;

	/**
	 * Planning time before the race starts, once the player is in the
	 * world.
	 *
	 * FIVE seconds, which is what MCSR Ranked uses. This was ten for a
	 * while on an earlier report; five is the corrected figure.
	 */
	private static final long COUNTDOWN_MS = 5_000;

	private MatchClock() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(MatchClock::tick);
	}

	/** Lets a new match claim a start after the previous one finished. */
	public static void reset() {
		claiming = false;
		setupFailureAnnounced = false;
	}

	private static void tick(MinecraftClient client) {
		// Only while a match is pending its start.
		if (MatchState.matchId == null || MatchState.matchStartMillis > 0 || claiming) {
			return;
		}
		if (client.player == null || client.world == null) {
			return;
		}

		// The pre-race countdown starts HERE, on the first tick the
		// player is actually in the world - not when the match was
		// made.
		//
		// Starting it at match time was wrong and invisible: world
		// generation takes about twelve seconds, so a five-second
		// countdown begun at matchmaking had already expired before
		// the player could see it, and the screen never appeared.
		//
		// The screen pauses the game, which is what keeps the clock
		// from starting - the isPaused() check below does the gating,
		// and this needs no coordination with it.
		//
		// Except on a rejoin. The countdown buys planning time before
		// the race; a player who crashed out twelve minutes in has
		// already planned, already run, and their clock is still going -
		// five seconds of the screen would be five seconds taken off a run
		// in progress. -1 marks it deliberately skipped, so this branch
		// does not fire again on the next tick.
		if (MatchState.runAlreadyStarted) {
			if (MatchState.countdownEndsAt >= 0) {
				MatchState.countdownEndsAt = -1;
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Rejoining a run in progress - skipping the seed reveal");
			}
			// Close it if the player is already looking at it: the flag
			// arrives from a poll, which can land after the screen has
			// opened.
			if (client.currentScreen instanceof SeedRevealScreen) {
				client.openScreen(null);
			}
		} else if (MatchState.countdownEndsAt == 0) {
			MatchState.countdownEndsAt = System.currentTimeMillis() + COUNTDOWN_MS;
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Seed reveal: {} - race starts in {}s",
					MatchState.seedType, COUNTDOWN_MS / 1000);
		}
		if (MatchState.countdownRemaining() > 0) {
			if (!(client.currentScreen instanceof SeedRevealScreen)) {
				client.openScreen(new SeedRevealScreen());
				com.speedrunmcalt.menu.Cues.seedReveal();
			}
			return;
		}

		if (client.isPaused()) {
			return;
		}

		// Tell the player, once, if the world could not be given what it
		// was promised. This used to go only to the log: a player was
		// dropped into open ocean with no portal and no lava pool, three
		// warnings deep, and had no way to tell our failure from their
		// own bad luck. Pointing them at the bad-seed vote matters -
		// that is the one route that ends the match with no rating
		// change for either side.
		if (MatchState.setupFailure != null && !setupFailureAnnounced) {
			setupFailureAnnounced = true;
			client.player.sendMessage(new LiteralText(
					"This seed is broken: " + MatchState.setupFailure)
					.formatted(Formatting.RED), false);
			client.player.sendMessage(new LiteralText(
					"It is not your fault and not a fair race. Open the pause menu and "
							+ "vote BAD SEED - if your opponent agrees, nobody loses rating.")
					.formatted(Formatting.YELLOW), false);
		}

		final String matchId = MatchState.matchId;
		final String token = MatchState.sessionToken;
		if (token == null) {
			return;
		}

		claiming = true;

		// Off the client thread. This is a network round trip, and
		// blocking here would stall rendering at the exact moment the
		// player expects to start moving.
		Thread thread = new Thread(() -> {
			long startMillis;
			try {
				startMillis = BackendClient.claimRunStart(token, matchId);
			} catch (Exception e) {
				// A run that cannot reach the backend still has to be
				// playable. Falling back to a local start is the
				// permissive choice, and it is the right one: the
				// alternative is a player sitting in a world with no
				// timer because of someone else's outage. The claim is
				// still authoritative for anyone who can reach it, and
				// the splits this client reports are checked against
				// the server's own clock regardless.
				SpeedrunMcAlt.LOGGER.warn(
						"[speedrunmcalt] Could not claim run start - timing locally", e);
				startMillis = System.currentTimeMillis();
			}

			// Do not resurrect the clock for a match that ended, or
			// overwrite one that somehow already started, while the
			// request was in flight.
			if (matchId.equals(MatchState.matchId) && MatchState.matchStartMillis <= 0) {
				MatchState.matchStartMillis = startMillis;
				com.speedrunmcalt.menu.Cues.raceStart();
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Run clock started");
			}
			claiming = false;
		}, "speedrunmcalt-runstart");
		thread.setDaemon(true);
		thread.start();
	}
}
