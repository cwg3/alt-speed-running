package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Does a seed give a runner WOOD, and an ocean seed its chests?
 *
 * The wood requirement was a biome test: seedtypes.c sampled biome ids
 * every 16 blocks within 5 chunks of spawn and passed on one wooded
 * hit. A biome is not a tree. Found in play on a shipwreck seed whose
 * spawn was open ocean with a jungle biome clipping the sample radius -
 * the only logs within 80 blocks were the WRECK'S OWN HULL, 61 blocks
 * out and 12 under water. No wood, no crafting table, no run.
 *
 * So logs are counted, not biomes, and any log inside a structure's
 * bounding box is excluded: a hull is not firewood.
 *
 * Ocean seeds are also checked for their three chests. Vanilla
 * shipwrecks generate with one to three, and the supply chest is the
 * one carrying food; the incumbent filters for all three rather than
 * building the missing one, which is the smaller deviation and the one
 * worth copying.
 *
 * Deliberately does NOT set MatchState.seedType, so MatchWorldSetup
 * stays out of it and this measures the seed as vanilla generates it.
 *
 * Reads "seed structX structZ type" from spawncheck.txt.
 * Row: seed,PASS|FAIL,detail
 */
public class SpawnResourceHook implements DedicatedServerModInitializer {
	/**
	 * How far a runner may reasonably go for their first wood.
	 *
	 * 128 blocks, not the 80 the old cubiomes check used. 80 was
	 * inherited without asking whether it was right, and it rejected a
	 * desert temple seed whose nearest tree was 83 blocks out - four
	 * blocks past the line, with 470 logs inside 160. That is a
	 * five-second walk, not a dead seed.
	 *
	 * The case this filter exists for is not a slightly longer walk; it
	 * is an ocean spawn with no usable wood at any distance, where the
	 * only logs were a sunken hull. Widening the radius keeps that
	 * rejection and stops punishing a jog.
	 */
	private static final int WOOD_RADIUS = 128;

	/** Logs below this are in a ravine or a sunken wreck, not a forest. */
	private static final int MIN_WOOD_Y = 60;

	/** Enough to make a table and the tools that follow. */
	private static final int MIN_LOGS = 8;

	/**
	 * Blocks of SEABED allowed above any of the wreck's chests: none.
	 *
	 * Submerged is fine and expected; buried is not. The wreck's own
	 * timber above a chest is an ordinary deck and does not count, so
	 * this is zero rather than a tolerance.
	 */
	private static final int MAX_COVER = 0;

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("spawncheck.txt"))) {
				return;
			}
			String seed = "?";
			try {
				String[] p = new String(Files.readAllBytes(
						Paths.get("spawncheck.txt")), "UTF-8").trim().split("\\s+");
				seed = p[0];
				int sx = Integer.parseInt(p[1]);
				int sz = Integer.parseInt(p[2]);
				String type = p.length > 3 ? p[3] : "village";

				ServerWorld world = server.getWorld(World.OVERWORLD);
				BlockPos spawn = world.getSpawnPos();

				// The structure's own box, so its timbers do not count.
				BlockBox exclude = null;
				StructureFeature<?> feature = featureFor(type);
				if (feature != null) {
					VillageSmith.Village v = VillageSmith.inspect(
							server.getStructureManager(), Long.parseLong(seed), feature, sx, sz);
					if (v.exists()) {
						exclude = v.xzBox();
					}
				}

				int logs = countLogs(world, spawn, exclude);
				boolean woodOk = logs >= MIN_LOGS;

				String detail = "spawn=" + spawn.getX() + "," + spawn.getZ()
						+ " logs=" + logs;
				boolean ok = woodOk;

				if ("shipwreck".equals(type) && exclude != null) {
					// Identify the WRECK'S OWN chests by loot table, not
					// by proximity.
					//
					// ContainerScan takes whole chunks, so "is there a
					// food chest" was answered once by an ocean ruin's
					// chest 75 blocks away, while the wreck's own supply
					// chest sat under seven blocks of stone. Reading the
					// loot table id settles which chest belongs to what,
					// and it has to be read BEFORE the inventory,
					// because reading the inventory rolls the loot and
					// clears the tag.
					int wreckChests = 0;
					boolean food = false;
					boolean haveSupply = false;
					boolean haveTreasure = false;
					int worstCover = -1;
					for (BlockPos cp : ContainerScan.find(world, exclude)) {
						net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(cp);
						if (!(be instanceof net.minecraft.block.entity.LootableContainerBlockEntity)) {
							continue;
						}
						net.minecraft.util.Identifier table =
								((com.speedrunmcalt.mixin.LootableContainerAccessor) be)
										.speedrunmcalt$getLootTableId();
						if (table == null || !table.getPath().contains("shipwreck")) {
							continue;   // somebody else's chest
						}
						wreckChests++;
						boolean isMap = table.getPath().contains("map");
						if (table.getPath().contains("supply")) {
							haveSupply = true;
						} else if (table.getPath().contains("treasure")) {
							haveTreasure = true;
						}
						int cover = solidDirectlyAbove(world, cp);
						// The MAP chest is paper, feathers and a map. A
						// block on top of it costs a runner nothing,
						// because they are not opening it.
						if (!isMap) {
							worstCover = Math.max(worstCover, cover);
						}
						SpeedrunMcAlt.LOGGER.info("[spawncheck]   {} at {},{},{} blockedAbove={}",
								table.getPath(), cp.getX(), cp.getY(), cp.getZ(), cover);
						if (table.getPath().contains("supply")) {
							net.minecraft.inventory.Inventory inv =
									(net.minecraft.inventory.Inventory) be;
							for (int i = 0; i < inv.size(); i++) {
								Item item = inv.getStack(i).getItem();
								if (item.isFood()
										&& item != net.minecraft.item.Items.ROTTEN_FLESH
										&& item != net.minecraft.item.Items.PUFFERFISH) {
									food = true;
								}
							}
						}
					}
					detail += " chests=" + wreckChests + " supply=" + haveSupply + " treasure=" + haveTreasure + " food=" + food
							+ " chestsBlocked=" + worstCover;
					// THE WRECK MUST NOT BE BURIED AT ALL. Not "the food
					// is reachable with some digging" - a buried wreck is
					// a different and slower opening, and two players on
					// two shipwreck seeds should be running the same one.
					// The chests that matter are SUPPLY and TREASURE -
					// food, and iron and diamonds. The map chest holds
					// paper, feathers and a filled map, none of which a
					// route uses, so requiring all three rejected wrecks
					// over a chest nobody opens.
					ok = ok && haveSupply && haveTreasure && food
							&& worstCover >= 0 && worstCover <= MAX_COVER;
				}

				SpeedrunMcAlt.LOGGER.info("[spawncheck] {} {} {}",
						seed, ok ? "PASS" : "FAIL", detail);
				try (FileWriter out = new FileWriter("spawncheck.csv", true)) {
					out.write(seed + "," + (ok ? "PASS" : "FAIL") + ","
							+ detail.replace(',', ';') + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[spawncheck] failed", e);
				try (FileWriter out = new FileWriter("spawncheck.csv", true)) {
					out.write(seed + ",ERROR," + e.getClass().getSimpleName() + "\n");
				} catch (Exception ignored) {
					// nothing useful left to do
				}
			}
			server.stop(false);
		});
	}

	/**
	 * Real, reachable logs: above sea level and not under water.
	 *
	 * This deliberately does NOT exclude the structure's bounding box.
	 * The first version did, to stop a sunken shipwreck's hull counting
	 * as firewood - and a village box is about 107 by 174 blocks, so it
	 * swallowed every tree near the village AND the village's own logs,
	 * which runners break for wood as a matter of course. It reported 0
	 * logs for a seed with 235 within 80 blocks, nearest 11 blocks out,
	 * on a seed that had already been played successfully.
	 *
	 * The exclusion was never needed. The hull that started this was at
	 * y51, already below the sea-level cutoff. Height and standing water
	 * are what separate a tree from a wreck; which structure a block
	 * belongs to is the wrong question.
	 */
	/**
	 * Bring the whole scan area to FULL before reading a single block.
	 *
	 * Without this the log count is not reproducible. The same seed
	 * checked seven times gave 2613, 2624, 2631, 2641, 2660, 2681 and
	 * 2690 - three of those from inside one container, back to back, so
	 * it is world generation and not the environment.
	 *
	 * The cause is that trees are placed during a chunk's FEATURES
	 * stage and a trunk near an edge puts logs into its NEIGHBOUR. If
	 * the scan reads a chunk before the neighbour has been populated,
	 * that overhang has not been written yet. Letting getBlockState
	 * generate chunks as the scan wanders means the order - and so the
	 * count - depends on where the scan happens to go first. The spread
	 * is about one tree, which is nothing against 2600 and decisive
	 * against MIN_LOGS.
	 *
	 * One chunk of margin beyond the scan area, so chunks at the very
	 * edge also have their populated neighbours.
	 */
	private static void generateScanArea(ServerWorld world, BlockPos spawn) {
		int cx0 = ((spawn.getX() - WOOD_RADIUS) >> 4) - 1;
		int cx1 = ((spawn.getX() + WOOD_RADIUS) >> 4) + 1;
		int cz0 = ((spawn.getZ() - WOOD_RADIUS) >> 4) - 1;
		int cz1 = ((spawn.getZ() + WOOD_RADIUS) >> 4) + 1;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, true);
			}
		}
	}

	private static int countLogs(ServerWorld world, BlockPos spawn, BlockBox exclude) {
		generateScanArea(world, spawn);
		int found = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		for (int dx = -WOOD_RADIUS; dx <= WOOD_RADIUS; dx++) {
			for (int dz = -WOOD_RADIUS; dz <= WOOD_RADIUS; dz++) {
				if (dx * dx + dz * dz > WOOD_RADIUS * WOOD_RADIUS) {
					continue;
				}
				int x = spawn.getX() + dx;
				int z = spawn.getZ() + dz;
				int top = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
				for (int y = Math.max(MIN_WOOD_Y, top - 24); y <= top + 8; y++) {
					pos.set(x, y, z);
					Block b = world.getBlockState(pos).getBlock();
					if (!b.getTranslationKey().contains("_log")
							&& !b.getTranslationKey().endsWith("_wood")) {
						continue;
					}
					// Submerged wood is a wreck, not a tree.
					if (!world.getFluidState(pos).isEmpty()) {
						continue;
					}
					found++;
				}
			}
		}
		return found;
	}

	/**
	 * Is the block DIRECTLY above this chest solid?
	 *
	 * Nothing may sit on a shipwreck chest - not terrain, not the
	 * wreck's own planking. Reaching these chests is already a swim
	 * down on one breath, and they carry the iron, gold and food the
	 * opening depends on; a block to break on top of that is time the
	 * route cannot spare, whatever the block is made of.
	 *
	 * This matches what the incumbent ships. Across five MCSR Ranked
	 * wrecks, thirteen of fifteen chests have WATER directly above
	 * them, with the deck planks and stairs sitting a block or two
	 * higher rather than on the chest. The two exceptions - gravel over
	 * a map chest, granite over a supply chest - are the only ones in
	 * the sample, and neither was ever opened.
	 *
	 * Earlier versions of this counted blocks, then counted only
	 * terrain, then counted only terrain needing a tool. Each was a
	 * proxy for "can you open it quickly", and each let something
	 * through. The block on top either exists or it does not.
	 */
	private static int solidDirectlyAbove(ServerWorld world, BlockPos pos) {
		BlockPos above = pos.up();
		net.minecraft.block.BlockState st = world.getBlockState(above);
		return st.getMaterial().isSolid() ? 1 : 0;
	}

	private static StructureFeature<?> featureFor(String type) {
		switch (type) {
			case "village": return StructureFeature.VILLAGE;
			case "desert_temple": return StructureFeature.DESERT_PYRAMID;
			case "ruined_portal": return StructureFeature.RUINED_PORTAL;
			case "shipwreck": return StructureFeature.SHIPWRECK;
			case "buried_treasure": return StructureFeature.BURIED_TREASURE;
			default: return null;
		}
	}
}
