package com.speedrunmcalt.seed;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.LootableContainerAccessor;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Random;

/**
 * Raises a structure's loot to the guaranteed minimum for its seed type.
 *
 * Vanilla loot tables give a wide spread - measurement put a village
 * with three or more iron at about one seed in eight - so a pool built
 * by searching for good rolls is either tiny or enormously expensive to
 * produce. Instead the roll is left alone and topped up where it falls
 * short, so every match starts from the same floor. This is the same
 * trade as the placed lava pools: worlds are not pure vanilla for their
 * seed, and that has to be published rather than hidden.
 *
 * Only the shortfall is added. A seed that rolls well keeps its good
 * roll, so there is still a spread above the floor and a reason to read
 * what you actually found.
 *
 * Iron is counted in the unit the route cares about, which differs by
 * type - ingots for a village, nuggets for a ruined portal, and both
 * together for the ocean routes.
 *
 * DETERMINISM: both players race the same seed and must get identical
 * worlds. Containers are taken in sorted order, the loot is rolled
 * before counting so contents do not depend on who opened what, and the
 * top-up always lands in the same container and slot.
 */
public final class LootTopUp {
	/** How a type measures its iron requirement. */
	public enum IronUnit {
		/** Whole ingots only. */
		INGOTS,
		/** Nuggets only. */
		NUGGETS,
		/** Ingots and nuggets together, valued as ingots (9 nuggets = 1). */
		INGOT_EQUIVALENT,
	}

	public enum SeedType {
		// A village's iron golem is a guaranteed 4 on top of this, which
		// is why the chest floor is 3 rather than 7.
		VILLAGE(3, IronUnit.INGOTS, 0, "minecraft:chests/village/", false, false),
		DESERT_TEMPLE(7, IronUnit.INGOTS, 13, "minecraft:chests/desert_pyramid", false, false),
		// 27 nuggets is exactly three ingots, which is exactly a bucket -
		// see the note on the bucket route below.
		RUINED_PORTAL(27, IronUnit.NUGGETS, 0, "minecraft:chests/ruined_portal",
				true, true),
		SHIPWRECK(7, IronUnit.INGOT_EQUIVALENT, 4, "minecraft:chests/shipwreck", false, false),
		BURIED_TREASURE(7, IronUnit.INGOT_EQUIVALENT, 4, "minecraft:chests/buried_treasure",
				false, false);

		public final int minIron;
		public final IronUnit unit;
		/** Minimum food items; 0 where the route does not depend on chest food. */
		public final int minFood;
		/**
		 * Loot tables that belong to this structure.
		 *
		 * A structure's bounding box routinely contains containers that
		 * are nothing to do with it - dungeon chests generate under
		 * villages all the time. Without this filter the top-up counted
		 * those toward the guarantee and, worse, deposited the shortfall
		 * into one of them, leaving the iron somewhere underground that
		 * a runner would never open.
		 */
		public final String tablePrefix;
		/** Whether this type guarantees portal obsidian (amount varies by seed). */
		public final boolean needsObsidian;
		/** Whether a means of lighting a portal is guaranteed. */
		public final boolean needsLightSource;

		SeedType(int minIron, IronUnit unit, int minFood, String tablePrefix,
				boolean needsObsidian, boolean needsLightSource) {
			this.minIron = minIron;
			this.unit = unit;
			this.minFood = minFood;
			this.tablePrefix = tablePrefix;
			this.needsObsidian = needsObsidian;
			this.needsLightSource = needsLightSource;
		}
	}

	/**
	 * Obsidian guaranteed in a ruined portal's chest.
	 *
	 * Capped low on purpose: obsidian is meant to come mostly from the
	 * nether, so handing over a whole frame would remove a leg of the
	 * run rather than guarantee a route.
	 *
	 * Four cannot build a portal from nothing - a minimum frame is ten.
	 * It does not need to. A ruined portal seed has two ways in:
	 *
	 *   obsidian route - the ruin's frame is nearly whole, and these
	 *                    four close the remaining gaps. Blocks burying
	 *                    the frame are fine; they come out with a stone
	 *                    pickaxe.
	 *   bucket route   - crying obsidian sits in the frame. It cannot
	 *                    be used as portal material and cannot be
	 *                    cleared without a diamond pickaxe, so the frame
	 *                    is a write-off and the runner casts a fresh
	 *                    portal from the lava at the ruin.
	 *
	 * The bucket route is guaranteed unconditionally: 27 nuggets is
	 * three ingots is one bucket, plus a placed lava pool and water at
	 * the ruin. That means every ruined portal seed is runnable whatever
	 * state its frame is in, and the obsidian is an opportunity rather
	 * than a dependency.
	 *
	 * This is why no frame geometry is parsed anywhere. Deciding the
	 * deficit exactly would mean modelling which standing blocks form a
	 * completable rectangle, in a structure that generates damaged,
	 * rotated and partly buried - a lot of fragile inference to earn a
	 * number that changes nothing, because the bucket route already
	 * covers the bad cases.
	 */
	private static final int PORTAL_OBSIDIAN_MIN = 2;
	private static final int PORTAL_OBSIDIAN_MAX = 4;

	/** Salt for the per-seed obsidian count. */
	private static final long OBSIDIAN_SALT = 0x0B51D1A4L;

	/**
	 * How much obsidian this seed's portal chest gets: 2 to 4, fixed by
	 * the seed.
	 *
	 * Varying it keeps ruined portal openings from being identical
	 * every time - four always is a known quantity a runner stops
	 * reading. Because the bucket route is guaranteed regardless, the
	 * low end costs nothing: two obsidian may not finish a frame, and
	 * the cast is still there.
	 *
	 * Derived from the seed alone, like every other placement decision,
	 * so both players get the same count.
	 */
	private static int obsidianFor(long seed) {
		Random random = new Random(seed ^ OBSIDIAN_SALT);
		return PORTAL_OBSIDIAN_MIN
				+ random.nextInt(PORTAL_OBSIDIAN_MAX - PORTAL_OBSIDIAN_MIN + 1);
	}

	private LootTopUp() {
	}

	/** Food item used to make up a shortfall, chosen to suit the route. */
	private static Item foodFor(SeedType type) {
		switch (type) {
			case DESERT_TEMPLE:
				return Items.ROTTEN_FLESH;
			case SHIPWRECK:
			case BURIED_TREASURE:
				return Items.COOKED_COD;
			default:
				return Items.BREAD;
		}
	}

	/**
	 * Food that counts toward this type's guarantee.
	 *
	 * NOT Item.isFood(), which was the first version and was wrong in a
	 * way that only showed up in play: it returns true for rotten flesh
	 * and poisonous potato, and a shipwreck supply chest is full of
	 * both. The guarantee read as satisfied while the chest held
	 * nothing a runner would eat, so no top-up fired and a player
	 * opened it to find no food at all.
	 *
	 * Rotten flesh counts at a desert temple and nowhere else, because
	 * there it IS the expected loot - the published criteria call for
	 * 13 to 30 of it. Everywhere else it is a trap: eating it costs
	 * hunger and health.
	 */
	private static boolean countsAsFood(SeedType type, ItemStack stack) {
		Item item = stack.getItem();
		if (!item.isFood()) {
			return false;
		}
		if (item == Items.POISONOUS_POTATO || item == Items.SPIDER_EYE
				|| item == Items.PUFFERFISH || item == Items.CHICKEN
				|| item == Items.SUSPICIOUS_STEW) {
			return false; // poison or raw - not something to rely on
		}
		if (item == Items.ROTTEN_FLESH) {
			return type == SeedType.DESERT_TEMPLE;
		}
		return true;
	}

	/**
	 * @param box the structure's bounding box
	 * @return true if the guarantee holds after topping up
	 */
	/**
	 * Where this structure's own containers actually turned out to be.
	 *
	 * Exists because the PREDICTED structure box cannot be trusted
	 * vertically. Its X and Z are exact, but its Y has been observed out
	 * by more than fifty blocks - a temple predicted at y[64..78] whose
	 * chests were at 53. Loot top-up survives that because ContainerScan
	 * reads whole chunk block-entity maps and ignores Y entirely, but
	 * anything that needs to know where the structure really is cannot
	 * use the prediction. The containers we matched by loot table are
	 * the one piece of ground truth the pass already has, so it is worth
	 * keeping rather than scanning again - these reads are expensive on
	 * the launcher's x86 JVM under Rosetta.
	 */
	private static volatile BlockBox lastContainerBounds = null;

	/** @return bounds of the last structure's own containers, or null */
	public static BlockBox lastContainerBounds() {
		return lastContainerBounds;
	}

	private static BlockBox boundsOf(List<BlockPos> positions) {
		BlockPos first = positions.get(0);
		int minX = first.getX(), maxX = first.getX();
		int minY = first.getY(), maxY = first.getY();
		int minZ = first.getZ(), maxZ = first.getZ();
		for (BlockPos pos : positions) {
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
		}
		return new BlockBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public static boolean apply(ServerWorld world, SeedType type, BlockBox box, long seed) {
		return apply(world, type, box, seed, false);
	}

	/**
	 * @param strictBox honour the box's Y as well as its X and Z. Use it
	 *                  only when the box describes something we built
	 *                  and therefore measured, never a predicted
	 *                  structure box - see ContainerScan.findWithin.
	 */
	public static boolean apply(ServerWorld world, SeedType type, BlockBox box, long seed,
			boolean strictBox) {
		// Filter to this structure's own containers BEFORE rolling any
		// of them: rolling clears the loot table id, so the evidence of
		// which structure a container belongs to is gone afterwards.
		List<BlockPos> containers = new java.util.ArrayList<>();
		for (BlockPos pos : (strictBox
				? ContainerScan.findWithin(world, box)
				: ContainerScan.find(world, box))) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container == null) {
				continue;
			}
			net.minecraft.util.Identifier table =
					((LootableContainerAccessor) container).speedrunmcalt$getLootTableId();
			if (table != null && table.toString().startsWith(type.tablePrefix)) {
				containers.add(pos);
			}
		}
		if (containers.isEmpty()) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] {} has no containers to top up", type);
			lastContainerBounds = null;
			return false;
		}
		lastContainerBounds = boundsOf(containers);

		// Work out the smith ordering BEFORE rolling: rolling clears the
		// loot table id, which is the only evidence of which container
		// belongs to a smith.
		containers = smithFirst(world, containers);

		// Roll them before counting. Loot is generated lazily on first
		// open, so counting without this would see empty containers and
		// top up a structure that was already fine.
		for (BlockPos pos : containers) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			if (container == null) {
				continue;
			}
			((LootableContainerAccessor) container).speedrunmcalt$checkLootInteraction(null);
		}

		int ironNuggets = 0;
		int ironIngots = 0;
		int foodItems = 0;
		int obsidian = 0;
		boolean hasLight = false;
		for (BlockPos pos : containers) {
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
					ironIngots += stack.getCount();
				} else if (stack.getItem() == Items.IRON_NUGGET) {
					ironNuggets += stack.getCount();
				}
				if (stack.getItem() == Items.OBSIDIAN) {
					obsidian += stack.getCount();
				}
				// Crying obsidian deliberately does not count: it cannot
				// form a portal frame, so treating it as obsidian would
				// let an unusable seed pass the guarantee.
				if (stack.getItem() == Items.FLINT_AND_STEEL
						|| stack.getItem() == Items.FIRE_CHARGE) {
					hasLight = true;
				}
				if (countsAsFood(type, stack)) {
					foodItems += stack.getCount();
				}
			}
		}

		int have;
		Item ironItem;
		switch (type.unit) {
			case NUGGETS:
				have = ironNuggets;
				ironItem = Items.IRON_NUGGET;
				break;
			case INGOT_EQUIVALENT:
				have = ironIngots + ironNuggets / 9;
				ironItem = Items.IRON_INGOT;
				break;
			case INGOTS:
			default:
				have = ironIngots;
				ironItem = Items.IRON_INGOT;
				break;
		}

		boolean ok = true;
		if (have < type.minIron) {
			int deficit = type.minIron - have;
			if (!insert(world, containers, ironItem, deficit)) {
				ok = false;
			}
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] {} iron {}->{} {} (+{})",
					type, have, type.minIron, type.unit, deficit);
		}

		if (type.needsObsidian) {
			int target = obsidianFor(seed);
			if (obsidian < target) {
				int deficit = target - obsidian;
				if (!insert(world, containers, Items.OBSIDIAN, deficit)) {
					ok = false;
				}
				SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] {} obsidian {}->{} (+{})",
						type, obsidian, target, deficit);
			}
		}

		if (type.needsLightSource && !hasLight) {
			if (!insert(world, containers, Items.FLINT_AND_STEEL, 1)) {
				ok = false;
			}
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] {} added flint and steel", type);
		}

		if (type.minFood > 0 && foodItems < type.minFood) {
			int deficit = type.minFood - foodItems;
			if (!insert(world, containers, foodFor(type), deficit)) {
				ok = false;
			}
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] {} food {}->{} (+{})",
					type, foodItems, type.minFood, deficit);
		}
		return ok;
	}

	/**
	 * Orders containers so a smith's chest is filled first.
	 *
	 * Without this the guaranteed iron went into whichever container
	 * sorted first by position, which across a 150-block village is
	 * usually some farmhouse. A runner heads for the smith, so iron
	 * anywhere else is worth much less than the count suggests.
	 *
	 * Ordering only - every container is still a candidate, so a
	 * smithless village still gets its iron somewhere.
	 */
	private static List<BlockPos> smithFirst(ServerWorld world, List<BlockPos> containers) {
		List<BlockPos> smith = new java.util.ArrayList<>();
		List<BlockPos> rest = new java.util.ArrayList<>();
		for (BlockPos pos : containers) {
			LootableContainerBlockEntity container =
					(LootableContainerBlockEntity) world.getBlockEntity(pos);
			net.minecraft.util.Identifier table = container == null ? null
					: ((LootableContainerAccessor) container).speedrunmcalt$getLootTableId();
			String name = table == null ? "" : table.toString();
			if (name.contains("weaponsmith") || name.contains("toolsmith")
					|| name.contains("armorer")) {
				smith.add(pos);
			} else {
				rest.add(pos);
			}
		}
		smith.addAll(rest);
		return smith;
	}

	/**
	 * Adds items to the first container with room, taking containers in
	 * sorted order so the destination is the same on both machines.
	 */
	private static boolean insert(ServerWorld world, List<BlockPos> containers,
			Item item, int count) {
		int remaining = count;
		for (BlockPos pos : containers) {
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
		SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No room to add {} x{}", item, remaining);
		return false;
	}
}
