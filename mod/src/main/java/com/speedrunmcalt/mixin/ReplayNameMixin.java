package com.speedrunmcalt.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides the name tags over replay bodies when the viewer turns names
 * off (replay key 8, ReplayPlayback.toggleNames).
 *
 * Ghosts only. Anything else that has a label keeps it - there is
 * nothing else in a replay world that should, but this is not the
 * place to decide that.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ReplayNameMixin {
	@Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
	private void speedrunmcalt$hideGhostNames(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!com.speedrunmcalt.replay.ReplayPlayback.namesVisible()
				&& com.speedrunmcalt.replay.ReplayPlayback.ghostUuid(entity) != null) {
			cir.setReturnValue(false);
		}
	}
}
