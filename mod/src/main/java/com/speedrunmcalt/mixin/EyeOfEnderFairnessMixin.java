package com.speedrunmcalt.mixin;

import net.minecraft.entity.EyeOfEnderEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla: this.dropsItem = this.random.nextInt(5) > 0 - a 20% chance
 * to permanently lose an eye of ender on landing, confirmed by reading
 * the real decompiled EyeOfEnderEntity source. Standardized to always
 * survive: a thrown eye is only useful for locating the stronghold, so
 * losing one to bad luck is pure frustration with no gameplay depth,
 * same reasoning as the gravel/flint standardization (remove the
 * variance entirely rather than just adjust the odds).
 */
@Mixin(EyeOfEnderEntity.class)
public class EyeOfEnderFairnessMixin {
	@Shadow
	private boolean dropsItem;

	@Inject(method = "moveTowards", at = @At("TAIL"))
	private void neverBreak(BlockPos pos, CallbackInfo ci) {
		this.dropsItem = true;
	}
}
