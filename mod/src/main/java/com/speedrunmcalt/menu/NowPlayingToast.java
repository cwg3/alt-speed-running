package com.speedrunmcalt.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.util.math.MatrixStack;

/**
 * "Now playing" in the corner, the first time the menu music starts in
 * a launch - so the song is credited where it is heard.
 *
 * Drawn on the plain dark toast frame (the advancement strip - the
 * system strip carries a warning "!"), the song in yellow and the
 * artist in white, gone after five seconds.
 */
public class NowPlayingToast implements Toast {
	private static final long SHOWN_MS = 5000L;
	private final String song;
	private final String artist;

	public NowPlayingToast(String song, String artist) {
		this.song = song;
		this.artist = artist;
	}

	@Override
	public Visibility draw(MatrixStack matrices, ToastManager manager, long startTime) {
		manager.getGame().getTextureManager().bindTexture(TOASTS_TEX);
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		manager.drawTexture(matrices, 0, 0, 0, 0, 160, 32);
		// The song on top in yellow, the artist under it in white - the
		// toast is two lines tall and 160 wide, and "Now playing" plus
		// both names did not fit. The note says what this is.
		manager.getGame().textRenderer.draw(matrices, "\u266a " + this.song, 18.0f, 7.0f, 0xFFFFFF00);
		manager.getGame().textRenderer.draw(matrices, this.artist, 18.0f, 18.0f, 0xFFFFFFFF);
		return startTime >= SHOWN_MS ? Visibility.HIDE : Visibility.SHOW;
	}
}
