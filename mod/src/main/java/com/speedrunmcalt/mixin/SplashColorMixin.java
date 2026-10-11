package com.speedrunmcalt.mixin;

import net.minecraft.client.gui.screen.SplashScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The Mojang Studios loading screen on a near-black purple instead of red.
 *
 * Vanilla builds its red (239, 50, 61) once in the static initialiser
 * into two fields - field_25041 opaque, field_25042 the same colour
 * with the alpha masked off, which render ORs a fade alpha onto while
 * the overlay fades in and out. Redirecting the reads in render swaps
 * the colour at every use and leaves the fade arithmetic, the logo and
 * the progress bar as vanilla draws them. The logo is drawn additively
 * in white (SRC_ALPHA, ONE), so it reads the same on purple.
 *
 * Gothic rather than bright: royal purple (0x7B2FF7) was tried first,
 * then near-black 0x1F0A33; this is the midpoint of the two - the same hue
 * family, dark enough to read gothic, so the white logo is the only bright thing
 * on screen.
 */
@Mixin(SplashScreen.class)
public class SplashColorMixin {
	private static final int GOTHIC_PURPLE = 0x4D1C95;

	@Redirect(method = "render", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/screen/SplashScreen;field_25041:I"))
	private int speedrunmcalt$opaqueBackground() {
		return 0xFF000000 | GOTHIC_PURPLE;
	}

	@Redirect(method = "render", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/gui/screen/SplashScreen;field_25042:I"))
	private int speedrunmcalt$fadingBackground() {
		return GOTHIC_PURPLE;
	}
}
