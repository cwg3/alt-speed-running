package com.speedrunmcalt;

import com.speedrunmcalt.auth.MinecraftIdentity;
import com.speedrunmcalt.auth.SessionAuth;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.QueueJoinResult;
import com.speedrunmcalt.net.VerifyResult;
import com.speedrunmcalt.world.MatchWorldCreator;
import net.minecraft.client.MinecraftClient;

/**
 * The real end-to-end flow: prove identity to our backend, join the
 * matchmaking queue (polling until paired), then create the assigned
 * match world. Replaces the earlier separate proof-of-concept triggers
 * for session auth and world creation with the actual wiring between
 * them.
 */
public final class MatchFlow {
	private static final long POLL_INTERVAL_MS = 3000;

	private MatchFlow() {
	}

	public static void start(MinecraftClient client) {
		Thread thread = new Thread(() -> runBlocking(client), "speedrunmcalt-matchflow");
		thread.setDaemon(true);
		thread.start();
	}

	private static void runBlocking(MinecraftClient client) {
		try {
			MinecraftIdentity identity = SessionAuth.currentIdentity();
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Session identity: {} ({})",
					identity.getUsername(), identity.getUuid());

			String serverId = SessionAuth.randomServerId();
			SessionAuth.joinServer(identity, serverId);

			VerifyResult verified = BackendClient.verify(identity.getUsername(), serverId);
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Backend verified: {} ({})",
					verified.username, verified.uuid);

			QueueJoinResult result = BackendClient.joinQueue(verified.sessionToken);
			while (!result.matched) {
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Waiting in queue...");
				Thread.sleep(POLL_INTERVAL_MS);
				result = BackendClient.joinQueue(verified.sessionToken);
			}

			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Matched vs {} - matchId={} overworldSeed={} netherSeed={}",
					result.opponentUsername, result.matchId, result.overworldSeed, result.netherSeed);

			QueueJoinResult finalResult = result;
			String verifiedToken = verified.sessionToken;
			// World creation touches client/server state - must run on the
			// main thread, not this background polling thread.
			client.execute(() -> {
				try {
					// Approximate run start: world-creation kickoff, not the
					// exact moment the player gains control after the load
					// screen. Close enough for the MVP; real timer mods
					// often make the same simplification.
					MatchState.reset();
					MatchState.matchId = finalResult.matchId;
					MatchState.sessionToken = verifiedToken;
					MatchState.matchStartMillis = System.currentTimeMillis();
					MatchWorldCreator.createMatchWorld(client, "match-" + finalResult.matchId,
							finalResult.overworldSeed, finalResult.netherSeed);
				} catch (Exception e) {
					MatchState.reset();
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match world creation failed", e);
				}
			});
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match flow failed", e);
		}
	}
}
