package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.LootTopUp;
import com.speedrunmcalt.seed.PortalVerify;
import com.speedrunmcalt.world.RuinedPortalPlacer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - does a placed ruined portal actually hold together?
 *
 * Checks the properties a runner depends on, not just that blocks were
 * written: enough obsidian standing to matter, a clear interior, a
 * chest that LootTopUp recognises and fills, and whether the frame
 * forces the bucket route.
 */
public class PortalPlaceHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("portalplace.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("portalplace.txt")), "UTF-8")
						.trim().split("\\s+");
				long seed = Long.parseLong(p[0]);
				int px = Integer.parseInt(p[1]);
				int pz = Integer.parseInt(p[2]);
				ServerWorld world = server.getWorld(World.OVERWORLD);

				long t0 = System.currentTimeMillis();
				PortalVerify.Result before = PortalVerify.check(
						world, server.getStructureManager(), seed, px, pz);
				long checkMs = System.currentTimeMillis() - t0;

				BlockPos placed = null;
				long placeMs = 0;
				if (!before.usable()) {
					long t1 = System.currentTimeMillis();
					placed = RuinedPortalPlacer.place(world, seed, px, pz);
					placeMs = System.currentTimeMillis() - t1;
				}

				int obsidian = 0, crying = 0, interiorClear = 0, interiorBlocked = 0, chests = 0;
				boolean lootOk = false;
				if (placed != null) {
					BlockBox box = new BlockBox(
							placed.getX() - 8, placed.getY() - 4, placed.getZ() - 8,
							placed.getX() + 8, placed.getY() + 8, placed.getZ() + 8);
					BlockPos.Mutable c = new BlockPos.Mutable();
					for (int x = box.minX; x <= box.maxX; x++) {
						for (int y = box.minY; y <= box.maxY; y++) {
							for (int z = box.minZ; z <= box.maxZ; z++) {
								c.set(x, y, z);
								Block b = world.getBlockState(c).getBlock();
								if (b == Blocks.OBSIDIAN) obsidian++;
								else if (b == Blocks.CRYING_OBSIDIAN) crying++;
							}
						}
					}
					// Interior of the frame must be air to light.
					for (int u = 1; u <= 2; u++) {
						for (int v = 1; v <= 3; v++) {
							c.set(placed.getX() + u, placed.getY() + v, placed.getZ());
							Block b1 = world.getBlockState(c).getBlock();
							c.set(placed.getX(), placed.getY() + v, placed.getZ() + u);
							Block b2 = world.getBlockState(c).getBlock();
							if (b1 == Blocks.AIR || b2 == Blocks.AIR) interiorClear++;
							else interiorBlocked++;
						}
					}
					chests = ContainerScan.find(world, box).size();
					lootOk = LootTopUp.apply(world, LootTopUp.SeedType.RUINED_PORTAL, box, seed);
				}

				SpeedrunMcAlt.LOGGER.info(
						"[place] seed={} vanillaUsable={} placed={} obsidian={} crying={} "
								+ "interiorClear={} blocked={} chests={} lootOk={} "
								+ "checkMs={} placeMs={}",
						seed, before.usable(), placed != null, obsidian, crying,
						interiorClear, interiorBlocked, chests, lootOk, checkMs, placeMs);
				try (FileWriter out = new FileWriter("portalplace.csv", true)) {
					out.write(seed + "," + before.usable() + "," + (placed != null) + ","
							+ obsidian + "," + crying + "," + interiorBlocked + ","
							+ chests + "," + lootOk + "," + checkMs + "," + placeMs + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[place] failed", e);
			}
			server.stop(false);
		});
	}
}
