package com.speedrunmcalt.match;

import com.speedrunmcalt.menu.AltMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Post-match summary, shown after the client leaves the match world.
 *
 * Exists so a finished match has somewhere to land. The two routes out
 * are the two things a player actually wants next - queue again, or
 * stop - so neither is buried behind the title screen.
 */
public class MatchEndScreen extends Screen {
	private static final int PHOSPHOR = com.speedrunmcalt.menu.Palette.CYAN;
	private static final int DIM = com.speedrunmcalt.menu.Palette.DIM;
	private static final int WIN = com.speedrunmcalt.menu.Palette.CYAN;
	private static final int LOSS = com.speedrunmcalt.menu.Palette.MAGENTA;

	private final boolean won;
	private final String opponent;
	private final long myTimeMs;
	private final Long opponentTimeMs;
	private final Integer ratingDelta;
	private final Integer seasonPoints;

	public MatchEndScreen(boolean won, String opponent, long myTimeMs, Long opponentTimeMs,
			Integer ratingDelta, Integer seasonPoints) {
		super(new LiteralText(won ? "Victory" : "Defeat"));
		this.won = won;
		this.opponent = opponent;
		this.myTimeMs = myTimeMs;
		this.opponentTimeMs = opponentTimeMs;
		this.ratingDelta = ratingDelta;
		this.seasonPoints = seasonPoints;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = this.height / 2 + 30;
		this.addButton(new ButtonWidget(cx - 100, y, 200, 20,
				new LiteralText("Find another match"),
				b -> this.client.openScreen(new AltMenuScreen(new TitleScreen()))));
		this.addButton(new ButtonWidget(cx - 100, y + 26, 200, 20,
				new LiteralText("Back to title"),
				b -> this.client.openScreen(new TitleScreen())));
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int y = this.height / 2 - 60;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(won ? "VICTORY" : "DEFEAT"), cx, y, won ? WIN : LOSS);
		y += 20;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("vs " + (opponent == null ? "opponent" : opponent)), cx, y, DIM);
		y += 18;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("your time   " + MatchState.formatTime(myTimeMs)), cx, y, PHOSPHOR);
		y += 12;

		String theirs = opponentTimeMs == null ? "--:--" : MatchState.formatTime(opponentTimeMs);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("their time  " + theirs), cx, y, DIM);
		y += 18;

		if (ratingDelta != null) {
			String line = "rating  " + (ratingDelta >= 0 ? "+" : "") + ratingDelta;
			if (seasonPoints != null && seasonPoints > 0) {
				line += "    season  +" + seasonPoints;
			}
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(line), cx, y, won ? WIN : LOSS);
		} else {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("rating updated - see the menu"), cx, y, DIM);
		}

		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// Esc here would drop the player into an empty screen with no
		// world behind it. Make them pick one of the two exits.
		return false;
	}
}
