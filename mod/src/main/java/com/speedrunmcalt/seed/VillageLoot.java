package com.speedrunmcalt.seed;

import com.speedrunmcalt.mixin.LootableContainerAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import java.util.List;

/**
 * Counts the iron a village actually yields on a given seed.
 *
 * Structure prediction alone cannot answer this. cubiomes tells us a
 * village is near spawn, but since 1.14 villages are assembled by
 * jigsaw, so whether one contains a smith - and what is in its chest -
 * is not something cubiomes models at all. A village that passes the
 * distance filter is routinely a village with no iron in it, which is a
 * start a runner would reset.
 *
 * Rather than reimplementing 1.16 loot tables, this reads the loot
 * table id and seed off each container and rolls them through the
 * server's own LootManager. That makes the count exact by
 * construction: it is the same code path the game runs when a player
 * opens it.
 *
 * Two traps here, both of which silently reported zero iron for
 * villages that demonstrably contained weaponsmiths:
 *
 * 1. Containers, not chests. Taiga and snowy villages store their loot
 *    in BARRELS, a different block entity. Matching on
 *    LootableContainerBlockEntity covers chests and barrels alike.
 *
 * 2. A freshly generated chunk keeps its block entities as packed NBT
 *    until something asks for them, so getBlockEntityPositions() lists
 *    only the ones already instantiated - which for a newly generated
 *    village is close to none. Scanning block STATES for container
 *    blocks and then fetching each one forces instantiation, which is
 *    what actually makes the loot visible.
 */
public final class VillageLoot {
	/**
	 * Chunks of slack added around the village's bounding box.
	 *
	 * The box comes from the jigsaw, so it is exact; the margin only
	 * covers a chest sitting right on the boundary.
	 */
	private static final int CHUNK_MARGIN = 1;

	/**
	 * Only village chests count. The sweep radius also catches whatever
	 * else generated nearby - dungeon chests underneath the village turn
	 * up routinely - and counting those would credit a seed with iron a
	 * runner would have to go mining for.
	 */
	private static final String VILLAGE_TABLE_PREFIX = "minecraft:chests/village/";

	private VillageLoot() {
	}

	public static final class Result {
		public final int ironIngots;
		public final boolean hasIronPickaxe;
		public final boolean hasIronArmor;
		public final int chestsFound;
		/**
		 * Smith chests the sweep actually reached. Distinguishes "the
		 * smith's chest rolled no iron" from "the sweep never found the
		 * smith", which look identical in the iron count alone.
		 */
		public final int smithChests;
		/**
		 * Diamonds in the village's chests.
		 *
		 * The standard accepts 4 iron AND 3 diamonds in place of the
		 * 7-iron threshold - diamonds skip straight to the tools the
		 * iron was going to be spent on. Counting them is what lets
		 * that branch be checked at all.
		 */
		public final int diamonds;

		Result(int ironIngots, boolean hasIronPickaxe, boolean hasIronArmor,
				int chestsFound, int smithChests, int diamonds) {
			this.ironIngots = ironIngots;
			this.hasIronPickaxe = hasIronPickaxe;
			this.hasIronArmor = hasIronArmor;
			this.chestsFound = chestsFound;
			this.smithChests = smithChests;
			this.diamonds = diamonds;
		}

		@Override
		public String toString() {
			return "iron=" + ironIngots
					+ " pick=" + hasIronPickaxe
					+ " armor=" + hasIronArmor
					+ " chests=" + chestsFound
					+ " smithChests=" + smithChests
					+ " diamonds=" + diamonds;
		}
	}

	/**
	 * Totals the iron in a village, sweeping the box the jigsaw
	 * actually produced rather than a guessed radius around the
	 * predicted centre.
	 */
	public static Result scan(ServerWorld world, BlockBox village) {
		int ironIngots = 0;
		boolean pickaxe = false;
		boolean armor = false;
		int chests = 0;
		int smithChests = 0;
		int diamonds = 0;

		// Container discovery is delegated rather than done here, and the
		// reason is the whole point of this fix.
		//
		// This used to walk block states over the PREDICTED box's Y
		// range. That range is not reliable: a structure's X and Z are
		// exact but its Y shifts to fit terrain, by as much as fifty
		// blocks. So the scan looked in the wrong vertical slice and
		// reported "no smith chest" for villages that have one - a
		// verification tool returning a confident false negative, which
		// is worse than no tool at all.
		//
		// ContainerScan ignores Y and reads the chunk's own block entity
		// maps. It is also enormously cheaper: the block-state walk was
		// the approach that took roughly 9.4 million reads for a village
		// and killed the launcher's Rosetta-translated JVM outright.
		for (BlockPos pos : ContainerScan.find(world, village)) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof LootableContainerBlockEntity)) {
				continue;
			}
			LootableContainerAccessor accessor = (LootableContainerAccessor) entity;
			Identifier table = accessor.speedrunmcalt$getLootTableId();
			if (table == null || !table.toString().startsWith(VILLAGE_TABLE_PREFIX)) {
				continue;
			}
			chests++;
			String tableName = table.toString();
			if (tableName.contains("weaponsmith")
					|| tableName.contains("toolsmith")
					|| tableName.contains("armorer")) {
				smithChests++;
			}
			for (ItemStack stack : rollLoot(world,
					(LootableContainerBlockEntity) entity, pos)) {
				if (stack.getItem() == Items.IRON_INGOT) {
					ironIngots += stack.getCount();
				} else if (stack.getItem() == Items.DIAMOND) {
					diamonds += stack.getCount();
				} else if (stack.getItem() == Items.IRON_PICKAXE) {
					pickaxe = true;
				} else if (isIronArmor(stack)) {
					armor = true;
				}
			}
		}
		return new Result(ironIngots, pickaxe, armor, chests, smithChests, diamonds);
	}

	private static boolean isIronArmor(ItemStack stack) {
		return stack.getItem() == Items.IRON_HELMET
				|| stack.getItem() == Items.IRON_CHESTPLATE
				|| stack.getItem() == Items.IRON_LEGGINGS
				|| stack.getItem() == Items.IRON_BOOTS;
	}

	private static List<ItemStack> rollLoot(ServerWorld world,
			LootableContainerBlockEntity container, BlockPos pos) {
		LootableContainerAccessor accessor = (LootableContainerAccessor) container;
		Identifier tableId = accessor.speedrunmcalt$getLootTableId();
		if (tableId == null) {
			// Already-rolled or non-loot container; read what is in it.
			return readContents(container);
		}

		LootTable table = world.getServer().getLootManager().getTable(tableId);
		LootContext.Builder builder = new LootContext.Builder(world)
				.parameter(LootContextParameters.POSITION, pos)
				.random(accessor.speedrunmcalt$getLootTableSeed());
		return table.generateLoot(builder.build(LootContextTypes.CHEST));
	}

	private static List<ItemStack> readContents(LootableContainerBlockEntity container) {
		java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
		for (int i = 0; i < container.size(); i++) {
			ItemStack stack = container.getStack(i);
			if (!stack.isEmpty()) {
				stacks.add(stack);
			}
		}
		return stacks;
	}
}
