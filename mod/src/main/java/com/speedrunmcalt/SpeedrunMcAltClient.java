package com.speedrunmcalt;

import com.speedrunmcalt.auth.MicrosoftAuth;
import net.fabricmc.api.ClientModInitializer;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Phase 2 proof-of-concept: log in automatically on client start and
		// log the result. Real UX (a login screen, caching the refresh
		// token so this doesn't happen on every launch) comes later - this
		// is here to prove the full Microsoft -> Xbox -> Minecraft chain
		// actually works end to end.
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Starting Microsoft login...");
		MicrosoftAuth.login().whenComplete((profile, error) -> {
			if (error != null) {
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Login failed", error);
			} else {
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Login succeeded: {} ({})",
						profile.getName(), profile.getUuid());
			}
		});
	}
}
