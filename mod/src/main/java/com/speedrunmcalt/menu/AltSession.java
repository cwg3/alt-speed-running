package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.auth.MinecraftIdentity;
import com.speedrunmcalt.auth.SessionAuth;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.VerifyResult;

/**
 * The player's logged-in state, shared between the menu screens.
 *
 * Every field is volatile because the network work runs on a background
 * thread while the screen reads these every frame on the render thread.
 */
public final class AltSession {
	public enum State { DISCONNECTED, CONNECTING, READY, ERROR }

	private static volatile State state = State.DISCONNECTED;
	private static volatile String username;
	private static volatile String uuid;
	private static volatile String sessionToken;
	private static volatile int skillRating;
	private static volatile int seasonPoints;
	private static volatile String error;

	private AltSession() {
	}

	public static State state() {
		return state;
	}

	public static String username() {
		return username;
	}

	public static String uuid() {
		return uuid;
	}

	public static String sessionToken() {
		return sessionToken;
	}

	public static int skillRating() {
		return skillRating;
	}

	public static int seasonPoints() {
		return seasonPoints;
	}

	public static String error() {
		return error;
	}

	/**
	 * Folds a finished match's result into the cached profile.
	 *
	 * These values are read once at connect time, so without this the
	 * menu kept showing the rating the player had when they launched -
	 * they could lose several matches and still be told they were on
	 * 1500. Applying the delta locally avoids a round trip; the next
	 * connect re-reads the authoritative value either way.
	 */
	public static void applyMatchResult(Integer ratingDelta, Integer seasonPointsAwarded) {
		if (ratingDelta != null) {
			skillRating += ratingDelta;
		}
		if (seasonPointsAwarded != null) {
			seasonPoints += seasonPointsAwarded;
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Profile now {} elo, {} pts",
				skillRating, seasonPoints);
	}

	/** Proves account ownership to the backend. No-op if already connecting or ready. */
	public static void connect() {
		if (state == State.CONNECTING || state == State.READY) {
			return;
		}
		state = State.CONNECTING;
		error = null;

		Thread thread = new Thread(() -> {
			try {
				MinecraftIdentity identity = SessionAuth.currentIdentity();
				String serverId = SessionAuth.randomServerId();
				SessionAuth.joinServer(identity, serverId);

				VerifyResult verified = BackendClient.verify(identity.getUsername(), serverId);
				username = verified.username;
				uuid = verified.uuid;
				sessionToken = verified.sessionToken;
				skillRating = verified.skillRating;
				seasonPoints = verified.seasonPoints;
				state = State.READY;
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Connected as {} ({} elo, {} pts)",
						username, skillRating, seasonPoints);
			} catch (Exception e) {
				error = e.getMessage();
				state = State.ERROR;
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Connect failed", e);
			}
		}, "speedrunmcalt-connect");
		thread.setDaemon(true);
		thread.start();
	}
}
