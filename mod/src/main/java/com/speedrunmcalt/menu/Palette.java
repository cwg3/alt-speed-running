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
	/**
	 * Royal purple, lightened from 0x7B2FF7.
	 *
	 * The original was by far the dimmest thing in the palette and it
	 * looked it next to the others. Relative luminance:
	 *
	 *   purple (old)  0.130     magenta  0.248
	 *   orange        0.316     cyan     0.531
	 *   yellow        0.683     phosphor 0.739
	 *
	 * Half the brightness of the next dimmest and a quarter of cyan.
	 * That is not a taste problem, it is where purple's light sits: it
	 * is almost entirely blue, and blue contributes about 7% of
	 * perceived brightness against green's 72%. A saturated royal
	 * purple is physically dim however vivid it looks on its own.
	 *
	 * This one is 0.298 - just above magenta, so it belongs to the same
	 * family as the rest. Matching the palette's MEDIAN would have
	 * meant something near 0xD4B8FF, which is lavender and stops
	 * reading as royal purple at all.
	 */
	public static final int PURPLE = 0xA97BFF;
	public static final int MAGENTA = 0xEC4899;
	public static final int ORANGE = 0xFF6A00;
	public static final int YELLOW = 0xFFD400;

	/**
	 * The original phosphor green, kept as a palette colour rather than
	 * replaced. It is the wordmark's colour - "alt" was green before
	 * any of the rest existed, and a brand mark is the one thing a
	 * restyle should leave alone.
	 */
	/**
	 * The wordmark's green, and NOTHING else.
	 *
	 * Reserved on purpose: it is the one colour that says "alt" rather
	 * than saying something about the state of a match. It drifted onto
	 * the MATCH FOUND title once, which made a status message look like
	 * branding.
	 *
	 * Several screens used to declare a local constant called PHOSPHOR
	 * that was actually CYAN - harmless until somebody "corrects" it to
	 * the real thing and spreads the green across half the UI. Those are
	 * named ACCENT now.
	 */
	public static final int PHOSPHOR = 0x56FF42;

	/**
	 * Won a split. A DIFFERENT green from PHOSPHOR on purpose.
	 *
	 * Phosphor is the trademark - it belongs to the wordmark and to
	 * "alt" on the HUD, and spending it on a split time would make the
	 * brand colour mean "you are ahead" half the time. This one is
	 * deeper and less electric: still unmistakably good news, still
	 * obviously not the logo.
	 */
	public static final int EMERALD = 0x35C759;

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
	/**
	 * Where one segment's own centre lands, in the same layout
	 * drawCenteredSegments would produce.
	 *
	 * For stacking something above a particular segment rather than
	 * above the whole line. The wordmark needs it: "speed" is five
	 * characters and "running" is seven, so the hyphen sits well left
	 * of the string's centre. Centring "alt" on the string is
	 * geometrically correct and looks wrong, because "alt" and the
	 * hyphen are both phosphor green and the eye pairs them.
	 */
	public static int segmentCenterX(TextRenderer font, int cx, String[] parts, int index) {
		int total = 0;
		for (String part : parts) {
			total += font.getWidth(part);
		}
		int x = cx - total / 2;
		for (int i = 0; i < index; i++) {
			x += font.getWidth(parts[i]);
		}
		return x + font.getWidth(parts[index]) / 2;
	}

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

	/**
	 * Splits "Pace 10:00" into {"Pace ", "10:00"}, or null for a person.
	 *
	 * A NAME IS OFTEN ALL THERE IS TO GO ON. Several endpoints send a
	 * pace as an opponent NAME with no flag beside it - a history row, a
	 * match detail, the HUD's opponent - so the shape of the string is
	 * the only thing that distinguishes one from a player, and the space
	 * is what does it, because a Minecraft username cannot contain one.
	 * Anything with a space is therefore not somebody's name.
	 *
	 * That is what keeps PaceBot whole and yellow. It is a real account
	 * with real rating changes, it sits in the history list next to the
	 * paces, and a prefix test alone would have split it into "Pace" and
	 * "Bot" and coloured a player like a target. The trailing space in
	 * "Pace " and the m:ss check are both load-bearing.
	 */
	public static String[] paceParts(String name) {
		if (name == null || !name.startsWith("Pace ")) {
			return null;
		}
		String time = name.substring("Pace ".length());
		return time.matches("\\d+:\\d{2}") ? new String[] { "Pace ", time } : null;
	}

	/**
	 * Draws an opponent at x: a person whole in yellow, a pace yellow for
	 * the word and cyan for the time. Returns the width drawn, for lines
	 * that put something after the name.
	 *
	 * Every screen that names an opponent goes through here. Each one
	 * used to decide for itself, which is how the same pace ended up
	 * yellow on the screen that scored it and two-coloured on the screen
	 * that announced it.
	 */
	public static int drawName(MatrixStack matrices, TextRenderer font,
			String name, int x, int y) {
		String[] parts = paceParts(name);
		if (parts == null) {
			String text = name == null ? "" : name;
			font.drawWithShadow(matrices, text, x, y, YELLOW);
			return font.getWidth(text);
		}
		font.drawWithShadow(matrices, parts[0], x, y, YELLOW);
		int w = font.getWidth(parts[0]);
		font.drawWithShadow(matrices, parts[1], x + w, y, CYAN);
		return w + font.getWidth(parts[1]);
	}

	/** drawName, centred on cx rather than starting at it. */
	public static void drawCenteredName(MatrixStack matrices, TextRenderer font,
			String name, int cx, int y) {
		String[] parts = paceParts(name);
		if (parts == null) {
			String text = name == null ? "" : name;
			font.drawWithShadow(matrices, text, cx - font.getWidth(text) / 2, y, YELLOW);
			return;
		}
		drawCenteredSegments(matrices, font, cx, y, parts, new int[] { YELLOW, CYAN });
	}
}
