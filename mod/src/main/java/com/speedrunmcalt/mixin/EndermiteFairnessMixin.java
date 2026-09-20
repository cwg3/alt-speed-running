package com.speedrunmcalt.mixin;

import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * Vanilla: this.random.nextFloat() < 0.05f on pearl landing spawns an
 * endermite. Standardized to never spawn - a 5% chance of a hostile
 * mob appearing mid-run is pure variance between two players who
 * otherwise made identical decisions, with no skill component.
 *
 * Redirects the only nextFloat call in onCollision (the particle
 * effects there use nextDouble/nextGaussian) to return 1.0f, which
 * fails the < 0.05f check every time.
 */
@Mixin(EnderPearlEntity.class)
public class EndermiteFairnessMixin {
	@Redirect(
			method = "onCollision",
			at = @At(value = "INVOKE", target = "Ljava/util/Random;nextFloat()F"))
	private float speedrunmcalt$neverSpawnEndermite(Random random) {
		return 1.0f;
	}
}
