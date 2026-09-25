package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.MatchDetail;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * One match, split by split, both players side by side.
 *
 * Sits between the history list and the replay, and earns its place by
 * answering the question people actually have - where was this lost -
 * without watching anything. A list of your own times says how the run
 * went; the DELTA against the opponent says where the match turned.
 *
 * It is also the natural home for Watch Replay: you decide to watch
 * after seeing you dropped fourteen seconds finding the stronghold,
 * not before.
 */
public class MatchDetailScreen extends Screen {
	private static final int ROW_HEIGHT = 14;

	private final Screen parent;
	private final String matchId;

	private volatile MatchDetail detail;
	private volatile String error;
	private volatile boolean loading = true;

	public MatchDetailScreen(Screen parent, String matchId) {
		super(new LiteralText("Match"));
		this.parent = parent;
		this.matchId = matchId;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int bottom = this.height - 28;

		this.addButton(new ButtonWidget(cx - 155, bottom, 150, 20,
				new LiteralText("Watch replay"),
				b -> {
					com.speedrunmcalt.replay.ReplayLauncher.clearError();
					com.speedrunmcalt.replay.ReplayLauncher.open(
							this.client, matchId, AltSession.uuid());
				}));
		this.addButton(new ButtonWidget(cx + 5, bottom, 150, 20,
				new LiteralText("Back"), b -> this.client.openScreen(parent)));

		if (detail == null) {
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
				detail = BackendClient.getMatchDetail(token, matchId);
			} catch (Exception e) {
				error = e.getMessage();
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match detail failed", e);
			} finally {
				loading = false;
			}
		}, "speedrunmcalt-detail");
		t.setDaemon(true);
		t.start();
	}

	private static String clock(Long ms) {
		if (ms == null) {
			return "--:--";
		}
		long total = ms / 1000;
		return String.format("%d:%02d.%03d", total / 60, total % 60, ms % 1000);
	}

	/** "+00:04.090" / "-00:14.612" - sign first, because sign is the point. */
	private static String delta(Long ms) {
		if (ms == null) {
			return "";
		}
		String sign = ms > 0 ? "+" : ms < 0 ? "-" : " ";
		long a = Math.abs(ms);
		return sign + String.format("%d:%02d.%03d", a / 60000, (a / 1000) % 60, a % 1000);
	}

	private static String splitLabel(String raw) {
		switch (raw) {
			case "enter_nether": return "Entered Nether";
			case "piglin_barter": return "Bartered";
			case "obtain_rod": return "Blaze Rod";
			case "enter_stronghold": return "Stronghold";
			case "enter_end": return "Entered End";
			case "kill_dragon": return "Dragon";
			default: return raw.replace('_', ' ');
		}
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;

		if (loading) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("loading..."), cx, this.height / 2, Palette.DIM);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}
		if (error != null || detail == null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(error == null ? "no detail" : error),
					cx, this.height / 2, Palette.ALERT);
			super.render(matrices, mouseX, mouseY, delta);
			return;
		}

		MatchDetail d = detail;

		// Header: who won, what it was, whether it was given up.
		MatchDetail.Player winner = d.players.stream()
				.filter(p -> p.uuid.equals(d.winnerUuid)).findFirst().orElse(null);
		// WINNER is the headline: double size, emerald - the colour
		// this mod already uses for winning. The name sits under it at
		// body size in yellow, which is what a player name is
		// everywhere else here.
		matrices.push();
		matrices.scale(2.0f, 2.0f, 1.0f);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("WINNER"), cx / 2, 12 / 2, Palette.EMERALD);
		matrices.pop();
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(winner == null ? "-" : winner.username),
				cx, 28, Palette.YELLOW);

		Palette.drawCenteredSegments(matrices, this.textRenderer, cx, 42,
				new String[] { d.seedType.replace('_', ' '), d.forfeited ? "   forfeited" : "" },
				// Orange for forfeited, the same orange the history
				// screen uses for it.
				new int[] { Palette.PURPLE, Palette.ORANGE });

		// Two columns of times with the split name between them, which
		// is what makes a row readable left-to-right as a comparison
		// rather than two lists that happen to be adjacent.
		int left = cx - 190;
		// Below the enlarged winner line, which is twice the height of
		// the body text it replaced.
		int y = 60;

		for (int i = 0; i < d.players.size() && i < 2; i++) {
			MatchDetail.Player p = d.players.get(i);
			int x = i == 0 ? left : cx + 96;
			// Both names yellow. A player name is yellow everywhere
			// else in the mod - the HUD, the history list, the winner
			// line directly above this - and greying the opponent made
			// the one screen built for comparing two players treat one
			// of them as secondary. Which column is yours is already
			// said by the winner line and by the left/right split.
			this.textRenderer.drawWithShadow(matrices, p.username, x, y, Palette.YELLOW);
		}
		y += 16;

		for (MatchDetail.Row row : d.rows) {
			for (int i = 0; i < d.players.size() && i < 2; i++) {
				MatchDetail.Player p = d.players.get(i);
				Long t = row.times.get(p.uuid);
				Long dl = row.deltas == null ? null : row.deltas.get(p.uuid);

				int x = i == 0 ? left : cx + 96;
				this.textRenderer.drawWithShadow(matrices, clock(t), x, y,
						t == null ? Palette.DIM : Palette.CYAN);
				if (dl != null) {
					this.textRenderer.drawWithShadow(matrices, delta(dl),
							// Same green/red as the in-match HUD: a split
							// delta is won or lost, and the two screens
							// should not disagree about which colour
							// says which.
							x + 62, y, dl <= 0 ? Palette.EMERALD : Palette.ALERT);
				}
			}
			// Purple, the same as the split labels on the in-match HUD
			// and the seed type on the reveal screen: all three NAME
			// something rather than report how it went. Grey made the
			// labels read as disabled on a screen where they are the
			// thing you scan down.
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(splitLabel(row.split)), cx, y, Palette.PURPLE);
			y += ROW_HEIGHT;
		}

		if (d.worldSetupVersion != com.speedrunmcalt.world.WorldSetupVersion.CURRENT) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("no replay - this match was built by a different version"),
					cx, this.height - 44, Palette.DIM);
		}

		super.render(matrices, mouseX, mouseY, delta);
	}
}
