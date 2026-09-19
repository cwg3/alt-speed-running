package com.speedrunmcalt;

import com.speedrunmcalt.auth.MinecraftIdentity;
import com.speedrunmcalt.auth.SessionAuth;
import com.speedrunmcalt.world.MatchWorldCreator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// MinecraftClient.getInstance() isn't guaranteed to be assigned yet
		// during onInitializeClient() itself (confirmed against the actual
		// 1.16.1 boot order) - CLIENT_STARTED fires once the client is
		// fully constructed, which is the safe point to read the session.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			MinecraftIdentity identity = SessionAuth.currentIdentity();
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Session identity: {} ({})",
					identity.getUsername(), identity.getUuid());

			// Proof of concept only: in the real flow, serverId comes from
			// our backend when a match starts. The dev environment
			// (./gradlew runClient) uses a fake session with no real
			// access token, so this call is expected to fail here - it
			// can only be tested for real once the mod is installed into
			// an actual Minecraft Launcher profile.
			try {
				String serverId = SessionAuth.randomServerId();
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Using serverId: {}", serverId);
				SessionAuth.joinServer(identity, serverId);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Session join failed "
						+ "(expected in the dev environment, which has no real access token)", e);
			}

			// Temporary proof-of-concept trigger: normally these seeds come
			// from the backend's /queue/join match response, not a hardcoded
			// pair. Using the exact seeds (60/49) a real matchmaking test
			// already assigned, so this is testing MatchWorldCreator itself,
			// not fabricated data. Real wiring (mod calling /queue/join and
			// creating a world from its response) is separate, later work.
			try {
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Creating test match world (overworldSeed=60, netherSeed=49)");
				MatchWorldCreator.createMatchWorld(client, "speedrunmcalt-test-match", 60L, 49L);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match world creation failed", e);
			}
		});
	}
}
