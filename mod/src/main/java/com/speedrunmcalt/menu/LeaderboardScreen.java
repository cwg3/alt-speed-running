package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.LeaderboardEntry;
import com.speedrunmcalt.net.LeaderboardResult;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

import java.util.List;

/**
 * The ladder leaderboard.
 *
 * A ladder with no visible leaderboard is a rating nobody can see the
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
	/** Screen headings are cyan everywhere in this mod. */
	private static final int ACCENT = Palette.CYAN;

	private static final int ROW_HEIGHT = 12;
	private static final int ROWS_TOP = 46;
	private static final int FOOTER_RESERVE = 52;

	private final Screen parent;

	private volatile LeaderboardResult result;
	private volatile String error;
	private volatile boolean loading = true;

	public LeaderboardScreen(Screen parent) {
		super(new LiteralText("Leaderboard"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		this.addButton(new ButtonWidget(cx - 100, this.height - 28, 200, 20,
				new LiteralText("Back"), b -> this.client.openScreen(parent)));
		if (result == null) {
			load();
		}
	}

	private void load() {
		loading = true;
		error = null;
		Thread t = new Thread(() -> {
			try {
				result = BackendClient.leaderboard(50);
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

	/**
	 * "12-3-1", always three parts.
	 *
	 * The first version dropped the forfeits when there were none, so
	 * the column held two numbers on some rows and three on others under
	 * a header that said "W-L" - which was simply wrong wherever a
	 * forfeit existed. A board is read by scanning DOWN a column, and a
	 * column whose format changes per row cannot be scanned. Showing the
	 * zero is cheaper than that.
	 */
	private static String record(LeaderboardEntry e) {
		return e.wins + "-" + e.losses + "-" + e.forfeits;
	}

	private static String plural(int n, String one, String many) {
		return n + " " + (n == 1 ? one : many);
	}

	/**
	 * What is on the board, and what is deliberately not.
	 *
	 * PaceBot is the case this exists for: a solo player races it
	 * constantly and then cannot find it here. Unexplained, that reads
	 * as a bug in the board rather than a rule about it - and a ladder
	 * that publishes every deviation from vanilla should not be coy
	 * about which rows it drops.
	 */
	private static String footer(LeaderboardResult r) {
		if (r == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder(plural(r.totalRanked, "ranked player", "ranked players"));
		if (r.botsHidden > 0) {
			sb.append("   ").append(plural(r.botsHidden, "bot", "bots")).append(" not ranked");
		}
		if (r.unranked > 0) {
			sb.append("   ").append(r.unranked).append(" yet to finish a match");
		}
		return sb.toString();
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;

		matrices.push();
		matrices.scale(1.5f, 1.5f, 1.0f);
		// NOT phosphor. That green is the wordmark's and nothing else -
		// spending it on a screen title makes a heading look like
		// branding, which is the exact drift the palette warns about
		// having already happened once on MATCH FOUND.
		drawCenteredText(matrices, this.textRenderer, new LiteralText("LEADERBOARD"),
				(int) (cx / 1.5f), (int) (14 / 1.5f), ACCENT);
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

		LeaderboardResult r = result;
		List<LeaderboardEntry> list = r == null ? null : r.rows;
		if (list == null || list.isEmpty()) {
			// Not an error, and worth saying why rather than showing an
			// empty box: nobody has finished a ranked match yet.
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("no ranked matches yet"), cx, this.height / 2 - 6,
					Palette.DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("finish a match and you are on the board"),
					cx, this.height / 2 + 6, Palette.DIM);
			// The footer belongs here MOST of all. An empty board with a
			// bot excluded is the case where a reader is likeliest to
			// conclude the page is broken.
			drawCenteredText(matrices, this.textRenderer, new LiteralText(footer(r)),
					cx, this.height - 44, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		// Columns are laid out from the centre so the board stays put as
		// the window resizes, rather than drifting with the left edge.
		// A Minecraft username is at most 16 characters, which is about
		// 96px in this font. The name column was 148px wide, so every
		// board carried 50px of gap that read as a layout fault rather
		// than as spacing - most visibly with one row on it.
		int xRank = cx - 132;
		int xName = cx - 110;
		int xRating = cx - 2;
		int xPoints = cx + 52;
		int xRecord = cx + 96;

		int y = ROWS_TOP - 14;
		this.textRenderer.drawWithShadow(matrices, "#", xRank, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "player", xName, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "rating", xRating, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "points", xPoints, y, Palette.DIM);
		this.textRenderer.drawWithShadow(matrices, "W-L-F", xRecord, y, Palette.DIM);

		String me = AltSession.uuid();
		y = ROWS_TOP;
		int shown = Math.min(list.size(), rowsPerPage());
		for (int i = 0; i < shown; i++) {
			LeaderboardEntry e = list.get(i);
			boolean isMe = me != null && me.equals(e.uuid);

			// Every name yellow, including yours. A player name is yellow
			// everywhere else in this mod, and recolouring one of them to
			// mean "you" both breaks that and spends a palette colour on
			// something a label says better. Your row is marked after the
			// record instead.
			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.rank),
					xRank, y, e.rank <= 3 ? Palette.EMERALD : Palette.DIM);
			this.textRenderer.drawWithShadow(matrices, e.username, xName, y, Palette.YELLOW);
			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.skillRating),
					xRating, y, Palette.CYAN);
			this.textRenderer.drawWithShadow(matrices, String.valueOf(e.seasonPoints),
					xPoints, y, Palette.PURPLE);
			this.textRenderer.drawWithShadow(matrices, record(e), xRecord, y, Palette.DIM);
			if (isMe) {
				this.textRenderer.drawWithShadow(matrices, "you", xRecord + 52, y, ACCENT);
			}
			y += ROW_HEIGHT;
		}

		if (list.size() > shown) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText((list.size() - shown) + " more - resize the window to see them"),
					cx, this.height - 56, Palette.DIM);
		}

		drawCenteredText(matrices, this.textRenderer, new LiteralText(footer(r)),
				cx, this.height - 44, Palette.DIM);

		super.render(matrices, mouseX, mouseY, delta);
	}
}
