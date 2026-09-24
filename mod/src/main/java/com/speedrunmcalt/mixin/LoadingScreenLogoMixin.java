package com.speedrunmcalt.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.WorldGenerationProgressTracker;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The alt tile in place of the chunk map while a world loads.
 *
 * The world-loading screen spends its time on a grid of coloured
 * squares showing chunk status. That grid is for someone debugging
 * world generation; to a player waiting for a match world it is
 * decoration that happens to move. The percentage above it, drawn by
 * render() and untouched here, is the part that actually says how long
 * is left - so replacing the grid costs no information.
 *
 * Injected into drawChunkMap rather than into render() because the map
 * centre arrives as ARGUMENTS. The caller computes it from the screen
 * size and passes it in, so the tile lands exactly where the grid
 * would have been without this mixin recomputing any layout, and it
 * keeps doing so if the caller's arithmetic ever changes.
 *
 * Applies to every world load, not just matches. The loading screen is
 * one of the few places the client's identity is visible at all, and a
 * client that only brands itself during a ranked match is stranger
 * than one that always does.
 */
@Mixin(LevelLoadingScreen.class)
public class LoadingScreenLogoMixin {
	private static final Identifier LOGO =
			new Identifier("speedrunmcalt", "textures/gui/logo_square.png");

	/** Source is 256 so a GUI scale above 1 is not magnifying it. */
	private static final int TEXTURE = 256;

	/**
	 * Drawn at 128, which is about what the chunk grid occupied. The
	 * tile is square, so this is one number rather than two.
	 */
	private static final int DRAWN = 128;

	@Inject(method = "drawChunkMap", at = @At("HEAD"), cancellable = true)
	private static void speedrunmcalt$drawLogoInstead(MatrixStack matrices,
			WorldGenerationProgressTracker tracker, int centerX, int centerY,
			int pixelSize, int gap, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getTextureManager() == null) {
			return;   // let the vanilla grid draw rather than nothing
		}
		client.getTextureManager().bindTexture(LOGO);
		// The tile is opaque, but the colour state is shared and
		// whatever drew last may have left a tint on it.
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		DrawableHelper.drawTexture(matrices,
				centerX - DRAWN / 2, centerY - DRAWN / 2,
				DRAWN, DRAWN,
				0.0f, 0.0f,
				TEXTURE, TEXTURE,
				TEXTURE, TEXTURE);
		ci.cancel();
	}
}
