package com.speedrunmcalt.mixin;

import net.minecraft.entity.passive.SheepEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * Vanilla: int i = 1 + this.random.nextInt(3) - shearing a sheep gives
 * 1-3 wool. Standardized to always 3, so the number of sheep a player
 * must find for a bed is fixed rather than a 1-3x swing. Directly
 * speedrun-relevant: beds are the dragon-fight strategy in 1.16 RSG.
 *
 * Redirects the single nextInt call in sheared() specifically (the
 * other random calls in that method are cosmetic drop velocity) to
 * return 2, giving 1 + 2 = 3.
 */
@Mixin(SheepEntity.class)
public class SheepShearFairnessMixin {
	@Redirect(
			method = "sheared",
			at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I"))
	private int speedrunmcalt$alwaysThreeWool(Random random, int bound) {
		return 2;
	}
}
