package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.MatchHistoryEntry;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's finished matches, newest first.
 *
 * Exists because a result that vanishes when the end screen closes is
 * not a record of anything. It is also the way into a replay - there
 * is no point recording runs nobody can navigate to - so the rows are
 * built to be clicked later even though clicking does nothing yet.
 *
 * Fetched on a background thread like everything else that talks to
 * the backend, with the screen reading volatile state each frame.
 */
public class MatchHistoryScreen extends Screen {
	private static final int ROWS_PER_PAGE = 12;
	private static final int ROW_HEIGHT = 14;

	private final Screen parent;

	private volatile List<MatchHistoryEntry> entries = new ArrayList<>();
	private volatile String error;
	private volatile boolean loading = true;
	/** Where the rows were drawn, so a click can find which one. */
	private int rowsLeft;
	private int rowsTop;

	/** completedAt to page from; 0 asks for the newest. */
	private volatile long before = 0;
	/** Absent next page: the oldest row is already shown. */
	private volatile boolean atEnd = false;

	public MatchHistoryScreen(Screen parent) {
		super(new LiteralText("Matches"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int bottom = this.height - 30;

		this.addButton(new ButtonWidget(cx - 155, bottom, 100, 20,
				new LiteralText("Newer"), b -> {
					before = 0;
					atEnd = false;
					load();
				}));
		this.addButton(new ButtonWidget(cx - 50, bottom, 100, 20,
				new LiteralText("Older"), b -> {
					if (!entries.isEmpty() && !atEnd) {
						before = entries.get(entries.size() - 1).completedAt;
						load();
					}
				}));
		this.addButton(new ButtonWidget(cx + 55, bottom, 100, 20,
				new LiteralText("Back"), b -> this.client.openScreen(parent)));

		if (entries.isEmpty()) {
			load();
		}
	}

	private void load() {
		loading = true;
		error = null;
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			loading = false;
			return;
		}
		Thread t = new Thread(() -> {
			try {
				List<MatchHistoryEntry> page =
						BackendClient.matchHistory(token, ROWS_PER_PAGE, before);
				// A short page means there is nothing older, so "Older"
				// stops rather than fetching an empty one.
				atEnd = page.size() < ROWS_PER_PAGE;
				entries = page;
			} catch (Exception e) {
				error = e.getMessage();
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match history failed", e);
			} finally {
				loading = false;
			}
		}, "speedrunmcalt-history");
		t.setDaemon(true);
		t.start();
	}

	/** "3m ago", "4h ago", "2d ago" - relative reads better than a date. */
	private static String ago(long whenMillis) {
		if (whenMillis <= 0) {
			return "";
		}
		long secs = Math.max(0, (System.currentTimeMillis() - whenMillis) / 1000);
		if (secs < 60) {
			return secs + "s ago";
		}
		if (secs < 3600) {
			return (secs / 60) + "m ago";
		}
		if (secs < 86400) {
			return (secs / 3600) + "h ago";
		}
		return (secs / 86400) + "d ago";
	}

	private static String typeName(String raw) {
		return raw == null ? "unknown" : raw.replace('_', ' ');
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!loading && error == null && !entries.isEmpty()) {
			int index = (int) ((mouseY - rowsTop) / ROW_HEIGHT);
			if (index >= 0 && index < entries.size()
					&& mouseX >= rowsLeft && mouseX <= rowsLeft + 300) {
				// Detail first, replay from there. Deciding to watch
				// follows from seeing where the time went, not the
				// other way round - and a match with no replay still
				// has splits worth reading.
				MatchHistoryEntry e = entries.get(index);
				this.client.openScreen(new MatchDetailScreen(this, e.matchId));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("MATCHES"), cx, 18, Palette.DIM);

		if (loading) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("loading..."), cx, this.height / 2, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}
		if (error != null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(error), cx, this.height / 2, Palette.ALERT);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}
		if (entries.isEmpty()) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("no matches yet"), cx, this.height / 2, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		// Columns from a fixed left edge rather than centred per row, so
		// the eye can run down result, opponent and rating without
		// following a ragged margin.
		int left = cx - 150;
		rowsLeft = left;
		rowsTop = 40;
		int y = 40;
		for (MatchHistoryEntry e : entries) {
			// A forfeit is not a loss and should not read as one.
			//
			// The row is per player, and forfeitedBy is the quitter's
			// uuid - so the same match says FORFEIT on the quitter's
			// row and WON on the other, with a marker saying how. A
			// player scrolling their own history can tell a run they
			// gave up from one they were beaten in, which is the
			// distinction the record existed to keep.
			boolean iQuit = e.forfeitedBy != null
					&& e.forfeitedBy.equals(com.speedrunmcalt.menu.AltSession.uuid());
			String verdict = e.won ? "WON" : (iQuit ? "FORFEIT" : "LOST");
			this.textRenderer.drawWithShadow(matrices, verdict,
					left, y, e.won ? Palette.CYAN
							: (iQuit ? Palette.ALERT : Palette.MAGENTA));
			if (e.won && e.forfeitedBy != null) {
				// Won because they quit, not because you were faster.
				this.textRenderer.drawWithShadow(matrices, "ff",
						left + 26, y, Palette.DIM);
			}

			this.textRenderer.drawWithShadow(matrices, "vs", left + 34, y, Palette.DIM);
			this.textRenderer.drawWithShadow(matrices, e.opponentName,
					left + 50, y, Palette.YELLOW);

			this.textRenderer.drawWithShadow(matrices, typeName(e.seedType),
					left + 140, y, Palette.PURPLE);

			String ratingText = (e.ratingDelta >= 0 ? "+" : "") + e.ratingDelta;
			this.textRenderer.drawWithShadow(matrices, ratingText,
					left + 232, y, e.ratingDelta >= 0 ? Palette.CYAN : Palette.MAGENTA);

			this.textRenderer.drawWithShadow(matrices, ago(e.completedAt),
					left + 262, y, Palette.DIM);
			y += ROW_HEIGHT;
		}

		// The launcher's state, not this screen's: loading a replay is
		// its own operation and can fail in its own way - most often by
		// refusing a match built under different rules.
		String replayNote = com.speedrunmcalt.replay.ReplayLauncher.loading()
				? "loading replay..."
				: com.speedrunmcalt.replay.ReplayLauncher.error();
		if (replayNote != null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(replayNote), cx, this.height - 58,
					com.speedrunmcalt.replay.ReplayLauncher.error() != null
							? Palette.ALERT : Palette.DIM);
		}

		String page = before == 0 ? "newest" : "older";
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(page + (atEnd ? " - end of history" : "")
						+ "   -   click a match for splits and replay"),
				cx, this.height - 44, Palette.DIM);

		super.render(matrices, mouseX, mouseY, delta);
	}
}
