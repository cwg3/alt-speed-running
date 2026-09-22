package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.BastionLoot;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - how long does the bastion top-up actually take?
 *
 * It is triggered when a player first steps into the nether, and it
 * generates the bastion's chunks on the server thread. If that costs
 * seconds it is a freeze at a terrible moment and the work belongs on
 * the loading screen instead; if it costs milliseconds the deferred
 * placement is free. Worth measuring rather than guessing.
 *
 * Times the two halves separately: generating the chunks, and the
 * top-up itself against already-generated chunks.
 */
public class BastionTimeHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Files.exists(Paths.get("portalplace.txt"))) {
				return; // portal-placement run owns this boot
			}
			if (!Files.exists(Paths.get("bastion.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("bastion.txt")), "UTF-8")
						.trim().split("\\s+");
				long seed = Long.parseLong(p[0]);
				int bx = Integer.parseInt(p[1]);
				int bz = Integer.parseInt(p[2]);

				ServerWorld nether = server.getWorld(World.NETHER);
				VillageSmith.Village bastion = VillageSmith.inspect(
						server.getStructureManager(), seed,
						StructureFeature.BASTION_REMNANT, bx, bz, true);
				if (!bastion.exists()) {
					SpeedrunMcAlt.LOGGER.error("[bastion] no bastion at {},{}", bx, bz);
					server.stop(false);
					return;
				}

				// Cold: chunks not yet generated. This is the cost a
				// player would pay stepping through the portal.
				long t0 = System.currentTimeMillis();
				int chests = ContainerScan.find(nether, bastion.xzBox()).size();
				long scanMs = System.currentTimeMillis() - t0;

				// Warm: chunks already there, so this is the top-up
				// work alone.
				long t1 = System.currentTimeMillis();
				BastionLoot.apply(nether, bastion.xzBox(), seed);
				long topUpMs = System.currentTimeMillis() - t1;

				SpeedrunMcAlt.LOGGER.info(
						"[bastion] seed={} chests={} coldScan={}ms topUp={}ms total={}ms",
						seed, chests, scanMs, topUpMs, scanMs + topUpMs);
				try (FileWriter out = new FileWriter("bastion.csv", true)) {
					out.write(seed + "," + chests + "," + scanMs + "," + topUpMs
							+ "," + (scanMs + topUpMs) + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[bastion] failed", e);
			}
			server.stop(false);
		});
	}
}
