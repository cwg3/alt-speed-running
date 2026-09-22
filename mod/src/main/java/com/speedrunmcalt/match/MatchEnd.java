package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.menu.AltSession;
import com.speedrunmcalt.net.BackendClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.text.TranslatableText;

/**
 * Ends the match on the client once the backend has declared a result.
 *
 * A decided match has to actually stop. Previously the result was only
 * written to the HUD, which left a player who had already lost still
 * standing in a world that no longer counted, with no indication that
 * anything had changed and no way back other than quitting manually.
 *
 * The world lingers briefly before the disconnect rather than cutting
 * out the instant the result lands - being yanked to a menu mid-swing
 * with no explanation reads as a crash. The pause is long enough to see
 * the outcome on the HUD in-world and short enough not to feel stuck.
 */
public final class MatchEnd {
	private static final long LINGER_MS = 4000;

	// Both the split reporter and the live poller can discover the same
	// result within a poll interval of each other. Whichever gets here
	// first owns the teardown; the other is a no-op.
	private static volatile boolean ending = false;

	private MatchEnd() {
	}

	/**
	 * @param ratingDelta null when this client learned it lost from its
	 *                    own split report, which carries no numbers back.
	 */
	public static void complete(boolean won, String opponentName,
			Integer ratingDelta, Integer seasonPoints) {
		complete(won, opponentName, ratingDelta, seasonPoints, null, LINGER_MS);
	}

	/**
	 * @param hudMessage overrides the HUD banner - a forfeit is a loss
	 *                   but not one the opponent raced you to
	 * @param lingerMs   how long to stay in-world before the teardown
	 */
	public static void complete(boolean won, String opponentName,
			Integer ratingDelta, Integer seasonPoints, String hudMessage, long lingerMs) {
		if (ending || !MatchState.inMatch()) {
			return;
		}
		ending = true;

		MatchState.finish(hudMessage != null ? hudMessage
				: won ? "VICTORY" : "DEFEAT - " + opponentName + " finished first");

		// Snapshot before the teardown clears it.
		final long myTime = MatchState.elapsedMillis();
		final Long opponentTime = MatchState.opponentSplits.get("kill_dragon");
		final String opponent = opponentName != null ? opponentName : MatchState.opponentUsername;

		// Whichever way the result landed, the timeline goes on record.
		ReplayRecorder.uploadIfFinished();

		final String matchId = MatchState.matchId;
		final String token = MatchState.sessionToken;

		Thread thread = new Thread(() -> {
			// The split-report path learns it lost without being told the
			// numbers, so fetch them rather than leaving the profile
			// showing a rating the player no longer has.
			Integer delta = ratingDelta;
			Integer points = seasonPoints;
			if (delta == null && matchId != null && token != null) {
				try {
					com.speedrunmcalt.net.LiveMatchResult live =
							BackendClient.getLiveMatch(token, matchId);
					delta = live.ratingDelta;
					points = live.seasonPoints;
				} catch (Exception e) {
					SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Could not fetch final rating", e);
				}
			}
			AltSession.applyMatchResult(delta, points);
			final Integer shownDelta = delta;
			final Integer shownPoints = points;

			try {
				Thread.sleep(lingerMs);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
			MinecraftClient client = MinecraftClient.getInstance();
			if (client == null) {
				ending = false;
				return;
			}
			// Leaving the world touches client and integrated-server
			// state, so it has to happen on the main thread.
			client.execute(() -> {
				try {
					leaveWorld(client);
					client.openScreen(new MatchEndScreen(
							won, opponent, myTime, opponentTime, shownDelta, shownPoints));
				} catch (Exception e) {
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Failed to leave match world", e);
				} finally {
					MatchState.reset();
					ReplayRecorder.reset();
					ending = false;
				}
			});
		}, "speedrunmcalt-matchend");
		thread.setDaemon(true);
		thread.start();
	}

	private static void leaveWorld(MinecraftClient client) {
		if (client.world == null) {
			return;
		}
		// Mirrors what vanilla's "Save and Quit to Title" does - the
		// integrated server needs the save screen path or the world can
		// be left half-written.
		boolean singlePlayer = client.isInSingleplayer();
		client.world.disconnect();
		if (singlePlayer) {
			client.disconnect(new SaveLevelScreen(new TranslatableText("menu.savingLevel")));
		} else {
			client.disconnect();
		}
	}
}
