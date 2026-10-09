package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.Forfeit;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Confirmation before giving up a match.
 *
 * Deliberately not a bare keybind. Forfeiting is irreversible and hands
 * the opponent a win, so it should never be one mistaken keypress away
 * mid-run. Cancel is the default focus.
 */
public class ForfeitConfirmScreen extends AltScreen {
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = Palette.CYAN;
	private static final int DIM = Palette.DIM;
	/** What the forfeit costs you: the consequence line. */
	private static final int ALERT = Palette.YELLOW;
	/** Irreversibility, which is the part worth hesitating over. */
	// Orange, the colour a forfeit carries everywhere else now - the
	// history row, the match detail header. The screen that asks and
	// the record that results should agree.
	private static final int FINAL = Palette.ORANGE;

	private final Screen parent;

	public ForfeitConfirmScreen(Screen parent) {
		super(new LiteralText("Forfeit match"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		this.addButton(new ButtonWidget(cx - 100, this.height / 2 + 10, 200, 20,
				new LiteralText("Keep playing"), b -> this.client.openScreen(parent)));
		this.addButton(new ButtonWidget(cx - 100, this.height / 2 + 36, 200, 20,
				new LiteralText("Forfeit - opponent wins"), b -> {
					Forfeit.surrender(this.client);
					this.client.openScreen(null);
				}));
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int cy = this.height / 2;
		// Framed like every other alt screen. This one is drawn over the
		// live world rather than the menu backdrop, and the three lines
		// floating loose over a forest were the hardest text in the mod
		// to read - on the one screen where reading it matters most.
		String consequence = "Your opponent is awarded the win and ratings change.";
		int half = Math.max(110, this.textRenderer.getWidth(consequence) / 2 + 12);
		Ui.panel(matrices, cx - half, cy - 60, cx + half, cy - 2);
		Ui.bigCentered(matrices, this.textRenderer, "Forfeit this match?", cx, cy - 52, 1.5f, ACCENT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(consequence), cx, cy - 32, ALERT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("This cannot be undone."), cx, cy - 19, FINAL);
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
