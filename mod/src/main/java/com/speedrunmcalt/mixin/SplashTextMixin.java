package com.speedrunmcalt.mixin;

import java.util.List;
import java.util.Random;
import net.minecraft.client.resource.SplashTextResourceSupplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A few alt splash lines mixed into vanilla's pool.
 *
 * One title screen in ALT_ODDS gets one of these instead of vanilla's
 * pick - often enough to be found, rare enough that the C418-era
 * splashes still dominate. Only an ordinary pick is replaced: the date
 * specials (Christmas, New Year, Halloween) and the "<name> IS YOU"
 * line are not in splashTexts, so they are left alone.
 */
@Mixin(SplashTextResourceSupplier.class)
public class SplashTextMixin {
	private static final int ALT_ODDS = 8;
	private static final Random ALT_RANDOM = new Random();
	private static final String[] ALT_SPLASHES = {
		"Also try vanilla!",
		"Same seed, same odds!",
		"Tune in!",
		"Sub-10 or bust!",
		"One more seed!",
		"Blind travel is a lifestyle!",
		"Eye spy a stronghold!",
		"Bastion? Bastion!",
		"Perch, please!",
		"Don't forget the beds!",
		"Speed-running, alternatively!",
		"Now with ice cream!",
	};

	@Shadow
	@Final
	private List<String> splashTexts;

	@Inject(method = "get", at = @At("RETURN"), cancellable = true)
	private void speedrunmcalt$altSplash(CallbackInfoReturnable<String> cir) {
		String vanilla = cir.getReturnValue();
		if (vanilla != null && this.splashTexts.contains(vanilla)
				&& ALT_RANDOM.nextInt(ALT_ODDS) == 0) {
			cir.setReturnValue(ALT_SPLASHES[ALT_RANDOM.nextInt(ALT_SPLASHES.length)]);
		}
	}
}
