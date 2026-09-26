package com.speedrunmcalt.menu;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * MATCH FOUND, for a player who is looking at a menu.
 *
 * The in-game HUD draws the title overlay, and a HUD needs a world.
 * Queued from the title screen - or from Matches, or anywhere else in
 * these menus - there is no world behind the screen, so the overlay
 * had nothing to render on and the only signal was the chime. A noise
 * followed by several seconds of an unchanged screen reads as a bug,
 * which is precisely what it looked like.
 *
 * So the rule is the one a player would state: whenever the chime
 * fires, the next thing they see says MATCH FOUND. In a world that is
 * the title overlay; in a menu it is this.
 *
 * Transient by design - no buttons, and it is replaced when the match
 * world starts loading. Escape is ignored rather than wired to
 * anything: there is nothing to go back to, the match is already made.
 */
public class MatchFoundScreen extends Screen {
	private final String opponent;
	/** Non-null when this is a pace, and then it is the time to beat. */
	private final String paceLabel;

	public MatchFoundScreen(String opponent) {
		this(opponent, null);
	}

	public MatchFoundScreen(String opponent, String paceLabel) {
		super(new LiteralText(paceLabel == null ? "Match found" : "Race a Pace"));
		this.opponent = opponent == null ? "opponent" : opponent;
		this.paceLabel = paceLabel;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int cy = this.height / 2;

		// Drawn at double size to match the weight of the in-world
		// title, so the two contexts announce the same thing with the
		// same emphasis. Coordinates are halved because the scale
		// applies to them too.
		// A pace is not a match. Announcing one as MATCH FOUND would be
		// the single most misleading thing this screen could say, and it
		// is the thing a player would remember on finding out.
		boolean pace = paceLabel != null;
		matrices.push();
		matrices.scale(2.0f, 2.0f, 1.0f);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(pace ? "RACE A PACE" : "MATCH FOUND"),
				cx / 2, (cy - 20) / 2, Palette.YELLOW);
		matrices.pop();

		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(pace ? "target " + paceLabel : "vs " + opponent),
				cx, cy + 6, Palette.YELLOW);
		if (pace) {
			// Said here, before the run, not in a results screen
			// afterwards. A player should know what they are racing
			// while it still changes how they play it.
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("a time to beat - nothing is rated"),
					cx, cy + 24, Palette.DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("loading world..."), cx, cy + 38, Palette.DIM);
		} else {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("loading world..."), cx, cy + 24, Palette.DIM);
		}
	}
}
