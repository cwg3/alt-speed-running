package com.speedrunmcalt.mixin;

import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the loot table a container will roll, before it is rolled.
 *
 * A village chest does not contain items until something opens it - it
 * holds a loot table id and a seed, and the contents are generated on
 * first interaction. Reading those two fields lets the seed validator
 * roll the table through Minecraft's own LootManager and count the iron
 * a runner would actually find, without opening anything and without
 * reimplementing 1.16 loot tables (which would be a correctness
 * problem, since the whole point is to match the real game exactly).
 */
@Mixin(LootableContainerBlockEntity.class)
public interface LootableContainerAccessor {
	@Accessor("lootTableId")
	Identifier speedrunmcalt$getLootTableId();

	@Accessor("lootTableSeed")
	long speedrunmcalt$getLootTableSeed();

	/**
	 * Forces the game to roll the loot for real, the same way opening
	 * the chest would. Used only to check our own rolling against the
	 * real thing - the validator itself must never call this, because
	 * consuming the loot table would change what a player finds.
	 */
	@Invoker("checkLootInteraction")
	void speedrunmcalt$checkLootInteraction(net.minecraft.entity.player.PlayerEntity player);
}
