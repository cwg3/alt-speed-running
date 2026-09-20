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

				for (Map.Entry<String, Long> split : live.opponentSplits.entrySet()) {
					if (reported.add(split.getKey())) {
						SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] OPPONENT {} reached {} at {} ms",
								live.opponentUsername, split.getKey(), split.getValue());
					}
				}

				if (live.isComplete()) {
					SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Match finished - winner {}", live.winnerUuid);
					// Covers losing the race, where this client never
					// reports a final split of its own.
					ReplayRecorder.uploadIfFinished();
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
