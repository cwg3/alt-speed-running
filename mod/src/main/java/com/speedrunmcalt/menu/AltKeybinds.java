package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.MatchState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class AltKeybinds {
	private static KeyBinding forfeitKey;

	private AltKeybinds() {
	}

	public static void register() {
		// F6 is unbound in vanilla and away from the movement keys, so a
		// stray press during a run is unlikely - and it opens a
		// confirmation rather than forfeiting outright.
		forfeitKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.speedrunmcalt.forfeit",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_F6,
				"key.categories.speedrunmcalt"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (forfeitKey.wasPressed()) {
				if (MatchState.inMatch() && client.currentScreen == null) {
					client.openScreen(new ForfeitConfirmScreen(null));
				}
			}
		});
	}
}
