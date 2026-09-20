package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.SplitReportResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends split events to the backend off the game thread.
 *
 * Split detection happens inside mixins running on the server tick
 * thread - doing a blocking HTTP call there would stall the game for
 * the duration of the request, which during a timed race is exactly
 * the wrong thing to do. Everything here is fire-and-forget on a
 * single daemon thread instead. Single-threaded (rather than a pool)
 * so splits are sent in the order they occurred.
 */
public final class SplitReporter {
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "speedrunmcalt-splits");
		thread.setDaemon(true);
		return thread;
	});

	private SplitReporter() {
	}

	public static void report(String splitName, long elapsedMs) {
		String matchId = MatchState.matchId;
		String sessionToken = MatchState.sessionToken;

		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] SPLIT {} at {} ms", splitName, elapsedMs);

		if (matchId == null || sessionToken == null) {
			// Practice/solo world, or a dev test - nothing to report to.
			return;
		}

		EXECUTOR.submit(() -> {
			try {
				SplitReportResult result = BackendClient.reportSplit(sessionToken, matchId, splitName, elapsedMs);
				if (result.completed) {
					SpeedrunMcAlt.LOGGER.info(
							"[speedrunmcalt] Match complete - you win! rating {}{}, +{} season points",
							result.winnerRatingDelta >= 0 ? "+" : "", result.winnerRatingDelta,
							result.winnerSeasonPoints);
				} else if (result.alreadyCompleted) {
					SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Match already finished - opponent got there first");
				}
			} catch (Exception e) {
				// A failed split report must never interrupt the run.
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Failed to report split {}", splitName, e);
			}
		});
	}
}
