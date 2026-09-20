package com.speedrunmcalt;

import com.speedrunmcalt.match.MatchHud;
import com.speedrunmcalt.match.ReplayRecorder;
import com.speedrunmcalt.menu.AltKeybinds;
import net.fabricmc.api.ClientModInitializer;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// All only act while a match is active, so registering them
		// unconditionally here is safe.
		MatchHud.register();
		ReplayRecorder.register();
		AltKeybinds.register();

		// Everything else is driven from the title screen button added by
		// TitleScreenMixin - nothing touches the network until the player
		// asks for it.
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] client ready");
	}
}
