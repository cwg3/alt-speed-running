package com.speedrunmcalt.menu;

import com.speedrunmcalt.match.RaceReset;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

/**
 * Confirmation before Reset Race World.
 *
 * Shaped like ForfeitConfirmScreen, for the same reason: a reset throws
 * away everything in the world - the portal, the inventory, the hole
 * dug - and it should never be one stray keypress away mid-run. Keep
 * playing is first.
 */
public class ResetWorldConfirmScreen extends AltScreen {
	private static final int ACCENT = Palette.CYAN;
	/** The cost: the clock does not stop. */
	private static final int ALERT = Palette.YELLOW;

	private final Screen parent;

	public ResetWorldConfirmScreen(Screen parent) {
		super(new LiteralText("Reset world"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		this.addButton(new NeonButton(cx - 100, this.height / 2 + 10, 200, 20,
				new LiteralText("Keep playing"), b -> this.client.openScreen(parent)));
		this.addButton(new NeonButton(cx - 100, this.height / 2 + 36, 200, 20,
				new LiteralText("Reset - start from spawn"), b -> {
					this.client.openScreen(null);
					RaceReset.request();
				}));
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		this.renderBackground(matrices);
		int cx = this.width / 2;
		int cy = this.height / 2;
		String what = "A fresh copy of this seed, back at spawn with nothing.";
		int half = Math.max(110, this.textRenderer.getWidth(what) / 2 + 12);
		Ui.panel(matrices, cx - half, cy - 60, cx + half, cy - 2);
		Ui.bigCentered(matrices, this.textRenderer, "Reset your world?", cx, cy - 52, 1.5f, ACCENT);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText(what), cx, cy - 32, Palette.DIM);
		drawCenteredText(matrices, this.textRenderer,
				new LiteralText("Your clock keeps running."), cx, cy - 19, ALERT);
		super.render(matrices, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
