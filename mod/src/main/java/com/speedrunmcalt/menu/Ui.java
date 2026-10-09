package com.speedrunmcalt.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * The drawing pieces every alt screen shares: the backdrop, panels,
 * large titles and the logo.
 *
 * The screens were text on the dirt texture, which is the first thing
 * anyone sees and the thing a mod gets judged on before a single match.
 * Kept in one place so the look is one decision, the way Palette keeps
 * the colours one decision.
 *
 * Extends DrawableHelper only to reach its protected static gradient.
 */
public final class Ui extends DrawableHelper {
	private static final Identifier LOGO =
			new Identifier("speedrunmcalt", "textures/gui/logo_alt.png");
	private static final int LOGO_W = 288;
	private static final int LOGO_H = 186;
	/** The lobby render - see gen_wallpapers.py's LOBBY. */
	public static final Identifier LOBBY =
			new Identifier("speedrunmcalt", "textures/gui/lobby.png");

	private static final Identifier LOGO_HUD =
			new Identifier("speedrunmcalt", "textures/gui/logo_alt_hud.png");
	private static final int LOGO_HUD_W = 96;
	private static final int LOGO_HUD_H = 62;

	/**
	 * The CRT screen colour from gen_logo.py's SCREEN_BG, as a tint.
	 * The backdrop darkens toward it rather than toward pure black, so
	 * the panorama reads as seen through the same glass as the wordmark.
	 */
	private static final int GLASS = 0x07110A;

	/** Panel fill, and the faint cyan frame around it. */
	private static final int PANEL = 0xB8000000 | GLASS;
	private static final int FRAME = 0x5A000000 | Palette.CYAN;

	/**
	 * One renderer for every screen, so the panorama keeps turning from
	 * where it was when the player clicks between menus. A renderer per
	 * screen snapped back to the starting angle on every click.
	 */
	private static RotatingCubeMapRenderer panorama;

	private Ui() {
	}

	/**
	 * Full-screen background.
	 *
	 * In a world it is a dark wash over the game, heavier than vanilla's
	 * so the text holds up over snow and water. Out of one it is the
	 * vanilla title panorama under a vignette, instead of dirt.
	 */
	public static void backdrop(MatrixStack matrices, MinecraftClient client,
			int width, int height, float delta) {
		if (client.world != null) {
			gradient(matrices, 0, 0, width, height, 0xA0000000 | GLASS, 0xD0000000 | GLASS);
			return;
		}
		// The lobby's picture behind every menu, under a heavier wash than
		// the lobby itself uses: these screens are tables and lists, and
		// the neon at the sides competed with them at full strength. The
		// vanilla panorama stays as the fallback if the texture is missing.
		if (cover(matrices, client, LOBBY, width, height)) {
			fill(matrices, 0, 0, width, height, 0x90000000 | GLASS);
			return;
		}
		if (panorama == null) {
			panorama = new RotatingCubeMapRenderer(TitleScreen.PANORAMA_CUBE_MAP);
		}
		panorama.render(delta, 1.0f);
		int mid = height / 2;
		gradient(matrices, 0, 0, width, mid, 0xC8000000 | GLASS, 0x70000000 | GLASS);
		gradient(matrices, 0, mid, width, height, 0x70000000 | GLASS, 0xE0000000 | GLASS);
	}

	/** A dark translucent box with a one-pixel cyan frame. */
	public static void panel(MatrixStack matrices, int x1, int y1, int x2, int y2) {
		panel(matrices, x1, y1, x2, y2, PANEL);
	}

	/**
	 * The HUD's panel: the same frame over a lighter fill. A menu panel
	 * sits over a backdrop that is already dark; the HUD sits over the
	 * game for twenty minutes, and at menu opacity it was a black slab
	 * in the corner of the world.
	 */
	public static void hudPanel(MatrixStack matrices, int x1, int y1, int x2, int y2) {
		panel(matrices, x1, y1, x2, y2, 0x88000000 | GLASS);
	}

	private static void panel(MatrixStack matrices, int x1, int y1, int x2, int y2, int fillColor) {
		fill(matrices, x1, y1, x2, y2, fillColor);
		fill(matrices, x1, y1, x2, y1 + 1, FRAME);
		fill(matrices, x1, y2 - 1, x2, y2, FRAME);
		fill(matrices, x1, y1 + 1, x1 + 1, y2 - 1, FRAME);
		fill(matrices, x2 - 1, y1 + 1, x2, y2 - 1, FRAME);
	}

	/**
	 * Text at a multiple of the font size, centred on cx with its top
	 * at y. The shadow scales with it, which at 3x is the heavy
	 * drop-shadow a title wants.
	 */
	public static void bigCentered(MatrixStack matrices, TextRenderer font,
			String text, int cx, int y, float scale, int color) {
		matrices.push();
		matrices.translate(cx, y, 0);
		matrices.scale(scale, scale, 1.0f);
		font.drawWithShadow(matrices, text, -font.getWidth(text) / 2.0f, 0, color);
		matrices.pop();
	}

	/**
	 * The "alt" wordmark from brand/, centred on cx. Returns the width
	 * drawn.
	 *
	 * Filtered bilinear: the texture is the glyphs at 6x with their
	 * bloom, and no on-screen size divides that evenly at every GUI
	 * scale. Nearest sampling dropped whole glyph pixels at some sizes;
	 * smoothing is invisible under a glow that is soft anyway.
	 */
	public static int logo(MatrixStack matrices, MinecraftClient client, int cx, int top, int height) {
		return logo(matrices, client, cx, top, height, false);
	}

	/**
	 * The wordmark, or its HUD cut.
	 *
	 * The HUD draws "alt" about 16px tall over the open world, where the
	 * glow baked into logo_alt.png - meant for a dark screen - read as a
	 * dirty outline. logo_alt_hud.png is the same letters bold and flat,
	 * no glow, from gen_logo.py. An earlier fix thickened the strokes by
	 * drawing the glowing texture four times offset, which stacked the
	 * glow into a hard fringe round every letter.
	 */
	public static int logo(MatrixStack matrices, MinecraftClient client, int cx, int top, int height,
			boolean hud) {
		Identifier id = hud ? LOGO_HUD : LOGO;
		int width = height * LOGO_W / LOGO_H;
		client.getTextureManager().bindTexture(id);
		if (FILTERED.add(id)) {
			client.getTextureManager().getTexture(id).setFilter(true, false);
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		// Texture size passed as the region: both files share an aspect,
		// so the HUD cut's smaller pixel size does not change the layout.
		int texW = hud ? LOGO_HUD_W : LOGO_W;
		int texH = hud ? LOGO_HUD_H : LOGO_H;
		drawTexture(matrices, cx - width / 2, top, width, height, 0, 0, texW, texH, texW, texH);
		RenderSystem.disableBlend();
		return width;
	}

	/** Wallpapers whose texture has been switched to bilinear already. */
	private static final java.util.Set<Identifier> FILTERED = new java.util.HashSet<>();

	/** Size the wallpapers are shipped at - gen_wallpapers.py --width. */
	private static final int WALL_W = 1280;
	private static final int WALL_H = 720;

	/**
	 * The seed type's wallpaper, filling the screen. False when there is
	 * none for this type, so the caller can draw something else.
	 *
	 * Cover-fit: the picture fills the screen and the overflow is cropped
	 * evenly, rather than letterboxed - a 21:9 monitor loses a strip of
	 * sky and floor, which the renders leave empty for exactly that.
	 */
	public static boolean wallpaper(MatrixStack matrices, MinecraftClient client,
			String seedType, int width, int height) {
		if (seedType == null) {
			return false;
		}
		return cover(matrices, client, new Identifier("speedrunmcalt",
				"textures/gui/seed/" + seedType.toLowerCase(java.util.Locale.ROOT) + ".png"),
				width, height);
	}

	/**
	 * One of the gen_wallpapers.py renders filling the screen, cover-fit.
	 * False when the texture is not there.
	 */
	public static boolean cover(MatrixStack matrices, MinecraftClient client, Identifier id,
			int width, int height) {
		if (!client.getResourceManager().containsResource(id)) {
			return false;
		}
		client.getTextureManager().bindTexture(id);
		if (FILTERED.add(id)) {
			// Bilinear, like the logo: it is drawn at whatever size the
			// window is, and nearest sampling shimmered along every
			// neon edge.
			client.getTextureManager().getTexture(id).setFilter(true, false);
		}
		int regionW = WALL_W;
		int regionH = WALL_H;
		if (width * WALL_H > height * WALL_W) {
			regionH = WALL_W * height / width;   // wider screen: crop top and bottom
		} else {
			regionW = WALL_H * width / height;   // taller screen: crop the sides
		}
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		drawTexture(matrices, 0, 0, width, height,
				(WALL_W - regionW) / 2.0f, (WALL_H - regionH) / 2.0f,
				regionW, regionH, WALL_W, WALL_H);
		return true;
	}

	/**
	 * bigCentered with a dark outline, for text laid straight over a
	 * picture. A drop shadow alone disappeared into the neon wherever the
	 * number crossed an edge of the same colour.
	 */
	public static void bigCenteredOutlined(MatrixStack matrices, TextRenderer font,
			String text, int cx, int y, float scale, int color) {
		matrices.push();
		matrices.translate(cx, y, 0);
		matrices.scale(scale, scale, 1.0f);
		float x = -font.getWidth(text) / 2.0f;
		for (int[] o : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }) {
			font.draw(matrices, text, x + o[0], o[1], 0x000000);
		}
		font.drawWithShadow(matrices, text, x, 0, color);
		matrices.pop();
	}

	/** DrawableHelper's gradient, without needing a Screen instance. */
	public static void gradient(MatrixStack matrices, int x1, int y1, int x2, int y2,
			int top, int bottom) {
		RenderSystem.disableTexture();
		RenderSystem.enableBlend();
		RenderSystem.disableAlphaTest();
		RenderSystem.defaultBlendFunc();
		RenderSystem.shadeModel(7425);
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		buffer.begin(7, VertexFormats.POSITION_COLOR);
		fillGradient(matrices.peek().getModel(), buffer, x1, y1, x2, y2, 0, top, bottom);
		tessellator.draw();
		RenderSystem.shadeModel(7424);
		RenderSystem.disableBlend();
		RenderSystem.enableAlphaTest();
		RenderSystem.enableTexture();
	}
}
