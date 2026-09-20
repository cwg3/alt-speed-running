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

	public static void surrender(MinecraftClient client) {
		String matchId = MatchState.matchId;
		String token = MatchState.sessionToken;
		if (matchId == null || token == null) {
			return;
		}

		// Send the timeline before clearing state, so an abandoned run is
		// still on record.
		ReplayRecorder.uploadIfFinished();

		Thread thread = new Thread(() -> {
			try {
				BackendClient.forfeit(token, matchId);
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Forfeited match {}", matchId);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Forfeit request failed", e);
			}
		}, "speedrunmcalt-forfeit");
		thread.setDaemon(true);
		thread.start();

		// Clear locally straight away so the HUD stops and the player
		// isn't left looking at a timer for a match they've conceded,
		// even if the request is slow.
		MatchState.reset();
		ReplayRecorder.reset();
	}
}
