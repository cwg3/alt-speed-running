package com.speedrunmcalt.match;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;

/**
 * In-match overlay: run timer, opponent, and a split-by-split
 * comparison.
 *
 * Drawn top-left. Vanilla puts nothing there during normal play, unlike
 * the top-right (which can hold the scoreboard) or the bottom (hotbar,
 * health, effects).
 */
public final class MatchHud {
	private static final int PHOSPHOR = com.speedrunmcalt.menu.Palette.CYAN;
	private static final int DIM = com.speedrunmcalt.menu.Palette.DIM;
	private static final int AHEAD = com.speedrunmcalt.menu.Palette.CYAN;
	private static final int BEHIND = com.speedrunmcalt.menu.Palette.MAGENTA;

	private static final int X = 6;
	private static final int Y = 6;
	private static final int LINE = 10;

	// Split order for display. Kept here rather than derived from the
	// maps so rows stay in run order even when a split hasn't happened
	// yet, and so the opponent reaching something first doesn't reorder
	// the player's own list mid-run.
	private static final String[] ORDER = {
			"enter_nether",
			"piglin_barter",
			"obtain_rod",
			"enter_stronghold",
			"enter_end",
			"kill_dragon",
	};

	private static final String[] LABELS = {
			"nether",
			"barter",
			"rod",
			"stronghold",
			"end",
			"dragon",
	};

	private MatchHud() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(MatchHud::render);
	}

	private static void render(MatrixStack matrices, float tickDelta) {
		if (!MatchState.inMatch()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options.hudHidden || client.textRenderer == null) {
			return;
		}

		int y = Y;
		long elapsed = MatchState.elapsedMillis();
		String outcome = MatchState.result;
		// "alt" keeps its brand colour here too, so the HUD and the
		// menu read as the same product.
		drawShadowed(matrices, client, "alt", X, y, com.speedrunmcalt.menu.Palette.PHOSPHOR);
		drawShadowed(matrices, client, MatchState.formatTime(elapsed),
				X + client.textRenderer.getWidth("alt  "), y, PHOSPHOR);
		y += LINE;

		if (outcome != null) {
			drawShadowed(matrices, client, outcome, X, y,
					outcome.startsWith("VICTORY") ? AHEAD : BEHIND);
			y += LINE;
		}

		String opponent = MatchState.opponentUsername;
		drawShadowed(matrices, client,
				"vs " + (opponent == null ? "..." : opponent), X, y, DIM);
		y += LINE + 2;

		for (int i = 0; i < ORDER.length; i++) {
			String key = ORDER[i];
			Long mine = MatchState.mySplits.get(key);
			Long theirs = MatchState.opponentSplits.get(key);
			if (mine == null && theirs == null) {
				continue; // neither player has reached this yet
			}

			String line = LABELS[i] + "  "
					+ (mine == null ? "--:--" : MatchState.formatTime(mine))
					+ "  /  "
					+ (theirs == null ? "--:--" : MatchState.formatTime(theirs));

			int color = DIM;
			if (mine != null && theirs != null) {
				color = mine <= theirs ? AHEAD : BEHIND;
			} else if (mine != null) {
				color = AHEAD;   // reached it first
			} else {
				color = BEHIND;  // opponent got there first
			}

			drawShadowed(matrices, client, line, X, y, color);
			y += LINE;
		}
	}

	private static void drawShadowed(MatrixStack matrices, MinecraftClient client,
			String text, int x, int y, int color) {
		DrawableHelper.fill(matrices, x - 2, y - 2, x + client.textRenderer.getWidth(text) + 2,
				y + 9, 0x70000000);
		client.textRenderer.drawWithShadow(matrices, text, x, y, color);
	}
}
