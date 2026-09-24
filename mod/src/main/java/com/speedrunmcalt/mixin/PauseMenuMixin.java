package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.BadSeedVote;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.menu.BadSeedScreen;
import com.speedrunmcalt.menu.ForfeitConfirmScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.LiteralText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts Forfeit in the pause menu during a match.
 *
 * It was on F6 and nowhere else, which is the same as not existing: a
 * player mid-match looked for a way to concede and could not find one.
 * A keybind nobody is told about is not a feature.
 *
 * The pause menu is where a player already goes when they want out, so
 * that is where it belongs. F6 still works for anyone who knows it.
 * Both routes open the same confirmation - forfeiting hands the
 * opponent a win and should never be one keypress away mid-run.
 */
@Mixin(GameMenuScreen.class)
public abstract class PauseMenuMixin extends Screen {
	protected PauseMenuMixin() {
		super(null);
	}

	/**
	 * In a replay, Escape is the timeline rather than the pause menu.
	 *
	 * Escape is where a viewer already reaches for playback controls,
	 * and the vanilla pause menu offers nothing useful while watching -
	 * "Save and quit" and "Options" over a world that is not a save.
	 */
	@Inject(method = "init", at = @At("TAIL"))
	private void speedrunmcalt$replayTimeline(CallbackInfo ci) {
		if (com.speedrunmcalt.replay.ReplayPlayback.active()) {
			this.client.openScreen(new com.speedrunmcalt.replay.ReplayTimelineScreen());
		}
	}

	@Inject(method = "initWidgets", at = @At("TAIL"))
	private void speedrunmcalt$addForfeit(CallbackInfo ci) {
		if (!MatchState.inMatch() || MatchState.replayMode) {
			return;
		}
		// Below the vanilla buttons, clear of them. The pause menu's own
		// rows run from height/4 + 24 downward in 24px steps.
		this.addButton(new ButtonWidget(this.width / 2 - 102, this.height / 4 + 144, 204, 20,
				new LiteralText("Forfeit match"),
				button -> this.client.openScreen(new ForfeitConfirmScreen((Screen) (Object) this))));

		// Separate from forfeit on purpose. A forfeit is a loss you
		// accept; a bad seed is a claim that the match was never
		// playable, costs nobody rating, and needs the opponent to
		// agree. Putting them on the same button would blur that.
		String label = MatchState.opponentProposedBadSeed
				? "Opponent says: bad seed - review"
				: BadSeedVote.voted ? "Bad seed - waiting for opponent" : "Bad seed";
		ButtonWidget badSeed = new ButtonWidget(this.width / 2 - 102, this.height / 4 + 168, 204, 20,
				new LiteralText(label),
				button -> this.client.openScreen(new BadSeedScreen((Screen) (Object) this,
						MatchState.opponentProposedBadSeed)));
		// Voting twice does nothing; the button says so rather than
		// silently accepting another click.
		badSeed.active = !BadSeedVote.voted;
		this.addButton(badSeed);
	}
}
