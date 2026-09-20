package com.speedrunmcalt;

import net.fabricmc.api.ClientModInitializer;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Everything is driven from the title screen button added by
		// TitleScreenMixin - nothing touches the network until the
		// player asks for it. Replaces the placeholder that connected
		// and queued automatically on launch.
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] client ready");
	}
}
