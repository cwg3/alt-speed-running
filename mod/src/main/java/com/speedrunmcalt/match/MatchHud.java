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
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = com.speedrunmcalt.menu.Palette.CYAN;
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

	/** Search time as mm:ss - a bare second count reads badly past 99. */
	private static String clock(long seconds) {
		return String.format("%02d:%02d", seconds / 60, seconds % 60);
	}

	public static void register() {
		HudRenderCallback.EVENT.register(MatchHud::render);
	}

	/**
	 * Both clocks, top right, the one being watched highlighted.
	 *
	 * Top right rather than the match HUD's top left: it is a different
	 * readout answering a different question, and putting it somewhere
	 * else stops it being read as the live one.
	 */
	private static void renderReplay(MatrixStack matrices, MinecraftClient client) {
		com.speedrunmcalt.net.ReplayData data = com.speedrunmcalt.replay.ReplayPlayback.data();
		if (data == null) {
			return;
		}
		long at = com.speedrunmcalt.replay.ReplayPlayback.positionMillis();
		String watched = com.speedrunmcalt.replay.ReplayPlayback.watching();

		int y = Y;
		int right = client.getWindow().getScaledWidth() - X;
		for (java.util.Map.Entry<String, com.speedrunmcalt.net.ReplayData.Track> e
				: data.tracks.entrySet()) {
			boolean isWatched = e.getKey().equals(watched);
			com.speedrunmcalt.net.ReplayData.Track t = e.getValue();
			// A track that ends before the current time has stopped -
			// a forfeit, or a player who quit. Showing their last
			// timestamp rather than the playhead says so.
			long shown = at;
			boolean ended = false;
			if (t.samples != null && !t.samples.isEmpty()) {
				long last = t.samples.get(t.samples.size() - 1).t;
				if (at > last) {
					shown = last;
					ended = true;
				}
			} else {
				ended = true;
				shown = 0;
			}

			String line = t.username + "  " + MatchState.formatTime(shown) + (ended ? " *" : "");
			int w = client.textRenderer.getWidth(line);
			drawShadowed(matrices, client, line, right - w, y,
					isWatched ? com.speedrunmcalt.menu.Palette.YELLOW
							: com.speedrunmcalt.menu.Palette.DIM);
			y += LINE;
		}

		String hint = "Esc for the timeline";
		drawShadowed(matrices, client, hint,
				right - client.textRenderer.getWidth(hint), y,
				com.speedrunmcalt.menu.Palette.DIM);

		renderReplayControls(matrices, client);
	}

	/**
	 * The control strip, where the hotbar would be.
	 *
	 * Spectator hides the hotbar, so that strip is free and 1-9 mean
	 * nothing. Putting the controls there keeps them off the middle of
	 * the screen, which is the part being watched.
	 */
	private static void renderReplayControls(MatrixStack matrices, MinecraftClient client) {
		String[] labels = com.speedrunmcalt.replay.ReplayKeys.LABELS;
		int screenW = client.getWindow().getScaledWidth();
		int screenH = client.getWindow().getScaledHeight();

		// Measure first so the strip is centred as a whole rather than
		// each slot being centred on its own.
		int gap = 10;
		int total = 0;
		for (int i = 0; i < labels.length; i++) {
			total += client.textRenderer.getWidth((i + 1) + " " + labels[i]) + gap;
		}
		int x = (screenW - total) / 2;
		int y = screenH - 22;

		for (int i = 0; i < labels.length; i++) {
			String num = String.valueOf(i + 1);
			String text = labels[i];
			drawShadowed(matrices, client, num, x, y, com.speedrunmcalt.menu.Palette.YELLOW);
			x += client.textRenderer.getWidth(num) + 2;

			// The two that have a state worth showing say what it is
			// rather than only what it does.
			int colour = com.speedrunmcalt.menu.Palette.DIM;
			if (i == 0 && com.speedrunmcalt.replay.ReplayPlayback.paused()) {
				text = "paused";
				colour = com.speedrunmcalt.menu.Palette.CYAN;
			} else if (i == 3) {
				float sp = com.speedrunmcalt.replay.ReplayPlayback.speed();
				text = (sp == (long) sp ? String.valueOf((long) sp) : String.valueOf(sp)) + "x";
				if (sp != 1f) {
					colour = com.speedrunmcalt.menu.Palette.CYAN;
				}
			} else if (i == 4
					&& com.speedrunmcalt.replay.ReplayPlayback.camera()
							== com.speedrunmcalt.replay.ReplayPlayback.Camera.FREE) {
				text = "free";
				colour = com.speedrunmcalt.menu.Palette.CYAN;
			}
			drawShadowed(matrices, client, text, x, y, colour);
			x += client.textRenderer.getWidth(text) + gap;
		}
	}

	private static void render(MatrixStack matrices, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options.hudHidden || client.textRenderer == null) {
			return;
		}

		// Queueing while practising elsewhere is the normal way to
		// queue, so the search has to be visible from inside whatever
		// world the player is in - not only on the menu they walked
		// away from. Without it there is no way to tell a live search
		// from one that silently died, and no reminder that a match is
		// coming.
		// A replay gets its own readout: both players' clocks, because
		// the question while watching is not "how long have I been
		// going" but "where is the gap". The match HUD below would be
		// answering the wrong question with the wrong numbers.
		if (MatchState.replayMode && com.speedrunmcalt.replay.ReplayPlayback.active()) {
			renderReplay(matrices, client);
			return;
		}
		if (!MatchState.inMatch() || MatchState.replayMode) {
			if (com.speedrunmcalt.menu.Matchmaker.state()
					== com.speedrunmcalt.menu.Matchmaker.State.SEARCHING) {
				drawShadowed(matrices, client, "alt", X, Y,
						com.speedrunmcalt.menu.Palette.PHOSPHOR);
				drawShadowed(matrices, client,
						"searching for an opponent  " + MatchHud.clock(
								com.speedrunmcalt.menu.Matchmaker.searchSeconds()),
						X + client.textRenderer.getWidth("alt  "), Y,
						com.speedrunmcalt.menu.Palette.DIM);
			}
			return;
		}

		int y = Y;
		long elapsed = MatchState.elapsedMillis();
		String outcome = MatchState.result;
		// "alt" keeps its brand colour here too, so the HUD and the
		// menu read as the same product.
		drawShadowed(matrices, client, "alt", X, y, com.speedrunmcalt.menu.Palette.PHOSPHOR);
		drawShadowed(matrices, client, MatchState.formatTime(elapsed),
				X + client.textRenderer.getWidth("alt  "), y, ACCENT);
		y += LINE;

		if (outcome != null) {
			drawShadowed(matrices, client, outcome, X, y,
					outcome.startsWith("VICTORY") ? AHEAD : BEHIND);
			y += LINE;
		}

		// "vs" stays recessive; the NAME is yellow, the same way the
		// player's own name is yellow on the menu. In dim grey it was
		// nearly invisible over bright water.
		String opponent = MatchState.opponentUsername;
		drawShadowed(matrices, client, "vs ", X, y, DIM);
		drawShadowed(matrices, client, opponent == null ? "..." : opponent,
				X + client.textRenderer.getWidth("vs "), y,
				com.speedrunmcalt.menu.Palette.YELLOW);
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
