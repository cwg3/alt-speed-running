package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.PendingPickup;
import com.speedrunmcalt.match.ReplayRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the player picked up, and when.
 *
 * This is the closest a position trace gets to showing a barter or a
 * chest being emptied: the items arriving. "Three ender pearls at
 * 4:12" is the beat somebody rewinds to find.
 *
 * TWO HOOKS, for a reason subtle enough that the first version
 * recorded nothing while appearing to work. Vanilla's
 * ItemEntity.onPlayerCollision does this, in this order:
 *
 *     insertStack(stack)           // MUTATES stack, draining it empty
 *     player.sendPickup(this, i)   // i is the ORIGINAL count
 *
 * So at sendPickup the entity's stack is already empty and its item is
 * AIR - which is exactly why vanilla passes the count separately.
 * Reading the stack here and skipping empties skips every SUCCESSFUL
 * pickup: a complete pickup IS the empty case. It ran a whole match
 * and produced eleven kills and zero pickups on a run that crafted a
 * pickaxe and a sword.
 *
 * So the name is read at HEAD of onPlayerCollision, while the stack
 * still holds something, and the event is emitted from sendPickup,
 * which vanilla calls only when the insert succeeded. A pickup blocked
 * by a full inventory records nothing, which is right: it did not
 * happen.
 *
 * The handoff lives in com.speedrunmcalt.match.PendingPickup, NOT in
 * this package. Anything under com.speedrunmcalt.mixin.* is owned by
 * speedrunmcalt.mixins.json; mixin merges those classes into their
 * targets, so referencing one directly fails at class load. Parking
 * the handoff here as a nested class took the game down with
 * IllegalClassLoadError on the first block broken - and did it only
 * then, because nothing loads the class until an item is collected.
 * check-mixin-refs.sh now fails the build on that shape.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ReplayPickupMixin {
	@Inject(method = "sendPickup", at = @At("HEAD"))
	private void speedrunmcalt$recordPickup(Entity item, int count, CallbackInfo ci) {
		if (!(item instanceof ItemEntity)) {
			return;
		}
		String name = PendingPickup.take(item.getEntityId());
		if (name == null) {
			return;
		}
		ReplayRecorder.event("pickup", count + "x " + name);
	}
}
