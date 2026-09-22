package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;

/**
 * Raises, or agrees to, a vote that this match's seed is unplayable.
 *
 * The pool ships seeds that cannot be run - a village whose blacksmith
 * chest does not exist, a desert temple with no tree within five chunks
 * to craft with. Both were found by a player in a live match, not by a
 * test. Until the filter catches everything, players need a way out
 * that does not cost them rating for our mistake.
 *
 * It takes both players, and that is the whole design. A single-player
 * void is just a way to escape a losing run on a good seed. Two
 * opponents actively racing each other have no shared interest in
 * lying, which makes agreement between them a stronger signal than any
 * check we could run - and it needs no moderator, which is the point of
 * this project.
 *
 * The teardown is deliberately NOT done here. The vote may be the first
 * of two, in which case the match carries on; the client leaves the
 * world only when the live poller sees the match actually go void. That
 * way both players leave on the same event rather than one of them
 * quitting on an optimistic local guess.
 */
public final class BadSeedVote {
	/** Set once this client has voted, so the HUD can say so. */
	public static volatile boolean voted = false;

	private BadSeedVote() {
	}

	public static void reset() {
		voted = false;
	}

	/** Sends this player's vote. Safe to call twice. */
	public static void cast(String reason) {
		final String matchId = MatchState.matchId;
		final String token = MatchState.sessionToken;
		if (matchId == null || token == null) {
			return;
		}
		voted = true;

		Thread thread = new Thread(() -> {
			try {
				BackendClient.voteBadSeed(token, matchId, reason);
			} catch (Exception e) {
				// Leave `voted` set: the player did vote, and the HUD
				// telling them otherwise would invite a second click
				// that cannot help. The poller reports the real state.
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Bad seed vote failed to send", e);
			}
		}, "speedrunmcalt-badseed");
		thread.setDaemon(true);
		thread.start();
	}
}
