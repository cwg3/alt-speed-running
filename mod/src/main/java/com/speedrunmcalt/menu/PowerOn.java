package com.speedrunmcalt.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Util;

/**
 * The set switching on, once per launch, as the title screen first
 * appears.
 *
 * TuneInScreen ends a match's run-up with the tube going out - picture
 * to line, line to dot, dot to nothing. This is that ending played
 * forwards: a dot glows up, stretches into a line, and the line opens
 * vertically into the title screen. The two bookend each other - power
 * on when the game starts, tune in when a match does - without spending
 * the tune-in itself, which means "a match is starting".
 *
 * Drawn by TitleScreenMixin on top of everything the title screen
 * draws. While the Mojang splash is still up it paints solid black, so
 * the splash fades out into a dark tube rather than into the menu, and
 * the clock starts only once the splash has gone. Silent and about
 * two-thirds of a second: it plays on every launch.
 */
public final class PowerOn {
	private static final int DOT_MS = 120;
	private static final int LINE_MS = 320;
	private static final int TOTAL_MS = 650;

	private static final int BLACK = 0xFF000000;
	/** TuneInScreen's glow core, for the opening edges. */
	private static final int CORE = 0xF0FFEC;

	private static long startedAt = -1;
	private static boolean done;

	private PowerOn() {
	}

	/** True once the set is on - MenuMusic waits for it. */
	public static boolean isDone() {
		return done;
	}

	public static void render(MatrixStack matrices, MinecraftClient client, int width, int height) {
		if (done) {
			return;
		}
		if (client.getOverlay() != null) {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			return;
		}
		long now = Util.getMeasuringTimeMs();
		if (startedAt < 0) {
			startedAt = now;
		}
		int t = (int) (now - startedAt);
		if (t >= TOTAL_MS) {
			done = true;
			return;
		}

		int cx = width / 2;
		int cy = height / 2;
		if (t < DOT_MS) {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			TuneInScreen.glow(matrices, cx - 1, cy - 1, cx + 1, cy + 1, t / (float) DOT_MS);
		} else if (t < LINE_MS) {
			DrawableHelper.fill(matrices, 0, 0, width, height, BLACK);
			float q = easeOut((t - DOT_MS) / (float) (LINE_MS - DOT_MS));
			int half = (int) (width / 2 * q);
			TuneInScreen.glow(matrices, cx - half - 1, cy - 1, cx + half + 1, cy + 1, 1.0f);
		} else {
			// The line opens into the picture: black above and below a
			// band that grows from the middle, its edges bright and the
			// band itself washed with the light of the line, both fading
			// as it reaches full height.
			float q = easeOut((t - LINE_MS) / (float) (TOTAL_MS - LINE_MS));
			int half = Math.max(1, (int) (height / 2 * q));
			DrawableHelper.fill(matrices, 0, 0, width, cy - half, BLACK);
			DrawableHelper.fill(matrices, 0, cy + half, width, height, BLACK);
			int wash = (int) (110 * (1.0f - q)) << 24 | CORE;
			DrawableHelper.fill(matrices, 0, cy - half, width, cy + half, wash);
			int edge = (int) (255 * (1.0f - q)) << 24 | CORE;
			DrawableHelper.fill(matrices, 0, cy - half - 1, width, cy - half + 1, edge);
			DrawableHelper.fill(matrices, 0, cy + half - 1, width, cy + half + 1, edge);
		}
	}

	private static float easeOut(float x) {
		float inv = 1.0f - Math.min(1.0f, Math.max(0.0f, x));
		return 1.0f - inv * inv * inv;
	}
}
