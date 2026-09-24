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
 * five seconds with it. The layout stays hidden; what is shared is the
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
	private static final int PHOSPHOR = Palette.CYAN;
	private static final int DIM = Palette.DIM;
	private static final int ALERT = Palette.YELLOW;

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
				new LiteralText(typeName(MatchState.seedType)), cx, y + 16, PHOSPHOR);

		long remaining = MatchState.countdownRemaining();
		long seconds = (remaining + 999) / 1000;
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(String.valueOf(seconds)), cx, y + 44, ALERT);

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
