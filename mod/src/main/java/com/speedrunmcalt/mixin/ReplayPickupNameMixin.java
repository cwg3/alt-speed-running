package com.speedrunmcalt.mixin;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reads what an item entity holds BEFORE the inventory drains it.
 *
 * insertStack mutates the stack to empty as it transfers, and
 * sendPickup - the only place that knows the pickup actually
 * succeeded - runs after that. So the name has to be taken here and
 * the event emitted there. See ReplayPickupMixin for the whole
 * argument.
 */
@Mixin(ItemEntity.class)
public abstract class ReplayPickupNameMixin {
	@Inject(method = "onPlayerCollision", at = @At("HEAD"))
	private void speedrunmcalt$rememberItem(PlayerEntity player, CallbackInfo ci) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (self.world.isClient) {
			return;
		}
		ItemStack stack = self.getStack();
		if (stack.isEmpty()) {
			return;
		}
		com.speedrunmcalt.match.PendingPickup.put(self.getEntityId(),
				stack.getItem().getTranslationKey());
	}
}
