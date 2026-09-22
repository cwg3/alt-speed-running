package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.MagmaRavine;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/** TEMPORARY - measures how often ocean seeds have reachable lava. */
public class RavineCheckHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Files.exists(Paths.get("portalplace.txt"))) {
				return; // portal-placement run owns this boot
			}
			if (Files.exists(Paths.get("bastion.txt"))) {
				return; // bastion-timing run owns this boot
			}
			if (!Files.exists(Paths.get("ravine.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("ravine.txt")), "UTF-8")
						.trim().split("\\s+");
				String seed = p[0];
				int sx = Integer.parseInt(p[1]);
				int sz = Integer.parseInt(p[2]);
				String type = p.length > 3 ? p[3] : "?";
				ServerWorld world = server.getWorld(World.OVERWORLD);

				// One scan, at the 10-chunk radius the guarantee uses.
				// This used to scan twice - once at 5 chunks and once at
				// 10 - so the two radii could be compared. That question
				// is settled, and the near pass was a third of the cost
				// for nothing.
				MagmaRavine.Result far = MagmaRavine.find(world, sx, sz, 160);
				SpeedrunMcAlt.LOGGER.info("[ravine] {} {} ravines={} kelp={} nearest={}",
						seed, type, far.ravineCount, far.kelpNearby, far.nearestDistance);
				try (FileWriter out = new FileWriter("ravine.csv", true)) {
					// seed,type,pass,ravineCount,kelp,magma,stacked,nearest
					boolean pass = far.ravineCount >= 2 && far.kelpNearby;
					out.write(seed + "," + type + "," + pass + "," + far.ravineCount
							+ "," + far.kelpNearby + "," + far.magmaBlocks
							+ "," + far.stackedOnLava + "," + far.nearestDistance + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[ravine] failed", e);
			}
			server.stop(false);
		});
	}
}
