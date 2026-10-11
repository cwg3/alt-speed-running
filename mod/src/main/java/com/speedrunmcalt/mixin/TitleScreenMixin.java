package com.speedrunmcalt.mixin;

import com.speedrunmcalt.menu.AltMenuScreen;
import com.speedrunmcalt.menu.GlintButton;
import com.speedrunmcalt.menu.Palette;
import com.speedrunmcalt.menu.PowerOff;
import com.speedrunmcalt.menu.PowerOn;
import com.speedrunmcalt.menu.Ui;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the entry point to the title screen.
 *
 * The mixin extends Screen so the inherited addButton/width/height are
 * available; fabric-screen-api-v1 would normally do this more cleanly
 * but it isn't part of the Fabric API version this mod pins for 1.16.1.
 *
 * Sits in the gap between the logo and Singleplayer, which is the only
 * free space in the layout. Vanilla runs from j = height/4 + 48
 * (Singleplayer) down to the options/quit row at j + 84; below that row
 * the window runs out before a button fits, and it ends up clipped
 * against the version text.
 *
 * It wears the enchantment glint (GlintButton) so the one row that is
 * not Mojang's catches the eye the way enchanted gear does.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	/** The blaze-rod ">": gap from the buttons, and how far its point sticks out. */
	private static final int BRACKET_GAP = 6;
	private static final int BRACKET_DEPTH = 14;
	/**
	 * "Vanilla!"'s centre, out from the bracket's point and raised so
	 * the tilted word's low left end clears the point.
	 */
	private static final int LABEL_FROM_TIP = 32;
	private static final int LABEL_RISE = 6;
	/** About the size a typical splash comes out at. */
	private static final float LABEL_SCALE = 1.45F;

	/** "Share with your friends!": size, and gap from the alt button. */
	private static final float SHARE_SCALE = 1.2F;
	private static final int SHARE_GAP = 8;
	private static final net.minecraft.util.Identifier CAKE =
			new net.minecraft.util.Identifier("minecraft", "textures/item/cake.png");
	/** Which side of the alt button it sits on. */
	private static final boolean SHARE_ON_RIGHT = true;

	/** 70% black behind the buttons. */
	private static final int TITLE_MID_ALPHA = 0xB3;

	protected TitleScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void speedrunmcalt$addAltButton(CallbackInfo ci) {
		speedrunmcalt$bites = 0;
		speedrunmcalt$burpAt = -1;
		int singleplayerTop = this.height / 4 + 48;
		this.addButton(new GlintButton(
				this.width / 2 - 100, singleplayerTop - 24, 200, 20,
				// The wordmark's own colours, on the one button that is
				// ours. Plain white it was indistinguishable from
				// Singleplayer and Multiplayer, which is the wrong
				// thing for the only row on this screen that is not
				// Mojang's.
				//
				// A Text with styled siblings works because TextRenderer
				// takes the colour passed by ButtonWidget only as a
				// DEFAULT - any run carrying its own style wins.
				speedrunmcalt$label(),
				button -> this.client.openScreen(new AltMenuScreen(this))));
	}

	/**
	 * "alt" in phosphor, then "speed-running" lettered exactly as the
	 * lobby letters it (Palette.wordmarkColors).
	 */
	private static Text speedrunmcalt$label() {
		LiteralText label = new LiteralText("alt  ");
		label.styled(st -> st.withColor(TextColor.fromRgb(Palette.PHOSPHOR)));
		int[] colors = Palette.wordmarkColors();
		for (int i = 0; i < Palette.WORDMARK.length; i++) {
			int rgb = colors[i];
			label.append(new LiteralText(Palette.WORDMARK[i])
					.styled(st -> st.withColor(TextColor.fromRgb(rgb))));
		}
		return label;
	}

	/**
	 * Dims the panorama the way the menus dimmed it before they had
	 * wallpapers (Ui.vignette), so the logo and buttons sit on a darker
	 * field instead of the raw nether.
	 *
	 * Hooked right after vanilla draws PANORAMA_OVERLAY - the only
	 * drawTexture call with the UV/region signature in render - which
	 * is after the panorama and before the logo, splash and buttons.
	 * Blend goes back on afterwards because the logo's fade-in relies
	 * on the state the overlay left behind.
	 *
	 * Darker through the middle than the menus' 0x70: here the band
	 * behind the buttons is open nether, and at 0x70 it competed with
	 * the glint on the alt button.
	 */
	@Inject(method = "render",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/gui/screen/TitleScreen;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIFFIIII)V",
					ordinal = 0, shift = Shift.AFTER))
	private void speedrunmcalt$dimPanorama(MatrixStack matrices, int mouseX, int mouseY, float delta,
			CallbackInfo ci) {
		Ui.vignette(matrices, this.width, this.height, TITLE_MID_ALPHA);
		RenderSystem.enableBlend();
	}

	/**
	 * The set switching on, once per launch, and off when Quit Game is
	 * pressed, over everything the title screen has drawn (PowerOn,
	 * PowerOff).
	 */
	@Inject(method = "render", at = @At("TAIL"))
	private void speedrunmcalt$powerOn(MatrixStack matrices, int mouseX, int mouseY, float delta,
			CallbackInfo ci) {
		speedrunmcalt$vanillaLabel(matrices);
		speedrunmcalt$shareLabel(matrices);
		speedrunmcalt$disclaimer(matrices);
		speedrunmcalt$burpWhenDue();
		PowerOn.render(matrices, this.client, this.width, this.height);
		PowerOff.render(matrices, this.client, this.width, this.height);
	}

	/**
	 * "Share with your friends!" off one end of the alt button, in
	 * the same splash motion as "Vanilla!" across the screen - tilted
	 * -20 degrees, pulsing once a second - with its near end pointing
	 * at the button (SHARE_ON_RIGHT picks the side). Lettered in the
	 * wordmark's fade (Palette.drawFaded) where "Vanilla!" is plain
	 * white: this one is alt talking.
	 */
	private void speedrunmcalt$shareLabel(MatrixStack matrices) {
		String text = "Share with your friends!";
		int altTop = this.height / 4 + 48 - 24;
		float pulse = 1.8F - net.minecraft.util.math.MathHelper.abs(
				net.minecraft.util.math.MathHelper.sin(
						(float) (net.minecraft.util.Util.getMeasuringTimeMs() % 1000L) / 1000.0F
								* 6.2831855F) * 0.1F);
		double tilt = Math.toRadians(20.0);
		// Shrink to fit beside the button. At 1920x1080 on auto GUI scale
		// the screen is only 480 wide, and at full size the line ran off
		// the edge with the cake gone entirely - unseen on an ultrawide,
		// where there is room to spare. Measured on the whole line, cake
		// included, flattened by the tilt.
		int textWidth = this.textRenderer.getWidth(text);
		float room = this.width / 2.0F - 100 - SHARE_GAP - 4;
		float span = (textWidth + 6 + 10) * SHARE_SCALE * (float) Math.cos(tilt);
		float size = SHARE_SCALE * Math.min(1.0F, room / span);
		float scale = pulse * size / 1.8F;
		float half = textWidth * size / 2.0F;
		// Anchor the centre so the near end lands just off the button's
		// middle. The tilt lifts the right end and drops the left: on the
		// left side the centre sits below-left of the right end, on the
		// right side above-right of the left end.
		float endY = altTop + 10;
		float cx;
		float cy;
		if (SHARE_ON_RIGHT) {
			cx = this.width / 2 + 100 + SHARE_GAP + (float) (half * Math.cos(tilt));
			cy = endY - (float) (half * Math.sin(tilt));
		} else {
			cx = this.width / 2 - 100 - SHARE_GAP - (float) (half * Math.cos(tilt));
			cy = endY + (float) (half * Math.sin(tilt));
		}
		matrices.push();
		matrices.translate(cx, cy, 0.0);
		matrices.multiply(net.minecraft.client.util.math.Vector3f.POSITIVE_Z.getDegreesQuaternion(-20.0F));
		matrices.scale(scale, scale, 1.0F);
		Palette.drawFaded(matrices, this.textRenderer, text, -this.textRenderer.getWidth(text) / 2, -4);
		// Vanilla's cake item after it, at the cone's size - the cone's
		// mirror on the other line, and the thing you share.
		this.client.getTextureManager().bindTexture(CAKE);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		drawTexture(matrices, this.textRenderer.getWidth(text) / 2 + 6, -7, 10, 10,
				0.0F, 0.0F, 16, 16, 16, 16);
		RenderSystem.disableBlend();
		matrices.pop();
	}

	/**
	 * Mojang's brand guidelines ask a mod to say it is not theirs, in
	 * these words. Half-size fine print, right-aligned just above
	 * vanilla's copyright notice and grey, so the notice itself - left
	 * exactly as vanilla draws it - stays the line that reads at the
	 * bottom. Half scale is the smallest the font stays crisp at: one
	 * screen pixel per font pixel at GUI scale 2.
	 */
	private void speedrunmcalt$disclaimer(MatrixStack matrices) {
		String text = "Not an official Minecraft product. Not approved by or associated with Mojang.";
		float scale = 0.5F;
		matrices.push();
		matrices.translate(this.width - 2 - this.textRenderer.getWidth(text) * scale,
				this.height - 10 - 9 * scale, 0.0);
		matrices.scale(scale, scale, 1.0F);
		this.textRenderer.draw(matrices, text, 0.0F, 0.0F, 0xFF8A8A8A);
		matrices.pop();
	}

	/**
	 * Quit Game switches the set off first (PowerOff), which stops the
	 * game when it is done. method_19862 is the Quit button's lambda -
	 * its whole body is this one scheduleStop call.
	 */
	@Redirect(method = "method_19862",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;scheduleStop()V"))
	private void speedrunmcalt$powerOff(MinecraftClient client) {
		PowerOff.start();
	}

	/**
	 * Clicking the cone takes a bite: the cherry and the top of the
	 * scoop, the rest of the scoop, the rim, then the cone itself - and
	 * a burp. It grows back the next time the title screen opens.
	 *
	 * The click is carried back into the label's own tilted, scaled
	 * space (the inverse of what vanillaLabel applies) and tested
	 * against the cone's cells there, a couple of pixels of slack each
	 * side. Clicks are swallowed entirely while the set is switching
	 * off.
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$biteCone(double mouseX, double mouseY, int button,
			CallbackInfoReturnable<Boolean> cir) {
		if (PowerOff.active()) {
			cir.setReturnValue(true);
			return;
		}
		if (button != 0 || speedrunmcalt$bites >= BITES.length || speedrunmcalt$labelScale <= 0) {
			return;
		}
		double dx = mouseX - speedrunmcalt$labelX;
		double dy = mouseY - speedrunmcalt$labelY;
		double cos = Math.cos(Math.toRadians(20.0));
		double sin = Math.sin(Math.toRadians(20.0));
		double lx = (dx * cos - dy * sin) / speedrunmcalt$labelScale;
		double ly = (dx * sin + dy * cos) / speedrunmcalt$labelScale;
		int cx = speedrunmcalt$coneX;
		if (lx < cx - 2 || lx > cx + 9 || ly < CONE_Y - 2 || ly > CONE_Y + CONE.length + 2) {
			return;
		}
		speedrunmcalt$bites++;
		this.client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_GENERIC_EAT,
				0.8F + (float) Math.random() * 0.4F));
		if (speedrunmcalt$bites == BITES.length) {
			speedrunmcalt$burpAt = net.minecraft.util.Util.getMeasuringTimeMs() + 500L;
		}
		cir.setReturnValue(true);
	}

	private void speedrunmcalt$burpWhenDue() {
		if (speedrunmcalt$burpAt >= 0 && net.minecraft.util.Util.getMeasuringTimeMs() >= speedrunmcalt$burpAt) {
			speedrunmcalt$burpAt = -1;
			this.client.getSoundManager().play(
					PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_BURP, 1.0F));
		}
	}

	/**
	 * A tall ">" of two blaze rods closing around Singleplayer,
	 * Multiplayer and Realms, its point aimed at "Vanilla!" and a
	 * vanilla cone - the one row above is ours, the three it brackets
	 * are Mojang's. Blaze rods because every run goes through them.
	 *
	 * The label borrows the splash text's motion, copied from
	 * TitleScreen.render: rotated -20 degrees, pulsing by 0.1 once a
	 * second. White rather than splash yellow - vanilla is white.
	 * Speaking the game's own visual language is what makes it
	 * read as a label about the vanilla game rather than as more alt
	 * branding.
	 *
	 * Drawn before PowerOn so the power-on covers it like everything
	 * else.
	 */
	private void speedrunmcalt$vanillaLabel(MatrixStack matrices) {
		int singleplayerTop = this.height / 4 + 48;
		// Singleplayer's top to Realms' bottom: three 20px rows, 24 apart.
		int top = singleplayerTop;
		int bottom = singleplayerTop + 24 * 2 + 20;
		int mid = (top + bottom) / 2;
		int x0 = this.width / 2 + 100 + BRACKET_GAP;
		int tip = x0 + BRACKET_DEPTH;
		speedrunmcalt$rod(matrices, x0, top, tip, mid);
		speedrunmcalt$rod(matrices, x0, bottom, tip, mid);

		String text = "Vanilla!";
		float pulse = 1.8F - net.minecraft.util.math.MathHelper.abs(
				net.minecraft.util.math.MathHelper.sin(
						(float) (net.minecraft.util.Util.getMeasuringTimeMs() % 1000L) / 1000.0F
								* 6.2831855F) * 0.1F);
		// Vanilla's shrink, 100 / (width + 32), assumes a long splash -
		// on one short word it scales UP, to half again the size of the
		// splash beside the logo. Held to that typical size instead.
		pulse = pulse * LABEL_SCALE / 1.8F;
		speedrunmcalt$labelX = tip + LABEL_FROM_TIP;
		speedrunmcalt$labelY = mid - LABEL_RISE;
		speedrunmcalt$labelScale = pulse;
		speedrunmcalt$coneX = this.textRenderer.getWidth(text) / 2 + 3;
		matrices.push();
		matrices.translate(speedrunmcalt$labelX, speedrunmcalt$labelY, 0.0);
		matrices.multiply(net.minecraft.client.util.math.Vector3f.POSITIVE_Z.getDegreesQuaternion(-20.0F));
		matrices.scale(pulse, pulse, pulse);
		drawCenteredString(matrices, this.textRenderer, text, 0, -4, 0xFFFFFFFF);
		speedrunmcalt$cone(matrices, speedrunmcalt$coneX, CONE_Y);
		matrices.pop();
	}

	/**
	 * A 1960s vanilla cone, cherry on top, one font pixel per cell -
	 * drawn in the label's own space so it tilts and pulses with the
	 * word. Shadowed a pixel down-right at a quarter brightness, as the
	 * font shadows its glyphs.
	 */
	private static final String[] CONE = {
		"...r...",
		"..www..",
		".wwwww.",
		"wwwwwww",
		"wwwwwws",
		".sssss.",
		".tdtdt.",
		"..dtd..",
		"..tdt..",
		"...d...",
	};

	/** Where the cone sits in label space, above the text's baseline. */
	private static final int CONE_Y = -6;

	/** Rows gone after each bite: cherry and top, scoop, rim, cone. */
	private static final int[] BITES = { 2, 4, 6, 10 };

	private int speedrunmcalt$bites;
	private long speedrunmcalt$burpAt = -1;
	/** The label's transform as last drawn, for the cone's hit test. */
	private int speedrunmcalt$labelX;
	private int speedrunmcalt$labelY;
	private float speedrunmcalt$labelScale;
	private int speedrunmcalt$coneX;

	private void speedrunmcalt$cone(MatrixStack matrices, int x, int y) {
		int eaten = speedrunmcalt$bites == 0 ? 0 : BITES[speedrunmcalt$bites - 1];
		for (int pass = 0; pass < 2; pass++) {
			for (int row = eaten; row < CONE.length; row++) {
				for (int col = 0; col < CONE[row].length(); col++) {
					int rgb;
					switch (CONE[row].charAt(col)) {
						case 'r': rgb = 0xE0303A; break; // cherry
						case 'w': rgb = 0xFFF8E7; break; // vanilla
						case 's': rgb = 0xE6D8B8; break; // scoop's shaded rim
						case 't': rgb = 0xE0A860; break; // waffle
						case 'd': rgb = 0xB07838; break; // waffle, dark cross
						default: continue;
					}
					if (pass == 0) {
						// The font's shadow: each channel divided by four.
						rgb = (rgb & 0xFCFCFC) >> 2;
					}
					int px = x + col + (pass == 0 ? 1 : 0);
					int py = y + row + (pass == 0 ? 1 : 0);
					fill(matrices, px, py, px + 1, py + 1, 0xFF000000 | rgb);
				}
			}
		}
	}

	private static final net.minecraft.util.Identifier BLAZE_ROD =
			new net.minecraft.util.Identifier("minecraft", "textures/item/blaze_rod.png");
	/**
	 * The rod in blaze_rod.png runs corner to corner, (1.5, 14.5) to
	 * (14.5, 1.5) - centred on the texture, 13 * sqrt 2 texels long,
	 * pointing up-right at -45 degrees.
	 */
	private static final float ROD_TEXELS = 13.0F * (float) Math.sqrt(2.0);
	private static final float ROD_ANGLE = (float) Math.atan2(-1.0, 1.0);
	/**
	 * Each rod covers this much of its arm, centred - shorter than the
	 * arm so the two stand apart at the point and the ends.
	 */
	private static final float ROD_LENGTH = 0.36F;
	/** The glow's layers, as scales of the rod, and their strength. */
	private static final float[] ROD_GLOW = { 1.55F, 1.35F, 1.18F };
	private static final float ROD_GLOW_ALPHA = 0.55F;

	/**
	 * Vanilla's blaze rod texture laid along (x0, y0) to (x1, y1):
	 * centred on the segment, turned from its own diagonal onto the
	 * segment's angle, and scaled to ROD_LENGTH of it, with a glow.
	 */
	private void speedrunmcalt$rod(MatrixStack matrices, int x0, int y0, int x1, int y1) {
		float dx = x1 - x0;
		float dy = y1 - y0;
		float scale = (float) Math.sqrt(dx * dx + dy * dy) * ROD_LENGTH / ROD_TEXELS;
		matrices.push();
		matrices.translate((x0 + x1) / 2.0F, (y0 + y1) / 2.0F, 0.0);
		matrices.multiply(net.minecraft.client.util.math.Vector3f.POSITIVE_Z.getRadialQuaternion(
				(float) Math.atan2(dy, dx) - ROD_ANGLE));
		matrices.scale(scale, scale, 1.0F);
		this.client.getTextureManager().bindTexture(BLAZE_ROD);
		RenderSystem.enableBlend();
		// The glow: the rod itself drawn again, larger and additively in
		// blaze orange, so the halo follows its shape. A slow flicker,
		// out of step between the two rods, keeps it alive.
		float flicker = 0.85F + 0.15F * (float) Math.sin(
				net.minecraft.util.Util.getMeasuringTimeMs() / 140.0 + y0 * 0.37);
		RenderSystem.blendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE);
		for (float grow : ROD_GLOW) {
			matrices.push();
			matrices.scale(grow, grow, 1.0F);
			RenderSystem.color4f(1.0F, 0.62F, 0.18F, ROD_GLOW_ALPHA * flicker);
			drawTexture(matrices, -8, -8, 0.0F, 0.0F, 16, 16, 16, 16);
			matrices.pop();
		}
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		drawTexture(matrices, -8, -8, 0.0F, 0.0F, 16, 16, 16, 16);
		// A second additive pass over the rod itself brightens its core.
		RenderSystem.blendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE);
		RenderSystem.color4f(1.0F, 0.85F, 0.5F, 0.35F * flicker);
		drawTexture(matrices, -8, -8, 0.0F, 0.0F, 16, 16, 16, 16);
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableBlend();
		matrices.pop();
	}
}
