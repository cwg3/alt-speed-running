package com.speedrunmcalt.mixin;

import net.minecraft.client.options.DoubleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the brightness slider go past vanilla's 100% cap.
 *
 * Speedrun rules permit gamma up to 5.0 - 500% - and effectively every
 * competitive player runs it, because dark ravines and nether
 * structures are otherwise unreadable without spending time on
 * torches. Approved performance mods provide the same slider
 * extension.
 *
 * Editing options.txt by hand does not stick: Minecraft clamps the
 * value back to 1.0 when it saves, so the change is silently lost on
 * the next exit. Raising the option's own maximum is what makes it
 * persist, and it leaves the setting where players expect to find it
 * rather than inventing a separate control.
 *
 * Nothing here alters lighting or shadow rendering. Fullbright packs
 * and anything pushing past 5.0 remain disallowed - this only unlocks
 * the range the rules already permit.
 */
@Mixin(DoubleOption.class)
public interface GammaUnlockMixin {
	@org.spongepowered.asm.mixin.Mutable
	@Accessor("max")
	void speedrunmcalt$setMax(double max);

	@Accessor("max")
	double speedrunmcalt$getMax();
}
