package com.speedrunmcalt.menu;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Every alt screen extends this, and gets the shared backdrop wherever
 * it already calls renderBackground.
 *
 * Overriding renderBackground rather than adding a new call means no
 * screen can be restyled half-way: the dirt texture is gone from all of
 * them or from none.
 */
public abstract class AltScreen extends Screen {
	protected AltScreen(Text title) {
		super(title);
	}

	@Override
	public void renderBackground(MatrixStack matrices, int vOffset) {
		// Screen.render is handed the frame duration by GameRenderer;
		// renderBackground is not, so read the same value back here.
		Ui.backdrop(matrices, this.client, this.width, this.height,
				this.client.getLastFrameDuration());
	}
}
