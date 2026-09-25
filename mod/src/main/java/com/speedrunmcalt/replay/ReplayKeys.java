package com.speedrunmcalt.replay;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Number-key controls while a replay is playing.
 *
 * Spectator mode hides the hotbar, so a replay has that whole strip of
 * screen and none of the usual meaning for 1-9. Putting the controls
 * there keeps the view clear - the alternative is a row of buttons
 * across the bottom of what you are trying to watch, which is what
 * this replaces.
 *
 * Polled rather than registered as keybinds: these only exist during a
 * replay, and a keybind would show up in the controls menu and fight
 * with whatever the player has bound 1-9 to in real play.
 */
public final class ReplayKeys {
	/** What each slot does, in order. Rendered by MatchHud. */
	public static final String[] LABELS = {
		"play/pause", "-10s", "+10s", "speed", "camera", "switch",
	};

	private static final int[] KEYS = {
		GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3,
		GLFW.GLFW_KEY_4, GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6,
	};

	/** Edge detection: act on the press, not once per tick while held. */
	private static final boolean[] wasDown = new boolean[KEYS.length];

	private ReplayKeys() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(ReplayKeys::tick);
	}

	private static void tick(MinecraftClient client) {
		if (!ReplayPlayback.active() || client.currentScreen != null) {
			// Not while a screen is open: the timeline has its own
			// buttons and typing a number there should not scrub.
			java.util.Arrays.fill(wasDown, false);
			return;
		}
		long handle = client.getWindow().getHandle();
		for (int i = 0; i < KEYS.length; i++) {
			boolean down = InputUtil.isKeyPressed(handle, KEYS[i]);
			if (down && !wasDown[i]) {
				act(i);
			}
			wasDown[i] = down;
		}
	}

	private static void act(int slot) {
		switch (slot) {
			case 0:
				ReplayPlayback.togglePause();
				break;
			case 1:
				ReplayPlayback.nudge(-10_000);
				break;
			case 2:
				ReplayPlayback.nudge(10_000);
				break;
			case 3:
				ReplayPlayback.setSpeed(
						ReplayPlayback.speed() >= 8f ? 1f : ReplayPlayback.speed() * 2f);
				break;
			case 4:
				ReplayPlayback.toggleCamera();
				break;
			case 5:
				ReplayPlayback.watchOther();
				break;
			default:
				break;
		}
	}
}
