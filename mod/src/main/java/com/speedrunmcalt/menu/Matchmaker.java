package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.LiveMatchPoller;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.ReplayRecorder;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.QueueJoinResult;
import com.speedrunmcalt.world.MatchWorldCreator;
import net.minecraft.client.MinecraftClient;

/**
 * Queue search driven by the menu, replacing the old fire-once flow
 * that ran automatically on client launch.
 *
 * Volatile state for the same reason as AltSession: the search runs on
 * a background thread and the screen reads it every frame.
 */
public final class Matchmaker {
	public enum State { IDLE, SEARCHING, LAUNCHING, ERROR }

	private static final long POLL_INTERVAL_MS = 3000;

	private static volatile State state = State.IDLE;
	private static volatile String error;
	private static volatile String opponent;
	private static volatile long searchStartMillis;
	// Bumped on cancel so an in-flight search thread knows to stop
	// without needing to interrupt it mid-request.
	private static volatile int searchGeneration = 0;

	private Matchmaker() {
	}

	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static String opponent() {
		return opponent;
	}

	public static long searchSeconds() {
		return state == State.SEARCHING
				? (System.currentTimeMillis() - searchStartMillis) / 1000
				: 0;
	}

	public static void cancel() {
		searchGeneration++;
		state = State.IDLE;
		opponent = null;
	}

	public static void search(MinecraftClient client) {
		if (state == State.SEARCHING || state == State.LAUNCHING) {
			return;
		}
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			state = State.ERROR;
			return;
		}

		final int generation = ++searchGeneration;
		state = State.SEARCHING;
		error = null;
		opponent = null;
		searchStartMillis = System.currentTimeMillis();

		Thread thread = new Thread(() -> {
			try {
				QueueJoinResult result = BackendClient.joinQueue(token);
				while (!result.matched) {
					if (generation != searchGeneration) {
						return; // cancelled
					}
					Thread.sleep(POLL_INTERVAL_MS);
					if (generation != searchGeneration) {
						return;
					}
					result = BackendClient.joinQueue(token);
				}
				if (generation != searchGeneration) {
					return;
				}

				opponent = result.opponentUsername;
				state = State.LAUNCHING;
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Matched vs {} - matchId={} type={} structure={},{} "
								+ "bastion={}@{},{} overworldSeed={} netherSeed={}",
						result.opponentUsername, result.matchId, result.seedType,
						result.structureX, result.structureZ, result.bastionType,
						result.bastionX, result.bastionZ,
						result.overworldSeed, result.netherSeed);

				QueueJoinResult match = result;
				// World creation touches client/server state, so it has to
				// run on the main thread rather than this polling thread.
				client.execute(() -> {
					try {
						MatchState.reset();
						ReplayRecorder.reset();
						MatchState.matchId = match.matchId;
						MatchState.sessionToken = token;
						// Deliberately NOT started here. MatchClock starts
						// it on the first tick the player can actually
						// play, so world generation, setup and loading are
						// not charged to the run - they vary by hardware
						// and would hand the faster machine free seconds.
						// Set before the world is created: the setup hook
						// fires as soon as the integrated server starts,
						// and reads these to know what to guarantee.
						MatchState.seedType = match.seedType;
						MatchState.structureX = match.structureX;
						MatchState.structureZ = match.structureZ;
						MatchState.overworldSeed = match.overworldSeed;
						MatchState.netherSeed = match.netherSeed;
						MatchState.bastionX = match.bastionX;
						MatchState.bastionZ = match.bastionZ;
						MatchState.smithX = match.smithX;
						MatchState.smithZ = match.smithZ;
						// Must come after reset(), which clears it. Set
						// before the world exists, so MatchClock knows
						// not to show the planning countdown well before
						// the first playable tick can ask.
						MatchState.runAlreadyStarted = match.runAlreadyStarted;
						MatchWorldCreator.createMatchWorld(client, "match-" + match.matchId,
								match.overworldSeed, match.netherSeed);
						LiveMatchPoller.start();
						state = State.IDLE;
					} catch (Exception e) {
						MatchState.reset();
						ReplayRecorder.reset();
						error = e.getMessage();
						state = State.ERROR;
						SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match world creation failed", e);
					}
				});
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				if (generation == searchGeneration) {
					error = e.getMessage();
					state = State.ERROR;
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Matchmaking failed", e);
				}
			}
		}, "speedrunmcalt-matchmaker");
		thread.setDaemon(true);
		thread.start();
	}
}
