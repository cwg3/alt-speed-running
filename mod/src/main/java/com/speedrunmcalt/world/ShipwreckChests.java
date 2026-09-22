package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.LootableContainerAccessor;
import com.speedrunmcalt.seed.ContainerScan;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Gives every shipwreck its full set of three chests.
 *
 * Vanilla builds shipwrecks from several pieces - a full hull, a front
 * half, a back half - and the chests live in different parts of the
 * ship. A runner who draws a full wreck opens supply, treasure and map;
 * a runner who draws a half opens whatever happened to survive. Same
 * seed type, same distance from spawn, and one of them starts with a
 * third of the resources.
 *
 * That is not a difficulty difference, it is a coin flip on which
 * variant generated, and it is settled before either player does
 * anything. So any missing chest is placed.
 *
 * Deliberately NOT a rebuild of the wreck. The ship keeps whatever
 * shape the seed gave it, including being a broken half - only the
 * chests are made whole. A runner still has to find them in a wreck
 * that looks like the one their opponent is swimming through.
 *
 * Loot tables are set rather than contents, so LootTopUp then applies
 * the same iron and food guarantees it would to a natural chest. One
 * source of truth for what a shipwreck owes a player.
 */
public final class ShipwreckChests {
	/** The three chests an unbroken wreck carries. */
	private static final String[] TABLES = {
			"chests/shipwreck_supply",
			"chests/shipwreck_treasure",
			"chests/shipwreck_map",
	};

	private static final long SALT = 0x5417B0A7L;

	private ShipwreckChests() {
	}

	public static void ensureAllThree(ServerWorld world, BlockBox box, long seed) {
		List<BlockPos> existing = ContainerScan.find(world, box);

		Set<String> present = new LinkedHashSet<>();
		List<BlockPos> shipChests = new ArrayList<>();
		for (BlockPos pos : existing) {
			BlockEntity entity = world.getBlockEntity(pos);
			if (!(entity instanceof LootableContainerBlockEntity)) {
				continue;
			}
			Identifier table = ((LootableContainerAccessor) entity).speedrunmcalt$getLootTableId();
			if (table == null) {
				continue;
			}
			String name = table.getPath();
			for (String wanted : TABLES) {
				if (name.equals(wanted)) {
					present.add(wanted);
					shipChests.add(pos);
				}
			}
		}

		List<String> missing = new ArrayList<>();
		for (String wanted : TABLES) {
			if (!present.contains(wanted)) {
				missing.add(wanted);
			}
		}

		if (missing.isEmpty()) {
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Shipwreck already has all 3 chests");
			return;
		}
		if (shipChests.isEmpty()) {
			// Nothing to anchor to. Placing chests at a guessed spot in
			// a wreck we cannot find is worse than leaving it alone -
			// the seed pool's own check should have caught this.
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Shipwreck has no chests at all - not placing {}", missing);
			return;
		}

		Random random = new Random(seed ^ SALT);
		int placed = 0;
		for (String table : missing) {
			BlockPos spot = findSpot(world, shipChests, random);
			if (spot == null) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Nowhere to put the {} chest", table);
				continue;
			}
			world.setBlockState(spot, Blocks.CHEST.getDefaultState(), 2);
			BlockEntity entity = world.getBlockEntity(spot);
			if (entity instanceof LootableContainerBlockEntity) {
				((LootableContainerBlockEntity) entity).setLootTable(
						new Identifier("minecraft", table), random.nextLong());
				shipChests.add(spot);
				placed++;
			}
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Shipwreck chests: {} present, placed {} of {}",
				present.size(), placed, missing.size());
	}

	/**
	 * Somewhere inside the wreck to put a chest.
	 *
	 * Searched outward from a chest that already exists, so the new one
	 * lands in the ship rather than on the seabed beside it. Water is a
	 * valid place for it - shipwreck chests are underwater already.
	 */
	private static BlockPos findSpot(ServerWorld world, List<BlockPos> anchors, Random random) {
		BlockPos anchor = anchors.get(random.nextInt(anchors.size()));
		for (int radius = 1; radius <= 6; radius++) {
			for (int attempt = 0; attempt < 24; attempt++) {
				int dx = random.nextInt(radius * 2 + 1) - radius;
				int dy = random.nextInt(3) - 1;
				int dz = random.nextInt(radius * 2 + 1) - radius;
				BlockPos candidate = anchor.add(dx, dy, dz);

				boolean free = world.getBlockState(candidate).getMaterial().isReplaceable()
						|| world.getFluidState(candidate).getFluid() == Fluids.WATER;
				if (!free) {
					continue;
				}
				// Needs something under it, so it is part of the ship
				// rather than floating in open water.
				if (!world.getBlockState(candidate.down()).getMaterial().isSolid()) {
					continue;
				}
				return candidate;
			}
		}
		return null;
	}
}
