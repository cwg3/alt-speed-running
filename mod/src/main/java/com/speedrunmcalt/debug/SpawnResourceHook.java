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
	/** How far a runner may reasonably go for their first wood. */
	private static final int WOOD_RADIUS = 80;

	/** Logs below this are in a ravine or a sunken wreck, not a forest. */
	private static final int MIN_WOOD_Y = 60;

	/** Enough to make a table and the tools that follow. */
	private static final int MIN_LOGS = 8;

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
					int chests = 0;
					boolean food = false;
					for (BlockPos cp : ContainerScan.find(world, exclude)) {
						net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(cp);
						if (!(be instanceof net.minecraft.inventory.Inventory)) {
							continue;
						}
						chests++;
						net.minecraft.inventory.Inventory inv =
								(net.minecraft.inventory.Inventory) be;
						for (int i = 0; i < inv.size(); i++) {
							ItemStack st = inv.getStack(i);
							Item item = st.getItem();
							if (item.isFood() && item != net.minecraft.item.Items.ROTTEN_FLESH
									&& item != net.minecraft.item.Items.PUFFERFISH) {
								food = true;
							}
						}
					}
					detail += " chests=" + chests + " food=" + food;
					// Three chests AND real food, which is what the
					// incumbent filters for. Rotten flesh is not food a
					// runner will eat at full hunger.
					ok = ok && chests >= 3 && food;
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

	/** Real, reachable logs: above sea level and outside the structure. */
	private static int countLogs(ServerWorld world, BlockPos spawn, BlockBox exclude) {
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
					if (exclude != null && exclude.contains(pos)) {
						continue;
					}
					Block b = world.getBlockState(pos).getBlock();
					if (b.getTranslationKey().contains("_log")
							|| b.getTranslationKey().endsWith("_wood")) {
						found++;
					}
				}
			}
		}
		return found;
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
