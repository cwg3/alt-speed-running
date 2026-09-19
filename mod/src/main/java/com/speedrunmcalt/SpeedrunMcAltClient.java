package com.speedrunmcalt;

import com.speedrunmcalt.auth.MinecraftIdentity;
import com.speedrunmcalt.auth.SessionAuth;
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
				SessionAuth.joinServer(identity, SessionAuth.randomServerId());
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Session join failed "
						+ "(expected in the dev environment, which has no real access token)", e);
			}
		});
	}
}
