package com.speedrunmcalt.menu;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * A button for alt's own screens: dark glass with a neon edge, in place
 * of the stone texture.
 *
 * The stone was the one thing on the lobby that was still stock
 * Minecraft - everything around it is Ui.panel's glass and cyan frame
 * over a neon grid. Resting, this is that panel exactly; hovered or
 * focused, the edge lights to full cyan with a one-pixel halo and the
 * glass picks up a faint cyan wash; inactive, the edge and the label go
 * grey.
 *
 * A drop-in for ButtonWidget - same constructor, same behaviour, only
 * the drawing differs. Vanilla's own screens keep their stone buttons,
 * including the ones alt adds to the pause menu: Mojang's screens stay
 * in Mojang's style, as on the title screen.
 */
public class NeonButton extends ButtonWidget {
	private static final int HALO = 0x40000000 | Palette.CYAN;
	private static final int LIT = 0xFF000000 | Palette.CYAN;
	private static final int WASH = 0x22000000 | Palette.CYAN;
	private static final int DEAD_EDGE = 0x30FFFFFF;
	private static final int LABEL = 0xFFFFFF;
	private static final int DEAD_LABEL = 0x707070;

	public NeonButton(int x, int y, int width, int height, Text message, PressAction onPress) {
		super(x, y, width, height, message, onPress);
	}

	@Override
	public void renderButton(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		int x1 = this.x;
		int y1 = this.y;
		int x2 = this.x + this.width;
		int y2 = this.y + this.height;
		boolean lit = this.active && this.isHovered();

		fill(matrices, x1, y1, x2, y2, Ui.PANEL);
		if (lit) {
			fill(matrices, x1, y1, x2, y2, WASH);
			frame(matrices, x1 - 1, y1 - 1, x2 + 1, y2 + 1, HALO);
		}
		frame(matrices, x1, y1, x2, y2, !this.active ? DEAD_EDGE : lit ? LIT : Ui.FRAME);

		int alpha = (int) (this.alpha * 255.0F) << 24;
		MinecraftClient client = MinecraftClient.getInstance();
		drawCenteredText(matrices, client.textRenderer, this.getMessage(),
				x1 + this.width / 2, y1 + (this.height - 8) / 2,
				(this.active ? LABEL : DEAD_LABEL) | alpha);
	}

	private static void frame(MatrixStack matrices, int x1, int y1, int x2, int y2, int color) {
		fill(matrices, x1, y1, x2, y1 + 1, color);
		fill(matrices, x1, y2 - 1, x2, y2, color);
		fill(matrices, x1, y1 + 1, x1 + 1, y2 - 1, color);
		fill(matrices, x2 - 1, y1 + 1, x2, y2 - 1, color);
	}
}
