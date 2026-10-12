package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.RaceReset;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.options.ControlsOptionsScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

public final class AltKeybinds {
	private static KeyBinding forfeitKey;
	private static KeyBinding resetKey;
	private static KeyBinding cancelQueueKey;
	/** Edge detection for the cancel key, which is polled. */
	private static boolean cancelWasDown;

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
		// ] is Ranked's default for the same thing, so a player coming
		// from there already has it under their finger. It opens a
		// confirmation too: a reset throws the whole world away.
		resetKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.speedrunmcalt.reset",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_BRACKET,
				"key.categories.speedrunmcalt"));
		// Z, as in Ranked's Ctrl+Z - vanilla 1.16 keybinds cannot hold a
		// modifier. Unbound in vanilla, and it only does anything while
		// a search is running.
		cancelQueueKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.speedrunmcalt.cancelQueue",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_Z,
				"key.categories.speedrunmcalt"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (forfeitKey.wasPressed()) {
				if (MatchState.inMatch() && !MatchState.replayMode
						&& client.currentScreen == null) {
					client.openScreen(new ForfeitConfirmScreen(null));
				}
			}
			while (resetKey.wasPressed()) {
				if (RaceReset.available() && client.currentScreen == null) {
					client.openScreen(new ResetWorldConfirmScreen(null));
				}
			}
			cancelQueue(client);
		});
	}

	/**
	 * Polled, not read through wasPressed(). A search runs from the alt
	 * menu as often as from a practice world, and a keybind only counts
	 * presses while no screen is open - so on the menu, where most
	 * searches are cancelled, it would never fire.
	 *
	 * Not while typing into a text field, and not on the Controls
	 * screen, where pressing the key is how it gets rebound.
	 */
	private static void cancelQueue(MinecraftClient client) {
		while (cancelQueueKey.wasPressed()) {
			// Drained; the poll below is what acts.
		}
		boolean down = isDown(client, cancelQueueKey);
		boolean pressed = down && !cancelWasDown;
		cancelWasDown = down;
		if (!pressed || Matchmaker.state() != Matchmaker.State.SEARCHING) {
			return;
		}
		if (client.currentScreen instanceof ControlsOptionsScreen
				|| (client.currentScreen != null
						&& client.currentScreen.getFocused() instanceof TextFieldWidget)) {
			return;
		}
		Matchmaker.cancel();
		SystemToast.add(client.getToastManager(), SystemToast.Type.TUTORIAL_HINT,
				new LiteralText("Search cancelled"), null);
	}

	private static boolean isDown(MinecraftClient client, KeyBinding binding) {
		InputUtil.Key key = InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey());
		if (key.equals(InputUtil.UNKNOWN_KEY)) {
			return false;
		}
		long handle = client.getWindow().getHandle();
		if (key.getCategory() == InputUtil.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
		}
		return key.getCategory() == InputUtil.Type.KEYSYM
				&& InputUtil.isKeyPressed(handle, key.getCode());
	}
}
