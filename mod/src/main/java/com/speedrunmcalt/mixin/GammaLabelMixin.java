package com.speedrunmcalt.mixin;

import net.minecraft.client.options.DoubleOption;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.Option;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the brightness slider say what the brightness actually is.
 *
 * Vanilla's label branches on the RATIO, not the value:
 *
 *     getRatio(gamma) = (gamma - min) / (max - min)
 *
 * and calls ratio 1.0 "Bright". With the ceiling raised to 5.0 that
 * inverts the whole scale: true 500% reads "Bright", while vanilla's
 * old maximum of 1.0 reads "+20%". A runner checking whether their
 * brightness is set sees the least informative label at exactly the
 * setting they care about.
 *
 * So the label reports the gamma itself. 500% means gamma 5.0, which
 * is the number the rules are written in and the number every guide
 * quotes.
 *
 * GAMMA only. Every other DoubleOption keeps vanilla's wording, since
 * for those the ratio and the value agree.
 */
@Mixin(DoubleOption.class)
public abstract class GammaLabelMixin {
	@Inject(method = "getDisplayString", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$gammaLabel(GameOptions options,
			CallbackInfoReturnable<Text> cir) {
		if ((Object) this != Option.GAMMA) {
			return;
		}
		double gamma = ((DoubleOption) (Object) this).get(options);
		cir.setReturnValue(new LiteralText(
				"Brightness: +" + (int) Math.round(gamma * 100.0) + "%"));
	}
}
