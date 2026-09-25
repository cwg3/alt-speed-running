package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.LeaderboardEntry;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.List;

/**
 * The ladder standings.
 *
 * A ladder with no visible standings is a rating nobody can see the
 * point of. This is the answer to "am I getting better" and "who should
 * I be trying to beat", and neither is answerable from your own match
 * history alone.
 *
 * Rating is the ranking key because it is the number the ladder moves.
 * Season points sit beside it rather than behind it, because they say
 * something different - rating is how good you are, points are how much
 * you have played this season - and showing only one invites the wrong
 * comparison.
 *
 * Needs no session token: the endpoint is public, so this renders on the
 * title screen before anyone has logged in.
 */
public class LeaderboardScreen extends Screen {
	private static final int ROW_HEIGHT = 12;
	private static final int ROWS_TOP = 46;
	private static final int FOOTER_RESERVE = 52;

	private final Screen parent;

	private volatile List<LeaderboardEntry> rows;
	private volatile String error;
	private volatile boolean loading = true;

	public LeaderboardScreen(Screen parent) {
		super(new LiteralText("Standings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		this.addButton(new ButtonWidget(cx - 100, this.height - 28, 200, 20,
				new LiteralText("Back"), b -> this.client.openScreen(parent)));
		if (rows == null) {
			load();
		}
	}

	private void load() {
		loading = true;
		error = null;
		Thread t = new Thread(() -> {
			try {
				rows = BackendClient.leaderboard(50);
			} catch (Exception e) {
				error = e.getMessage();
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Leaderboard failed", e);
			} finally {
				loading = false;
			}
		}, "speedrunmcalt-leaderboard");
		t.setDaemon(true);
		t.start();
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - ROWS_TOP - FOOTER_RESERVE) / ROW_HEIGHT);
	}

	/** "12-3", or "12-3-1" when there are forfeits to show. */
	private static String record(LeaderboardEntry e) {
		return e.forfeits > 0
				? e.wins + "-" + e.losses + "-" + e.forfeits
				: e.wins + "-" + e.losses;
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;

		matrices.push();
		matrices.scale(1.5f, 1.5f, 1.0f);
		drawCenteredText(matrices, this.textRenderer, new LiteralText("STANDINGS"),
				(int) (cx / 1.5f), (int) (14 / 1.5f), Palette.PHOSPHOR);
		matrices.pop();

		if (loading) {
			drawCenteredText(matrices, this.textRenderer, new LiteralText("loading..."),
					cx, this.height / 2, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}
		if (error != null) {
			drawCenteredText(matrices, this.textRenderer, new LiteralText(error),
					cx, this.height / 2, Palette.ALERT);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		List<LeaderboardEntry> list = rows;
		if (list == null || list.isEmpty()) {
			// Not an error, and worth saying why rather than showing an
			// empty box: nobody has finished a ranked match yet.
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("no ranked matches yet"), cx, this.height / 2 - 6,
					Palette.DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("finish a match and you are on the board"),
					cx, this.height / 2 + 6, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		// Columns are laid out from the centre so the board stays put as
		// the window resizes, rather than drifting with the left edge.
		int xRank = cx - 150;
		int xName = cx - 128;
		int xRating = cx + 20;
		int xPoints = cx + 74;
		int xRecord = cx + 118;

		int y = ROWS_TOP - 14;
		this.textRenderer.drawWithShadow(matrices, "#", xRank, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "player", xName, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "rating", xRating, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "points", xPoints, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "W-L", xRecord, y, Palette.DIM);

		String me = AltSession.uuid();
		y = ROWS_TOP;
		int shown = Math.min(list.size(), rowsPerPage());
		for (int i = 0; i < shown; i++) {
			LeaderboardEntry e = list.get(i);
			boolean isMe = me != null && me.equals(e.uuid);

			// Your own row in phosphor, everyone else's name in yellow -
			// the colour a player name is everywhere else in this mod.
			// Finding yourself on a long board should not need reading.
			int nameColour = isMe ? Palette.PHOSPHOR : Palette.YELLOW;

			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.rank),
					xRank, y, e.rank <= 3 ? Palette.EMERALD : Palette.DIM);
			this.textRenderer.drawWithShadow(matrices, e.username, xName, y, nameColour);
			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.skillRating),
					xRating, y, Palette.CYAN);
			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.seasonPoints),
					xPoints, y, Palette.PURPLE);
			this.textRenderer.drawWithShadow(matrices, record(e), xRecord, y, Palette.DIM);
			y += ROW_HEIGHT;
		}

		if (list.size() > shown) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText((list.size() - shown) + " more - resize the window to see them"),
					cx, this.height - 44, Palette.DIM);
		}

		super.render(matrices, mouseX, mouseY, delta);
	}
}
