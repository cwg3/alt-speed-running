package com.speedrunmcalt.menu;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * The project's colours, in one place.
 *
 * They were phosphor green hardcoded into six files, which made a
 * palette change a search-and-replace across unrelated screens. Defined
 * once here so the next change is one edit.
 */
public final class Palette {
	public static final int CYAN = 0x22D3EE;
	public static final int PURPLE = 0x7B2FF7;
	public static final int MAGENTA = 0xEC4899;
	public static final int ORANGE = 0xFF6A00;
	public static final int YELLOW = 0xFFD400;

	/** Secondary text: present, but not competing with the palette. */
	public static final int DIM = 0x6B7280;
	/** Something went wrong. */
	public static final int ALERT = 0xFF6B5B;

	private Palette() {
	}

	/**
	 * Draws a run of differently-coloured segments as one centred line.
	 *
	 * Minecraft's drawCenteredText takes a single colour, so a wordmark
	 * like "speed-running" in three colours has to be measured and laid
	 * out by hand: total the widths, start half of that left of centre,
	 * and advance by each segment as it is drawn.
	 */
	public static void drawCenteredSegments(MatrixStack matrices, TextRenderer font,
			int cx, int y, String[] parts, int[] colors) {
		int total = 0;
		for (String part : parts) {
			total += font.getWidth(part);
		}
		int x = cx - total / 2;
		for (int i = 0; i < parts.length; i++) {
			font.drawWithShadow(matrices, parts[i], x, y,
					colors[Math.min(i, colors.length - 1)]);
			x += font.getWidth(parts[i]);
		}
	}
}
