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
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = com.speedrunmcalt.menu.Palette.CYAN;
	private static final int DIM = com.speedrunmcalt.menu.Palette.DIM;
	// The same green and red as every other won/lost in the mod. Cyan
	// and magenta read as two brand colours rather than as a result,
	// and cyan now means a CLOCK everywhere else.
	private static final int WIN = com.speedrunmcalt.menu.Palette.EMERALD;
	private static final int LOSS = com.speedrunmcalt.menu.Palette.ALERT;

	private final boolean won;
	private final String opponent;
	private final Long myTimeMs;
	private final Long opponentTimeMs;
	private final Integer ratingDelta;
	/**
	 * Captured at construction, NOT read from MatchState at render time.
	 *
	 * MatchEnd opens this screen and then calls MatchState.reset() in its
	 * finally block, which runs long before the first frame - so reading
	 * the field live gave null every time and quietly restored the very
	 * "rating +0" line this exists to remove.
	 */
	private final String paceLabel;
	private final Integer seasonPoints;

	public MatchEndScreen(boolean won, String opponent, Long myTimeMs, Long opponentTimeMs,
			Integer ratingDelta, Integer seasonPoints) {
		this(won, opponent, myTimeMs, opponentTimeMs, ratingDelta, seasonPoints, null);
	}

	/**
	 * @param myTimeMs the player's FINISH time, or null if they did not
	 *                 finish. Nullable for the same reason opponentTimeMs
	 *                 is: the run clock at the moment a match ended is
	 *                 not a finish time unless the player actually
	 *                 finished, and printing it as one under a heading
	 *                 that invites comparison says something untrue.
	 */
	public MatchEndScreen(boolean won, String opponent, Long myTimeMs, Long opponentTimeMs,
			Integer ratingDelta, Integer seasonPoints, String paceLabel) {
		super(new LiteralText(won ? "Victory" : "Defeat"));
		this.paceLabel = paceLabel;
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
		// Straight to the picker, because the most likely reason somebody
		// just raced a pace is that there was nobody to queue against -
		// and that has not changed in the last ten minutes. Sending them
		// back to the lobby to find the button again is a step that knows
		// what they want and asks anyway.
		this.addButton(new ButtonWidget(cx - 100, y + 26, 200, 20,
				new LiteralText("Race a Pace"),
				b -> this.client.openScreen(new com.speedrunmcalt.menu.RacePaceScreen(
						new AltMenuScreen(new TitleScreen())))));
		this.addButton(new ButtonWidget(cx - 100, y + 52, 200, 20,
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

		// Same treatment as the HUD and the menu: "vs" recedes, the
		// name is yellow. Player names are yellow everywhere.
		// "vs Pace 10:00" reads as a person with a peculiar name. A pace
		// is a target, so it gets the word that describes it - and NOT
		// the colour that means a person.
		//
		// Yellow is the player-name colour everywhere in this mod, so
		// spending it on a pace says "a human called Pace 10:00" in
		// exactly the way the wording is careful not to. Purple is what
		// labels already wear here (split names, seed type), and cyan is
		// what times wear - "your time" above is cyan too. So the line
		// ends up with no yellow on it at all, which is the point.
		if (paceLabel != null) {
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer, cx, y,
					new String[] { "target ", "Pace ", paceLabel },
					new int[] { DIM, com.speedrunmcalt.menu.Palette.PURPLE,
						com.speedrunmcalt.menu.Palette.CYAN });
		} else {
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer, cx, y,
					new String[] { "vs ", opponent == null ? "opponent" : opponent },
					new int[] { DIM, com.speedrunmcalt.menu.Palette.YELLOW });
		}
		y += 18;

		// A LOSS IS USUALLY NOT A SLOWER FINISH - it is no finish at all.
		//
		// This printed the run clock at the moment the match ended, which
		// is a finish time only if the player finished. Against a pace it
		// was actively misleading: "your time 10:01 / their time 10:00"
		// reads as losing by a second, when the player may have been
		// nowhere near the end. The pace's time is exact by construction,
		// so the comparison looked precise as well as wrong.
		if (myTimeMs != null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("your time   " + MatchState.formatTime(myTimeMs)),
					cx, y, ACCENT);
		} else {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("you did not finish"), cx, y, DIM);
		}
		y += 12;

		// A PACE'S TIME IS NEVER UNKNOWN. It is the tier - that is what a
		// pace IS - so "--:--" here was the screen forgetting the one
		// opponent whose finish is fixed before the race starts. It fell
		// back whenever the last poll had not landed the reveal, which is
		// exactly when the match ended.
		String theirs = paceLabel != null
				? paceLabel
				: opponentTimeMs == null ? "--:--" : MatchState.formatTime(opponentTimeMs);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("their time  " + theirs), cx, y, DIM);
		y += 18;

		// AN EXHIBITION HAS NO RATING LINE.
		//
		// "rating +0" is not a harmless way to say "unrated" - it reads
		// as a rating that was worked out and happened not to move, which
		// is a different and untrue statement. A pace scores nothing at
		// all: no Elo, no season points, no W-L-F. Say that.
		String pace = paceLabel;
		if (pace != null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(won ? "you beat the " + pace + " pace"
							: "you did not beat the " + pace + " pace"),
					cx, y, won ? WIN : LOSS);
			y += 12;
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("nothing rated - practice only"), cx, y, DIM);
		} else if (ratingDelta != null) {
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
