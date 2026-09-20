package com.speedrunmcalt;

import com.speedrunmcalt.match.MatchHud;
import com.speedrunmcalt.match.ReplayRecorder;
import net.fabricmc.api.ClientModInitializer;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// The match HUD only draws while a match is active, so it's safe
		// to register unconditionally here.
		MatchHud.register();
		ReplayRecorder.register();

		// Everything else is driven from the title screen button added by
		// TitleScreenMixin - nothing touches the network until the player
		// asks for it.
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] client ready");
	}
}
