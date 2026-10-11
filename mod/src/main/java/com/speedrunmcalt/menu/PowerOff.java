package com.speedrunmcalt.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;

/**
 * The set switching off when Quit Game is pressed: the picture squeezes
 * shut into a bright line, the line pulls in to a dot, the dot fades,
 * and then the game stops.
 *
 * PowerOn played backwards, and the same ending TuneInScreen gives the
 * run-up to a match - so launch, match and quit are one set going on,
 * tuning in, and going off. The menu music fades out alongside it,
 * under the set's own switch-off sound (Cues.powerOff).
 *
 * Drawn by TitleScreenMixin over everything the title screen draws;
 * TitleScreenMixin also redirects the Quit button's scheduleStop here.
 * Closing the window directly skips it, as it should - that is not a
 * button press.
 */
public final class PowerOff {
	/*
	 * THE TIMELINE IS SHARED WITH brand/gen_power_off.py, which renders
	 * the sound: its knock lands on SQUEEZE_MS and its whine takes over
	 * from LINE_MS. Change one, re-render the other.
	 */
	private static final int SQUEEZE_MS = 260;
	private static final int LINE_MS = 450;
	private static final int TOTAL_MS = 600;
	/**
	 * When the game actually stops: the screen holds black after the
	 * dot is gone while the sound plays out - a one-second "tsch" of
	 * static, the whine and the hum dying under it. Stopping at
	 * TOTAL_MS shut the sound system down mid-whine.
	 */
	private static final int STOP_MS = 1100;

	private static final int BLACK = 0xFF000000;
	/** TuneInScreen's glow core. */
	private static final int CORE = 0xF0FFEC;

	private static long startedAt = -1;
	private static boolean stopped;

	private PowerOff() {
	}

	/** Quit Game was pressed. */
	public static void start() {
		if (startedAt < 0) {
			startedAt = Util.getMeasuringTimeMs();
			MenuMusic.fadeOut();
			Cues.powerOff();
		}
	}

	/** Whether the set is going off - the title screen ignores clicks meanwhile. */
	public static boolean active() {
		return startedAt >= 0;
	}

	public static void render(MatrixStack matrices, MinecraftClient client, int width, int height) {
		if (startedAt < 0) {
			return;
		}
		int t = (int) (Util.getMeasuringTimeMs() - startedAt);
		int cx = width / 2;
		int cy = height / 2;
		if (t >= TOTAL_MS) {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			if (t >= STOP_MS && !stopped) {
				stopped = true;
				client.scheduleStop();
			}
			return;
		}
		if (t < SQUEEZE_MS) {
			// Black closing in from top and bottom on a band that
			// brightens as it narrows: the same light in less height.
			float q = easeIn(t / (float) SQUEEZE_MS);
			int half = Math.max(1, (int) (height / 2 * (1.0f - q)));
			DrawableHelper.fill(matrices, 0, 0, width, cy - half, BLACK);
			DrawableHelper.fill(matrices, 0, cy + half, width, height, BLACK);
			DrawableHelper.fill(matrices, 0, cy - half, width, cy + half, (int) (200 * q) << 24 | CORE);
		} else if (t < LINE_MS) {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			float q = easeIn((t - SQUEEZE_MS) / (float) (LINE_MS - SQUEEZE_MS));
			int half = (int) (width / 2 * (1.0f - q));
			TuneInScreen.glow(matrices, cx - half - 1, cy - 1, cx + half + 1, cy + 1, 1.0f);
		} else {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			float q = (t - LINE_MS) / (float) (TOTAL_MS - LINE_MS);
			TuneInScreen.glow(matrices, cx - 1, cy - 1, cx + 1, cy + 1, 1.0f - q);
		}
	}

	private static float easeIn(float x) {
		float c = Math.min(1.0f, Math.max(0.0f, x));
		return c * c * c;
	}
}
