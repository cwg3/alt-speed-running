package com.speedrunmcalt.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Random;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/**
 * A dead channel tuning in to "alt", between MATCH FOUND and the
 * chunk loading screen.
 *
 * Black glass, static, and the wordmark fighting for sync: it rolls,
 * tears sideways and splits its colour while the static clears, holds
 * steady for a moment, then the tube collapses to a line, to a dot, to
 * nothing - and the world starts loading out of the dark.
 *
 * Driven by wall-clock time from the moment it opened, not by ticks,
 * so it plays at the same speed at any frame rate. Matchmaker holds the
 * world creation back for TOTAL_MS and then replaces this screen.
 *
 * THE TIMELINE IS SHARED WITH brand/gen_tune_in.py, which renders the
 * sound. ARCS_MS are the instants the sound puts an arc crackle and the
 * picture spikes its glitch; LOCK_MS is where the hiss clears; OFF_MS is
 * the power-down thump. Change one, re-render the other.
 *
 * Purely cosmetic. It sits before world creation, so it costs the run
 * nothing - the clock starts after the countdown, long after this.
 */
public class TuneInScreen extends Screen {
	public static final int TOTAL_MS = 2600;
	private static final int LOCK_MS = 1200;
	private static final int OFF_MS = 2150;
	private static final int[] ARCS_MS = { 30, 340, 620, 910, 1650 };

	/**
	 * The one Lynch frame: for a blink during the fourth arc the static
	 * goes the colour of a red curtain. Short enough that a player is
	 * not sure they saw it.
	 */
	private static final int RED_FROM_MS = 910;
	private static final int RED_TO_MS = 960;

	private static final Identifier LOGO =
			new Identifier("speedrunmcalt", "textures/gui/logo_alt.png");
	private static final int LOGO_W = 288;
	private static final int LOGO_H = 186;
	/** Divides LOGO_H, so every strip is a whole number of texels. */
	private static final int STRIPS = 31;

	/**
	 * Static is drawn small and stretched with nearest sampling, which
	 * is what makes it read as a TV's grain rather than as a dither.
	 * Refilled at about 30 Hz, not every frame - at 144 fps the grain
	 * blurs into a flat grey.
	 */
	private static final int NOISE_W = 320;
	private static final int NOISE_H = 180;
	private static final int NOISE_FRAME_MS = 33;
	private static final Identifier NOISE = new Identifier("speedrunmcalt", "dynamic/tune_in_static");
	/**
	 * Made once and kept. 230 KB of texture is cheaper than getting the
	 * teardown right on every path that can replace this screen.
	 */
	private static NativeImageBackedTexture noise;

	/** Near-black with a trace of the CRT glass in it, as Ui's GLASS. */
	private static final int SCREEN = 0xFF020604;

	private final long openedAt;
	private final Random random = new Random();
	private final int[] tear = new int[STRIPS];
	private long lastSlot = -1;

	public TuneInScreen(long openedAt) {
		super(new LiteralText(""));
		this.openedAt = openedAt;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		int t = (int) Math.min(TOTAL_MS, System.currentTimeMillis() - openedAt);
		fill(matrices, 0, 0, this.width, this.height, SCREEN);

		long slot = t / NOISE_FRAME_MS;
		boolean newSlot = slot != lastSlot;
		lastSlot = slot;

		if (t >= OFF_MS) {
			collapse(matrices, t, newSlot);
			return;
		}

		float spike = spike(t);
		// 1 at the start, 0 once locked.
		float tuning = t < LOCK_MS ? 1.0f - (float) t / LOCK_MS : 0.0f;
		float disorder = Math.min(1.0f, tuning * tuning + spike);

		if (newSlot) {
			refillNoise(t >= RED_FROM_MS && t < RED_TO_MS);
			for (int i = 0; i < STRIPS; i++) {
				tear[i] = random.nextInt(2001) - 1000;
			}
		}

		// Static under the picture. Never quite gone: a weak channel.
		drawNoise(matrices, 0.06f + 0.55f * (float) Math.pow(tuning, 1.3) + 0.45f * spike);

		drawLogo(matrices, t, tuning, spike, disorder);
		rollingBar(matrices, t);
		scanlines(matrices);
	}

	/** 0..1, a sharp hit at each arc decaying over about 80 ms. */
	private static float spike(int t) {
		float s = 0.0f;
		for (int a : ARCS_MS) {
			if (t >= a) {
				s = Math.max(s, (float) Math.exp(-(t - a) / 80.0));
			}
		}
		return s;
	}

	private void drawLogo(MatrixStack matrices, int t, float tuning, float spike, float disorder) {
		int h = Math.max(24, (int) (this.height * 0.34f));
		int w = h * LOGO_W / LOGO_H;
		int x = (this.width - w) / 2;
		int top = (this.height - h) / 2;

		// Vertical roll: the picture slides down and wraps while the set
		// hunts for sync, slowing to a stop as it locks. Drawn twice, one
		// screen-height apart, so the wrap is continuous.
		float roll = tuning * tuning;
		int rollY = (int) (this.height * roll * frac(t / 380.0f));

		// Fades up out of the static, with the brightness jitter of a
		// signal that is only just there.
		float flicker = 0.88f + 0.12f * random.nextFloat();
		float alpha = Math.min(1.0f, 0.15f + t / 700.0f) * flicker * (1.0f - 0.6f * spike);

		this.client.getTextureManager().bindTexture(LOGO);
		this.client.getTextureManager().getTexture(LOGO).setFilter(true, false);
		RenderSystem.enableBlend();

		int maxTear = (int) (this.width * 0.09f * disorder);
		int split = 1 + (int) (6 * disorder);
		for (int pass = 0; pass < 2; pass++) {
			int y0 = top + rollY - pass * this.height;
			if (y0 + h < 0 || y0 > this.height) {
				continue;
			}
			// Chroma split first, additive, so the true colour sits on top.
			if (disorder > 0.02f) {
				RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
				RenderSystem.color4f(1.0f, 0.15f, 0.35f, 0.55f * disorder * alpha);
				strips(matrices, x - split, y0, w, h, t, maxTear);
				RenderSystem.color4f(0.1f, 0.6f, 1.0f, 0.55f * disorder * alpha);
				strips(matrices, x + split, y0, w, h, t, maxTear);
			}
			RenderSystem.defaultBlendFunc();
			RenderSystem.color4f(1.0f, 1.0f, 1.0f, alpha);
			strips(matrices, x, y0, w, h, t, maxTear);
		}
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		RenderSystem.disableBlend();
	}

	/**
	 * The wordmark in horizontal slices, each pushed sideways by its own
	 * random tear plus a slow sine wobble - the picture bending the way
	 * a CRT's does when horizontal hold is wrong.
	 */
	private void strips(MatrixStack matrices, int x, int top, int w, int h, int t, int maxTear) {
		int texStrip = LOGO_H / STRIPS;
		for (int i = 0; i < STRIPS; i++) {
			int y0 = top + i * h / STRIPS;
			int y1 = top + (i + 1) * h / STRIPS;
			if (y1 <= y0) {
				continue;
			}
			int dx = maxTear == 0 ? 0
					: tear[i] * maxTear / 1000 / 3
							+ (int) (maxTear * 0.5f * Math.sin(i * 0.45f + t * 0.011f));
			drawTexture(matrices, x + dx, y0, w, y1 - y0,
					0.0f, i * texStrip, LOGO_W, texStrip, LOGO_W, LOGO_H);
		}
	}

	/**
	 * The tube going out: the picture squashes into a bright line, the
	 * line pulls in to a dot, and the dot fades.
	 */
	private void collapse(MatrixStack matrices, int t, boolean newSlot) {
		float p = (t - OFF_MS) / (float) (TOTAL_MS - OFF_MS);
		int cx = this.width / 2;
		int cy = this.height / 2;
		if (p < 0.4f) {
			if (newSlot) {
				refillNoise(false);
			}
			float s = Math.max(0.01f, 1.0f - p / 0.4f);
			matrices.push();
			matrices.translate(cx, cy, 0);
			matrices.scale(1.0f + 0.15f * (1.0f - s), s, 1.0f);
			matrices.translate(-cx, -cy, 0);
			drawNoise(matrices, 0.35f);
			drawLogo(matrices, LOCK_MS, 0.0f, 0.0f, 0.0f);
			matrices.pop();
			// The squash brightens as it narrows: the same light in less
			// height.
			int a = (int) (200 * (1.0f - s));
			fill(matrices, 0, cy - 1, this.width, cy + 1, (a << 24) | 0xE8FFE4);
		} else if (p < 0.7f) {
			float q = (p - 0.4f) / 0.3f;
			int half = (int) (this.width / 2 * (1.0f - q) * (1.0f - q));
			glow(matrices, cx - half - 1, cy - 1, cx + half + 1, cy + 1, 1.0f);
		} else {
			float q = (p - 0.7f) / 0.3f;
			glow(matrices, cx - 1, cy - 1, cx + 1, cy + 1, 1.0f - q);
		}
	}

	/** A white-green core with a phosphor halo, for the line and the dot. */
	private static void glow(MatrixStack matrices, int x0, int y0, int x1, int y1, float a) {
		int halo = (int) (70 * a) << 24 | Palette.PHOSPHOR;
		int core = (int) (255 * a) << 24 | 0xF0FFEC;
		fill(matrices, x0 - 2, y0 - 2, x1 + 2, y1 + 2, halo);
		fill(matrices, x0, y0, x1, y1, core);
	}

	/** A faint bright band rolling down the glass, as on any old set. */
	private void rollingBar(MatrixStack matrices, int t) {
		int band = Math.max(8, this.height / 6);
		int y = (int) (frac(t / 1700.0f) * (this.height + band)) - band;
		fillGradient(matrices, 0, y, this.width, y + band / 2, 0x00FFFFFF, 0x0CFFFFFF);
		fillGradient(matrices, 0, y + band / 2, this.width, y + band, 0x0CFFFFFF, 0x00FFFFFF);
	}

	private void scanlines(MatrixStack matrices) {
		for (int y = 0; y < this.height; y += 2) {
			fill(matrices, 0, y, this.width, y + 1, 0x38000000);
		}
	}

	private void drawNoise(MatrixStack matrices, float alpha) {
		if (noise == null) {
			return;
		}
		this.client.getTextureManager().bindTexture(NOISE);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, Math.min(1.0f, alpha));
		drawTexture(matrices, 0, 0, this.width, this.height,
				0.0f, 0.0f, NOISE_W, NOISE_H, NOISE_W, NOISE_H);
		RenderSystem.color4f(1.0f, 1.0f, 1.0f, 1.0f);
		RenderSystem.disableBlend();
	}

	/**
	 * New grain. Grey with a green cast so it belongs to the same glass
	 * as the wordmark, plus the odd brighter row - interference catching
	 * a whole scanline.
	 *
	 * NativeImage packs ABGR, not ARGB.
	 */
	private void refillNoise(boolean red) {
		if (noise == null) {
			noise = new NativeImageBackedTexture(new NativeImage(NOISE_W, NOISE_H, false));
			this.client.getTextureManager().registerTexture(NOISE, noise);
		}
		NativeImage img = noise.getImage();
		if (img == null) {
			return;
		}
		for (int y = 0; y < NOISE_H; y++) {
			int rowBoost = random.nextInt(14) == 0 ? 60 : 0;
			for (int x = 0; x < NOISE_W; x++) {
				int v = Math.min(255, random.nextInt(200) + rowBoost);
				int r;
				int g;
				int b;
				if (red) {
					r = v;
					g = v / 10;
					b = v / 8;
				} else {
					r = v * 82 / 100;
					g = v;
					b = v * 76 / 100;
				}
				img.setPixelColor(x, y, 0xFF000000 | b << 16 | g << 8 | r);
			}
		}
		noise.upload();
	}

	private static float frac(float v) {
		return v - (float) Math.floor(v);
	}
}
