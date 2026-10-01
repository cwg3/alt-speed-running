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
		//
		// A PACE WEARS YELLOW FOR THE WORD AND CYAN FOR THE TIME, the
		// same as every other screen that names one.
		//
		// Yellow used to be held back from paces here, on the grounds
		// that it is the player-name colour and "Pace 10:00" in it would
		// read as a person with a peculiar name. The countdown screen
		// had been drawing paces yellow the whole time, though - it
		// renders them through the ordinary opponent path - so the rule
		// only ever held on two screens out of three, and the same pace
		// changed colour between the screen that announced it and the
		// screen that scored it. One treatment everywhere is worth more
		// than the distinction was; "target" and "vs" already say which
		// kind of thing it is, in the word immediately before it.
		if (paceLabel != null) {
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer, cx, y,
					new String[] { "target ", "Pace ", paceLabel },
					new int[] { DIM, com.speedrunmcalt.menu.Palette.YELLOW,
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
		//
		// AGAINST A PACE, ONLY A FINISH TIME EARNS A LINE. "you did not
		// finish / their time 30:00" restated the two lines around it -
		// the target is named above and the verdict below - so a loss
		// said the same 30:00 three times.
		if (myTimeMs != null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("your time   " + MatchState.formatTime(myTimeMs)),
					cx, y, ACCENT);
			y += paceLabel != null ? 18 : 12;
		} else if (paceLabel == null) {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("you did not finish"), cx, y, DIM);
			y += 12;
		}

		// A pace's time is the tier, already on the target line, so only
		// a real opponent gets a "their time" line.
		if (paceLabel == null) {
			String theirs = opponentTimeMs == null ? "--:--" : MatchState.formatTime(opponentTimeMs);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("their time  " + theirs), cx, y, DIM);
			y += 18;
		}

		// AN EXHIBITION HAS NO RATING LINE.
		//
		// "rating +0" is not a harmless way to say "unrated" - it reads
		// as a rating that was worked out and happened not to move, which
		// is a different and untrue statement. A pace scores nothing at
		// all: no Elo, no season points, no W-L-F. Say that.
		String pace = paceLabel;
		if (pace != null) {
			// The pace is coloured inside the sentence for the same reason
			// it is on the line above: both name the same fact, and drawing
			// "30:00" cyan in one place and red in the other made one pace
			// look like two different numbers. The verdict words keep the
			// win/loss colour, so the line still reads green or red at a
			// glance - which is the only thing it has to do from across a
			// room.
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer, cx, y,
					new String[] { won ? "you beat the " : "you did not beat the ",
						pace, " pace" },
					new int[] { won ? WIN : LOSS, com.speedrunmcalt.menu.Palette.CYAN,
						com.speedrunmcalt.menu.Palette.YELLOW });
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
