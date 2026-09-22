package com.speedrunmcalt.seed;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.LootableContainerAccessor;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Raises a bastion's chest loot to the guaranteed minimum.
 *
 * The bastion is the run's supply depot, and vanilla's chest rolls make
 * it a lottery. Three things are guaranteed collectively across all of
 * a bastion's chests:
 *
 *   iron 3+      - covered alongside what the overworld supplied
 *   obsidian 5+  - with the 6 per 72 barters, this crosses the 10-20
 *                  needed for a double-travel route: ten to leave the
 *                  first portal and ten to cast a second one nearer
 *                  the stronghold. Without it a runner can be stranded
 *                  by chest RNG alone.
 *   string 48-64 - four to five beds, which is a comfortable one-cycle
 *                  dragon kill. The bastion is the PRIMARY bed supply,
 *                  not a supplement, so this is deliberately generous;
 *                  12 string for a single bed would be the wrong order
 *                  of magnitude.
 *
 * Applied to all four bastion types. Treasure and bridge have the
 * densest chest hubs and are the usual examples, but housing units and
 * hoglin stables need the same floor or the type you draw decides the
 * run.
 *
 * DETERMINISM: the string target varies per seed within its range and
 * is derived from the match seed, so both players get the same amount
 * in the same chests.
 */
public final class BastionLoot {
	private static final String BASTION_TABLE_PREFIX = "minecraft:chests/bastion";

	private static final int MIN_IRON = 3;
	private static final int MIN_OBSIDIAN = 5;

	/** Four to five beds' worth. */
	private static final int STRING_MIN = 48;
	private static final int STRING_MAX = 64;

	private static final long SALT = 0xBA5710L;

	private BastionLoot() {
	}

	public static boolean apply(ServerWorld world, BlockBox bastion, long matchSeed) {
		List<BlockPos> chests = new ArrayList<>();
		// Counted so a failure says WHICH failure it was. "No bastion
		// chests found" was reported on a seed that provably has twelve,
		// and the message could not distinguish "the scan returned
		// nothing" from "it returned chests whose loot table had already
		// been cleared by someone opening them".
		int raw = 0;
		int wrongTable = 0;
		for (BlockPos pos : ContainerScan.find(world, bastion)) {
			raw++;
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container == null) {
				continue;
			}
			Identifier table =
					((LootableContainerAccessor) container).speedrunmcalt$getLootTableId();
			if (table != null && table.toString().startsWith(BASTION_TABLE_PREFIX)) {
				chests.add(pos);
			} else {
				wrongTable++;
			}
		}
		if (chests.isEmpty()) {
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] No bastion chests to top up - scan returned {} container(s), "
							+ "{} with a non-bastion or cleared loot table, box x[{}..{}] z[{}..{}]",
					raw, wrongTable, bastion.minX, bastion.maxX, bastion.minZ, bastion.maxZ);
			return false;
		}

		for (BlockPos pos : chests) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container != null) {
				((LootableContainerAccessor) container).speedrunmcalt$checkLootInteraction(null);
			}
		}

		int iron = 0;
		int obsidian = 0;
		int string = 0;
		for (BlockPos pos : chests) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container == null) {
				continue;
			}
			for (int slot = 0; slot < container.size(); slot++) {
				ItemStack stack = container.getStack(slot);
				if (stack.isEmpty()) {
					continue;
				}
				if (stack.getItem() == Items.IRON_INGOT) {
					iron += stack.getCount();
				} else if (stack.getItem() == Items.OBSIDIAN) {
					obsidian += stack.getCount();
				} else if (stack.getItem() == Items.STRING) {
					string += stack.getCount();
				}
			}
		}

		Random random = new Random(matchSeed ^ SALT);
		int stringTarget = STRING_MIN + random.nextInt(STRING_MAX - STRING_MIN + 1);

		boolean ok = true;
		ok &= topUp(world, chests, Items.IRON_INGOT, iron, MIN_IRON, "iron");
		ok &= topUp(world, chests, Items.OBSIDIAN, obsidian, MIN_OBSIDIAN, "obsidian");
		ok &= topUp(world, chests, Items.STRING, string, stringTarget, "string");

		SpeedrunMcAlt.LOGGER.info(
				"[speedrunmcalt] Bastion loot: {} chests, iron {}->{}, obsidian {}->{}, string {}->{}",
				chests.size(), iron, Math.max(iron, MIN_IRON),
				obsidian, Math.max(obsidian, MIN_OBSIDIAN),
				string, Math.max(string, stringTarget));
		return ok;
	}

	private static boolean topUp(ServerWorld world, List<BlockPos> chests, Item item,
			int have, int target, String label) {
		if (have >= target) {
			return true;
		}
		int remaining = target - have;
		for (BlockPos pos : chests) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container == null) {
				continue;
			}
			for (int slot = 0; slot < container.size() && remaining > 0; slot++) {
				ItemStack stack = container.getStack(slot);
				if (stack.isEmpty()) {
					int put = Math.min(remaining, item.getMaxCount());
					container.setStack(slot, new ItemStack(item, put));
					remaining -= put;
				} else if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
					int put = Math.min(remaining, stack.getMaxCount() - stack.getCount());
					stack.increment(put);
					remaining -= put;
				}
			}
			container.markDirty();
			if (remaining <= 0) {
				return true;
			}
		}
		SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No room for {} more {}", remaining, label);
		return false;
	}
}
