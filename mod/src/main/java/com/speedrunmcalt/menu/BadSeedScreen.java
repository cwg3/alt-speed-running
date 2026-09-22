package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.BadSeedVote;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Raise or agree to a bad-seed vote.
 *
 * Two jobs in one screen, because they are the same decision seen from
 * either side: proposing that the seed is unplayable, and agreeing with
 * an opponent who has already proposed it. Which one it is depends only
 * on whether the opponent has voted, so the wording changes and the
 * mechanism does not.
 *
 * Both cases are stated plainly. The person raising it is told nothing
 * happens until the opponent agrees, so a silent match is not mistaken
 * for a broken button. The person agreeing is shown that the match ends
 * for both of them with no rating change, so it is not mistaken for a
 * forfeit.
 */
public class BadSeedScreen extends Screen {
	private static final int PHOSPHOR = 0x56FF42;
	private static final int DIM = 0x8AA894;
	private static final int ALERT = 0xFFC65B;

	private final Screen parent;
	/** True when the opponent raised it first and we are agreeing. */
	private final boolean agreeing;
	/**
	 * What was wrong with the seed, in the player's own words.
	 *
	 * The quarantine list exists so a bad seed can be traced back to
	 * the filter bug that let it through, and a list of seed ids
	 * without reasons is a list of numbers. The first real vote
	 * recorded "test vote from PaceBot" as its reason, because the
	 * player had no way to say anything and the synthetic opponent's
	 * string was the one that carried.
	 *
	 * Optional on purpose. A player mid-run should not be made to
	 * write an essay to escape a seed they cannot play.
	 */
	private TextFieldWidget reason;

	public BadSeedScreen(Screen parent, boolean agreeing) {
		super(new LiteralText("Bad seed"));
		this.parent = parent;
		this.agreeing = agreeing;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;

		reason = new TextFieldWidget(this.textRenderer, cx - 100, this.height / 2 - 6, 200, 18,
				new LiteralText("Reason"));
		// The backend truncates at 200; stopping here means the player
		// sees what will actually be stored.
		reason.setMaxLength(120);
		reason.setSuggestion(agreeing ? "click here to say why" : "click here: no kelp? no wood? no route?");
		this.addButton(reason);
		this.setInitialFocus(reason);

		this.addButton(new ButtonWidget(cx - 100, this.height / 2 + 22, 200, 20,
				new LiteralText(agreeing ? "Keep playing" : "Cancel"),
				b -> this.client.openScreen(parent)));
		this.addButton(new ButtonWidget(cx - 100, this.height / 2 + 46, 200, 20,
				new LiteralText(agreeing ? "Agree - void this match" : "Vote: seed is unplayable"),
				b -> {
					BadSeedVote.cast(reason.getText().trim());
					this.client.openScreen(null);
				}));
	}

	/** Enter submits, so the vote is one line and a keypress. */
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == 257 || keyCode == 335) { // enter, numpad enter
			BadSeedVote.cast(reason.getText().trim());
			this.client.openScreen(null);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int y = this.height / 2 - 52;

		if (agreeing) {
			String who = MatchState.opponentUsername == null
					? "Your opponent" : MatchState.opponentUsername;
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText(who + " says this seed is unplayable."),
					cx, y, ALERT);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("If you agree, the match is voided for both of you."),
					cx, y + 16, PHOSPHOR);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("Nobody wins, and neither rating changes."),
					cx, y + 28, DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("Disagree and the race simply carries on."),
					cx, y + 44, DIM);
		} else {
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("Is this seed unplayable?"), cx, y, PHOSPHOR);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("For a seed that cannot be run at all - no blacksmith,"),
					cx, y + 18, DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("no wood, no route. Not for a seed you dislike."),
					cx, y + 30, DIM);
			drawCenteredText(matrices, this.textRenderer,
					new LiteralText("Your opponent must agree before anything happens."),
					cx, y + 48, ALERT);
		}
		// Label the field explicitly. The box alone was missed in use -
		// it sits directly above the button the player is reaching for,
		// and a focused text box is quiet next to a lit button.
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Reason (optional) - one line helps us fix the filter"),
				cx, this.height / 2 - 18, ALERT);

		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
