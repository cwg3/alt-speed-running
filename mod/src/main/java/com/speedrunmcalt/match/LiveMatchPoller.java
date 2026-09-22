package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.LiveMatchResult;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Polls the backend for the opponent's progress during an active match
 * and surfaces each newly reached split.
 *
 * Polling rather than a pushed WebSocket connection is a deliberate
 * choice: a 1.16 RSG run produces roughly six splits over eight to
 * fifteen minutes, so a three-second poll is indistinguishable from
 * instant for this data, and it avoids a persistent wss:// connection
 * plus reconnect handling on the Java 8 runtime the real launcher uses
 * for 1.16.1. The transport can be swapped later without changing
 * anything a player sees.
 */
public final class LiveMatchPoller {
	private static final long POLL_INTERVAL_MS = 3000;

	private LiveMatchPoller() {
	}

	public static void start() {
		Thread thread = new Thread(LiveMatchPoller::pollLoop, "speedrunmcalt-livepoll");
		thread.setDaemon(true);
		thread.start();
	}

	private static void pollLoop() {
		String matchId = MatchState.matchId;
		String sessionToken = MatchState.sessionToken;
		if (matchId == null || sessionToken == null) {
			return;
		}

		Set<String> reported = new HashSet<>();
		int consecutiveFailures = 0;

		while (matchId.equals(MatchState.matchId)) {
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}

			try {
				LiveMatchResult live = BackendClient.getLiveMatch(sessionToken, matchId);
				consecutiveFailures = 0;

				MatchState.opponentUsername = live.opponentUsername;
				MatchState.opponentSplits.putAll(live.opponentSplits);

				// Tells MatchClock this is a rejoin, so it does not show
				// the planning countdown for a run already in progress.
				// The first poll lands about three seconds in and world
				// generation takes about twelve, so this is normally
				// known well before the player reaches a playable tick -
				// and if it is not, MatchClock closes the screen the
				// moment it arrives.
				if (live.yourRunHasStarted() && !MatchState.runAlreadyStarted) {
					MatchState.runAlreadyStarted = true;
				}

				for (Map.Entry<String, Long> split : live.opponentSplits.entrySet()) {
					if (reported.add(split.getKey())) {
						SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] OPPONENT {} reached {} at {} ms",
								live.opponentUsername, split.getKey(), split.getValue());
					}
				}

				// Tell the player their opponent is waiting on them. This
				// poll is the only channel that carries it - there is no
				// push - so a vote raised on the other side surfaces
				// here or nowhere.
				if (live.badSeedOpponent && !MatchState.opponentProposedBadSeed) {
					MatchState.opponentProposedBadSeed = true;
					SpeedrunMcAlt.LOGGER.info(
							"[speedrunmcalt] {} voted BAD SEED - agree in the pause menu to void the match",
							live.opponentUsername);
				}

				if (live.isVoided()) {
					SpeedrunMcAlt.LOGGER.info(
							"[speedrunmcalt] Match VOIDED - both players agreed the seed was unplayable");
					try {
						// Not a win and not a loss. Passing null for the
						// rating numbers would make MatchEnd go and fetch
						// them; zeroes say plainly that nothing moved.
						MatchEnd.complete(false, live.opponentUsername,
								Integer.valueOf(0), Integer.valueOf(0),
								"VOIDED - bad seed, no rating change", 4000);
					} catch (Throwable t) {
						SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Void teardown failed", t);
					}
					return;
				}

				if (live.isComplete()) {
					SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Match finished - winner {}", live.winnerUuid);
					boolean iWon = live.winnerUuid != null
							&& live.winnerUuid.equals(com.speedrunmcalt.menu.AltSession.uuid());
					try {
						// Tears down the match: uploads the replay
						// (covering the losing side, which never reports
						// a final split of its own), leaves the world and
						// shows the summary.
						MatchEnd.complete(iWon, live.opponentUsername,
								live.ratingDelta, live.seasonPoints);
					} catch (Throwable t) {
						// The catch below only wraps the network call, so
						// anything thrown while HANDLING a result used to
						// kill this thread outright - the match over on
						// the server and the client never noticing. A
						// swapped jar caused exactly that once; a null
						// field or a bad screen transition would do the
						// same. Better to log it and leave the player
						// able to quit than to strand them silently.
						SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match teardown failed", t);
					}
					return;
				}
			} catch (Exception e) {
				// Opponent progress is a nice-to-have display; a flaky
				// network must never end the poll loop or interrupt the
				// player's own run. Give up only after repeated failures.
				if (++consecutiveFailures >= 5) {
					SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Giving up on opponent progress polling", e);
					return;
				}
			}
		}
	}
}
