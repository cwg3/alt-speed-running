package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import net.minecraft.client.MinecraftClient;

/**
 * Abandons the current match.
 *
 * Resetting a bad start is normal play for this category rather than an
 * edge case, so there has to be a way out that leaves the match in a
 * defined state. The alternative - quitting to the menu - strands the
 * match as pending forever and leaves the opponent waiting on a result
 * that never comes.
 */
public final class Forfeit {
	private Forfeit() {
	}

	/**
	 * Shorter than a raced finish. There is no result to read - the
	 * player already knows how it ended, because they chose it.
	 */
	private static final long LINGER_MS = 1500;

	public static void surrender(MinecraftClient client) {
		String matchId = MatchState.matchId;
		String token = MatchState.sessionToken;
		if (matchId == null || token == null) {
			return;
		}
		String opponent = MatchState.opponentUsername;

		// Freeze the clock on the spot. The request below can take a
		// moment, and a HUD still counting up after the player has
		// conceded reads as the button not having worked.
		MatchState.finish("FORFEITED");

		// Send the timeline first, so an abandoned run is still on
		// record even if everything after this fails.
		ReplayRecorder.uploadIfFinished();

		Thread thread = new Thread(() -> {
			try {
				BackendClient.forfeit(token, matchId);
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Forfeited match {}", matchId);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Forfeit request failed", e);
			}
			// Request or no request, the match is over for this player,
			// so leave the world through the same teardown every other
			// ending uses.
			//
			// This used to reset local state here instead. That cleared
			// matchId, which made the live poller's own "match finished"
			// a no-op against MatchEnd's inMatch() guard - so nothing
			// ever tore the world down and the player was left standing
			// in a match they had already conceded.
			MatchEnd.complete(false, opponent, null, null, "FORFEITED", LINGER_MS);
		}, "speedrunmcalt-forfeit");
		thread.setDaemon(true);
		thread.start();
	}
}
