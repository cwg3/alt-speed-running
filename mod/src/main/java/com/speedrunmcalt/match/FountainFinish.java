package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.menu.AltSession;
import com.speedrunmcalt.net.BackendClient;

/**
 * Entering the exit fountain ends the race.
 *
 * Killing the dragon used to complete the match, and that is the wrong
 * moment. The dragon dying is a split; the race is not over until the
 * runner has got back to the exit portal and jumped in. Two players
 * can kill within seconds of each other and the one standing on the
 * fountain wins - which is what the ending is for.
 *
 * Deliberately NOT a new split. The dragon stays the last thing on the
 * split list; this is the finish, and a finish is not a checkpoint.
 *
 * Off the game thread, like every other network call here: this runs
 * from a block collision on the server tick, and a blocking HTTP
 * request there would stall the game at the exact moment the player is
 * watching to see whether they won.
 */
public final class FountainFinish {
	private FountainFinish() {
	}

	/**
	 * One claim per run.
	 *
	 * Lives here rather than in the mixin because a mixin class is
	 * merged into its target and cannot be referenced from outside -
	 * parking state in one took the game down on the first block
	 * broken earlier today.
	 */
	private static final java.util.concurrent.atomic.AtomicBoolean CLAIMED =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	/** Called when a new match begins, so the next run can finish too. */
	public static void reset() {
		CLAIMED.set(false);
	}

	public static void claim() {
		if (!CLAIMED.compareAndSet(false, true)) {
			return;
		}
		final String matchId = MatchState.matchId;
		final String token = MatchState.sessionToken;
		final String uuid = AltSession.uuid();
		final long elapsed = MatchState.elapsedMillis();
		if (matchId == null || token == null || uuid == null) {
			return;
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Fountain entered - claiming the win");

		Thread thread = new Thread(() -> {
			try {
				// The server holds a close finish open for a moment so
				// the LOWEST RUN TIME wins rather than the fastest
				// connection. While it is provisional it tells us how
				// long is left; we wait that out and ask again.
				com.speedrunmcalt.net.FountainResult r =
						BackendClient.claimFountainWin(token, matchId, uuid, elapsed);
				int guard = 0;
				while (r.provisional && guard++ < 10) {
					Thread.sleep(Math.max(200, Math.min(r.retryInMs + 150, 5_000)));
					r = BackendClient.claimFountainWin(token, matchId, uuid, elapsed);
				}
				MatchEnd.complete(r.won, MatchState.opponentUsername, null, null);
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Fountain claim settled: {}",
						r.won ? "WIN" : "opponent was faster");
			} catch (Exception e) {
				// A finish that cannot reach the backend still ended the
				// run locally. The poller sees the real result when the
				// network comes back; showing nothing at all would be
				// worse than showing a result that gets corrected.
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Fountain claim failed", e);
			}
		}, "speedrunmcalt-fountain");
		thread.setDaemon(true);
		thread.start();
	}
}
