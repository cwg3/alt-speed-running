package com.speedrunmcalt.mixin;

import com.speedrunmcalt.match.ReplayRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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
 * TWO HOOKS, and the reason is subtle enough that the first version
 * recorded nothing at all while appearing to work.
 *
 * Vanilla ItemEntity.onPlayerCollision does this, in this order:
 *
 *     insertStack(stack)      // MUTATES stack, draining it to empty
 *     player.sendPickup(this, i)   // i is the ORIGINAL count
 *
 * So at sendPickup the entity's stack is already empty and its item is
 * AIR - which is why vanilla passes the count as a separate argument.
 * Reading the stack there and skipping empties, as the first version
 * did, skips every SUCCESSFUL pickup: a complete pickup is the empty
 * case. It ran for a whole match and produced eleven kills and zero
 * pickups on a run that crafted a pickaxe and a sword.
 *
 * So the item is read at HEAD of onPlayerCollision, while the stack
 * still has something in it, and the event is only emitted from
 * sendPickup - which vanilla calls only when the insert succeeded.
 * A pickup blocked by a full inventory records nothing, which is
 * right: it did not happen.
 *
 * The handoff is a plain static because both halves run on the server
 * thread inside one synchronous call, and it is keyed by entity id so
 * a stale value can never be attributed to the wrong item.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ReplayPickupMixin {
	@Inject(method = "sendPickup", at = @At("HEAD"))
	private void speedrunmcalt$recordPickup(Entity item, int count, CallbackInfo ci) {
		if (!(item instanceof ItemEntity)) {
			return;
		}
		String name = PickupNames.take(item.getEntityId());
		if (name == null) {
			return;
		}
		ReplayRecorder.event("pickup", count + "x " + name);
	}

	/**
	 * Remembers what an item entity held before the inventory drained
	 * it. Not an inner class of the mixin: mixin classes are merged
	 * into the target and are not a place to keep state.
	 */
	static final class PickupNames {
		private static int pendingId = -1;
		private static String pendingName = null;

		private PickupNames() {
		}

		static void put(int entityId, String name) {
			pendingId = entityId;
			pendingName = name;
		}

		/** Reads once, and only for the entity it was stored against. */
		static String take(int entityId) {
			if (entityId != pendingId) {
				return null;
			}
			String name = pendingName;
			pendingId = -1;
			pendingName = null;
			return name;
		}
	}
}
