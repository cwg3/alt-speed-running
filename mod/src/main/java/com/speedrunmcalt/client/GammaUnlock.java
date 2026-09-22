package com.speedrunmcalt.client;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.GammaUnlockMixin;
import net.minecraft.client.options.Option;

/**
 * Raises the brightness option's ceiling to the 5.0 the rules allow.
 *
 * Applied once at client start. The slider then runs to 500% and the
 * value survives a save, which a hand-edited options.txt does not -
 * Minecraft clamps that back to 1.0 on exit, so the change silently
 * disappears and the player is left wondering why it did not work.
 */
public final class GammaUnlock {
	/** The maximum speedrun rules permit. */
	private static final double MAX_GAMMA = 5.0;

	private GammaUnlock() {
	}

	public static void apply() {
		try {
			GammaUnlockMixin gamma = (GammaUnlockMixin) (Object) Option.GAMMA;
			if (gamma.speedrunmcalt$getMax() < MAX_GAMMA) {
				gamma.speedrunmcalt$setMax(MAX_GAMMA);
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Brightness unlocked to {}%",
						(int) (MAX_GAMMA * 100));
			}
		} catch (Throwable t) {
			// Cosmetic: a failure here must never stop the client
			// starting, it just means the slider stays at vanilla's cap.
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Could not unlock brightness", t);
		}
	}
}
