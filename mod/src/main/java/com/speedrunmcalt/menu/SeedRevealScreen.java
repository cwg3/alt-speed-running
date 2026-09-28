package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.MatchState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * The seed type, and a countdown, before the race begins.
 *
 * Both players are told which opening they have drawn - village,
 * desert temple, ruined portal, shipwreck, buried treasure - and get
 * ten seconds with it. The layout stays hidden; what is shared is the
 * route you are about to run, so the opening is planned rather than
 * improvised from a cold start. The race begins when the count reaches
 * zero.
 *
 * It is a PAUSE screen, and that does the real work. MatchClock
 * refuses to claim a run start while the game is paused, so the clock
 * cannot begin until this closes - no extra coordination, and the
 * existing rule that loading is never charged to the run still holds.
 * World generation happens behind this screen, which turns load time
 * from something merely uncharged into the planning window itself.
 */
public class SeedRevealScreen extends Screen {
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = Palette.CYAN;
	private static final int DIM = Palette.DIM;
	/**
	 * The countdown number: cyan.
	 *
	 * Yellow before, which collided with the two things either side of
	 * it - the opponent's name is yellow, as player names are
	 * everywhere, and MATCH FOUND is yellow moments earlier. Three
	 * yellows in sequence stopped the number being the thing the eye
	 * lands on.
	 */
	private static final int COUNT = Palette.CYAN;

	public SeedRevealScreen() {
		super(new LiteralText("Seed"));
	}

	/** Readable name for a seed type, e.g. "DESERT TEMPLE". */
	private static String typeName(String raw) {
		if (raw == null) {
			return "UNKNOWN";
		}
		return raw.replace('_', ' ').toUpperCase();
	}

	@Override
	protected void init() {
		// Clear the MATCH FOUND title, which has done its job by now.
		//
		// This screen pauses the game, and a paused client does not tick
		// the HUD - so the title's own timer stops here and resumes the
		// instant the countdown closes, putting it back on screen for
		// its remaining couple of seconds just as the race starts.
		//
		// setTitles(null, null, -1, -1, -1) is vanilla's own reset: all
		// five arguments have to be null-or-negative or it takes the
		// other branch and only adjusts timings.
		if (this.client != null && this.client.inGameHud != null) {
			this.client.inGameHud.setTitles(null, null, -1, -1, -1);
		}
	}

	@Override
	public void tick() {
		if (MatchState.countdownRemaining() <= 0) {
			// Closing releases the pause, which is what lets MatchClock
			// claim the run start.
			this.client.openScreen(null);
		}
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int y = this.height / 2 - 40;

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("YOUR SEED"), cx, y, DIM);
		drawCenteredText(matrices, this.textRenderer,
				// Purple, not cyan. The countdown on this same screen is
				// cyan, so the seed type sat in the timer's colour and
				// the two read as one kind of information. The match
				// history screen already draws the type in purple -
				// this makes the seed type one colour everywhere it
				// appears, and leaves cyan meaning "clock".
				new LiteralText(typeName(MatchState.seedType)), cx, y + 16, Palette.PURPLE);

		// Who you are actually racing.
		//
		// The title overlay says it for two seconds while the world
		// loads, which a player watching their practice world save is
		// not necessarily reading. These ten seconds are the one
		// moment both players are certainly looking at the screen with
		// nothing else to do, so the name belongs here.
		// A PACE ARRIVES HERE AS AN OPPONENT NAME - the literal string
		// "Pace 30:00" - and with no branch of its own it went out in one
		// piece in the player-name yellow, so the time never got the cyan
		// it wears on the announce and end screens. Split it, and the
		// same pace is the same colour on all three.
		String pace = MatchState.paceLabel;
		if (pace != null) {
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer,
					cx, y + 30,
					new String[] { "vs ", "Pace ", pace },
					new int[] { DIM, com.speedrunmcalt.menu.Palette.YELLOW,
						com.speedrunmcalt.menu.Palette.CYAN });
		} else {
			String vs = MatchState.opponentUsername;
			com.speedrunmcalt.menu.Palette.drawCenteredSegments(matrices, this.textRenderer,
					cx, y + 30,
					new String[] { "vs ", vs == null ? "opponent" : vs },
					new int[] { DIM, com.speedrunmcalt.menu.Palette.YELLOW });
		}

		long remaining = MatchState.countdownRemaining();
		long seconds = (remaining + 999) / 1000;
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(String.valueOf(seconds)), cx, y + 44, COUNT);
		// The server's commitment to this world, where a camera can see it.
		//
		// Both players are shown it before either can play, so a copy of
		// it exists outside the server from the moment the match is made
		// - in a screenshot, in a VOD, in whatever a viewer clipped.
		// Half this community streams, which makes this line the
		// cheapest independent timestamp available to it.
		//
		// Eight characters, not sixty-four: the full value is already in
		// the log for anyone verifying properly, and this has to be
		// readable off a stream at a glance without crowding the
		// countdown it sits under. DIM, and left alone by the eye unless
		// somebody is looking for it - it is evidence, not information
		// the player needs in order to run.
		//
		// Nothing is drawn when the backend sent no commitment. An empty
		// label would read as a guarantee that happens to be blank.
		com.speedrunmcalt.net.DrawProof proof = MatchState.draw;
		if (com.speedrunmcalt.net.DrawProof.isPresent(proof)) {
			String season = proof.seasonId == null ? "" : proof.seasonId + " ";
			String line = "commitment " + season + proof.shortForm();
			// HALF SIZE, AND ABOVE THE CLOSING LINE. At full size and
			// underneath it, it sat in the same weight and colour as
			// "the race begins at zero" and read as a second instruction
			// - the last thing the eye landed on before a race, which is
			// the opposite of what it is for. Half size makes it a
			// footnote, which is its actual rank: evidence that has to be
			// present and legible, not something the player must act on.
			//
			// Scaling the matrix means the draw call works in DOUBLED
			// coordinates, so the centring maths has to double too -
			// drawCenteredText would centre on the unscaled cx and land
			// the line a quarter of the screen to the left.
			matrices.push();
			matrices.scale(0.5f, 0.5f, 1.0f);
			this.textRenderer.draw(matrices, line,
					cx * 2 - this.textRenderer.getWidth(line) / 2.0f,
					(y + 57) * 2.0f, DIM);
			matrices.pop();
		}

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("the race begins at zero"), cx, y + 64, DIM);

		super.render(matrices, mouseX, mouseY, delta);
	}

	/** Pausing is the mechanism, not a side effect - see the class note. */
	@Override
	public boolean isPauseScreen() {
		return true;
	}

	/** Escape must not skip the countdown. */
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
