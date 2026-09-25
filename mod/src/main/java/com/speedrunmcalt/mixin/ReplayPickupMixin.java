package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.ReplayRecorder;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the player picked up, and when.
 *
 * This is the closest a position trace can get to showing a barter or
 * a chest being emptied: the items arriving. "Three ender pearls at
 * 4:12" is the beat somebody rewinds to find.
 *
 * Only what the player actually collects - not every item entity that
 * spawns - because the question is what they GOT, not what dropped
 * near them and despawned.
 */
@Mixin(PlayerEntity.class)
public abstract class ReplayPickupMixin {
	@Inject(method = "sendPickup", at = @At("HEAD"))
	private void speedrunmcalt$recordPickup(net.minecraft.entity.Entity item, int count,
			CallbackInfo ci) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (self.world.isClient || !(item instanceof ItemEntity)) {
			return;
		}
		ItemStack stack = ((ItemEntity) item).getStack();
		if (stack.isEmpty()) {
			return;
		}
		ReplayRecorder.event("pickup",
				count + "x " + stack.getItem().getTranslationKey());
	}
}
