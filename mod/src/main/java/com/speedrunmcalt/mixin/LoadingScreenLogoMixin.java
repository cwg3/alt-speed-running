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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World generation wipes the alt tile in as it completes.
 *
 * The loading screen draws a grid of chunks, one cell each, coloured
 * by generation status - a dark surround for chunks not started, and
 * pure white for ChunkStatus.FULL. That white region grows from the
 * centre outwards as the world builds, which is already a progress
 * wipe; it just happens to be wiping in a flat white square.
 *
 * So the tile is drawn UNDERNEATH the grid at exactly the grid's
 * bounds, and the cells that would have been painted white are not
 * painted at all. Everything else covers the tile as before. The
 * result is the logo appearing through finished chunks, spreading
 * with generation, complete when the world is.
 *
 * Done as two small injections rather than by replacing drawChunkMap:
 *
 *   - Only FULL maps to 0xFFFFFF in STATUS_TO_COLOR, and the loop
 *     ORs 0xFF000000 onto it, so "the colour is opaque white" and
 *     "this chunk is finished" are the same test. Redirecting fill
 *     and dropping the white ones needs no access to that private
 *     map and leaves vanilla's loop, ordering and colours intact.
 *
 *   - The border fills at the top of drawChunkMap are unaffected:
 *     they use -16772609, not white. They also only run when the gap
 *     argument is non-zero, which the caller never passes.
 *
 * Applies to every world load, not just matches. The loading screen
 * is one of the few places the client's identity is visible at all.
 */
@Mixin(LevelLoadingScreen.class)
public class LoadingScreenLogoMixin {
	private static final Identifier LOGO =
			new Identifier("speedrunmcalt", "textures/gui/logo_square.png");

	/** Source is 256 so a GUI scale above 1 is not magnifying it. */
	private static final int TEXTURE = 256;

	/** ChunkStatus.FULL's colour, after the loop ORs in full alpha. */
	private static final int FINISHED = 0xFFFFFFFF;

	/**
	 * The tile, behind the grid and exactly its size.
	 *
	 * The three lines of arithmetic are vanilla's own, from the top of
	 * drawChunkMap: cell pitch, grid extent, top-left corner. They are
	 * repeated rather than read back because the method computes them
	 * into locals and nothing exposes them. If vanilla's layout ever
	 * changed, the tile would sit off-centre behind the grid - visibly
	 * wrong, but not a crash, and the grid itself would still be
	 * correct because it is still vanilla drawing it.
	 */
	@Inject(method = "drawChunkMap", at = @At("HEAD"))
	private static void speedrunmcalt$drawTileUnderGrid(MatrixStack matrices,
			WorldGenerationProgressTracker tracker, int centerX, int centerY,
			int pixelSize, int gap, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getTextureManager() == null) {
			return;
		}
		int pitch = pixelSize + gap;
		int extent = tracker.getSize() * pitch - gap;
		if (extent <= 0) {
			return;
		}
		client.getTextureManager().bindTexture(LOGO);
		// The colour state is shared and whatever drew last may have
		// left a tint on it.
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		DrawableHelper.drawTexture(matrices,
				centerX - extent / 2, centerY - extent / 2,
				extent, extent,
				0.0f, 0.0f,
				TEXTURE, TEXTURE,
				TEXTURE, TEXTURE);
	}

	/**
	 * A finished chunk paints nothing, so the tile shows through.
	 *
	 * Every other status paints exactly what vanilla painted.
	 */
	@Redirect(method = "drawChunkMap",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/DrawableHelper;"
							+ "fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"))
	private static void speedrunmcalt$skipFinishedChunks(MatrixStack matrices,
			int x1, int y1, int x2, int y2, int color) {
		if (color == FINISHED) {
			return;
		}
		DrawableHelper.fill(matrices, x1, y1, x2, y2, color);
	}
}
