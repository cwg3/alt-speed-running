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
public class ForfeitConfirmScreen extends Screen {
	/** Cyan. Named for what it IS - see the note in Palette. */
	private static final int ACCENT = Palette.CYAN;
	private static final int DIM = Palette.DIM;
	/** What the forfeit costs you: the consequence line. */
	private static final int ALERT = Palette.YELLOW;
	/** Irreversibility, which is the part worth hesitating over. */
	private static final int FINAL = Palette.MAGENTA;

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
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Forfeit this match?"), cx, this.height / 2 - 40, ACCENT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Your opponent is awarded the win and ratings change."),
				cx, this.height / 2 - 24, ALERT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("This cannot be undone."), cx, this.height / 2 - 12, FINAL);
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
