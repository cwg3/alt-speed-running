package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.ReplayRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
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
 *
 * ServerPlayerEntity, not PlayerEntity. sendPickup is DECLARED on
 * LivingEntity and OVERRIDDEN here; PlayerEntity does not have it at
 * all, and mixin resolves a target against the named class alone, not
 * up the hierarchy. Targeting PlayerEntity compiled clean, built a
 * jar, uploaded to a release, and then failed at class load with
 * "could not find any targets matching 'sendPickup'" - which takes the
 * whole game down at Blocks.<clinit>, before a title screen exists to
 * show an error on. Mixin signatures are checked when the class loads,
 * so BUILD SUCCESSFUL means nothing here; only launching does.
 *
 * ServerPlayerEntity is also the honest target: the recorder is
 * server-side, and LivingEntity would fire for every zombie that walks
 * over an item.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ReplayPickupMixin {
	@Inject(method = "sendPickup", at = @At("HEAD"))
	private void speedrunmcalt$recordPickup(Entity item, int count, CallbackInfo ci) {
		if (!(item instanceof ItemEntity)) {
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
