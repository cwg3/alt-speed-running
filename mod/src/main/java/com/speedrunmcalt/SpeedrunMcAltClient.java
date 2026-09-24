package com.speedrunmcalt;

import com.speedrunmcalt.match.MatchClock;
import com.speedrunmcalt.match.MatchHud;
import com.speedrunmcalt.match.ReplayRecorder;
import com.speedrunmcalt.menu.AltKeybinds;
import net.fabricmc.api.ClientModInitializer;

public class SpeedrunMcAltClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// All only act while a match is active, so registering them
		// unconditionally here is safe.
		com.speedrunmcalt.client.GammaUnlock.apply();
		MatchHud.register();
		MatchClock.register();
		ReplayRecorder.register();
		AltKeybinds.register();
		// Drives the handoff from "matched" to "in the match world".
		// Must be a tick rather than a queued task - see Matchmaker.
		com.speedrunmcalt.menu.Matchmaker.init();
		// Replay: the launcher drives the world switch, the playback
		// drives the camera.
		com.speedrunmcalt.replay.ReplayLauncher.init();
		com.speedrunmcalt.replay.ReplayPlayback.register();

		// Everything else is driven from the title screen button added by
		// TitleScreenMixin - nothing touches the network until the player
		// asks for it.
		// Report the allocator as it actually IS, not as preLaunch tried
		// to set it. preLaunch runs before Log4j has a file to write to,
		// so its own line never reaches latest.log - which makes it
		// useless for confirming anything from a tester's log. This runs
		// late enough to be recorded and states the effective value.
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] lwjgl allocator: {}",
				System.getProperty("org.lwjgl.system.allocator", "(default - jemalloc)"));
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] client ready");
	}
}
