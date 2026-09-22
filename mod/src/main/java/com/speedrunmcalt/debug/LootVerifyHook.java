package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.VillageLoot;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY batch harness - delete once the seed pool pipeline has a
 * permanent home.
 *
 * Reads "<seed> <villageX> <villageZ>" from village.txt, scans that
 * village's iron, and appends a CSV row to results.csv. The comparison
 * against the game's own rolled loot has already been done and matched
 * (including a weaponsmith village yielding exactly 3 iron), so this
 * only runs the prediction path - which halves the work per seed.
 */
public class LootVerifyHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (Files.exists(Paths.get("portalplace.txt"))) {
				return; // portal-placement run owns this boot
			}
			if (Files.exists(Paths.get("bastion.txt"))) {
				return; // bastion-timing run owns this boot
			}
			if (Files.exists(Paths.get("ravine.txt"))) {
				return; // ravine-check run owns this boot
			}
			if (Files.exists(Paths.get("rppredict.txt"))) {
				return; // rp-predict run owns this boot
			}
			if (Files.exists(Paths.get("rpverify.txt"))) {
				return; // rp-verify run owns this boot
			}
			if (Files.exists(Paths.get("portal.txt"))) {
				return; // portal-diag run owns this boot
			}
			if (Files.exists(Paths.get("loot.txt"))) {
				return; // loot-check run owns this boot
			}
			// Each harness owns a boot; whichever input file is present
			// decides. Without this the first hook stops the server
			// before the others run, and their tests silently pass on
			// empty output.
			if (Files.exists(Paths.get("smithbatch.txt"))
					|| Files.exists(Paths.get("lava.txt"))
					|| Files.exists(Paths.get("diag.txt"))) {
				return;
			}
			if (!Files.exists(Paths.get("village.txt"))) {
				return;
			}
			String seed = "?";
			int vx = 0, vz = 0;
			try {
				String[] parts = new String(Files.readAllBytes(
						Paths.get("village.txt")), "UTF-8").trim().split("\\s+");
				seed = parts[0];
				vx = Integer.parseInt(parts[1]);
				vz = Integer.parseInt(parts[2]);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[scan] could not read village.txt", e);
				server.stop(false);
				return;
			}

			com.speedrunmcalt.seed.VillageSmith.Village village =
					com.speedrunmcalt.seed.VillageSmith.inspect(
							server.getStructureManager(), Long.parseLong(seed), vx, vz);
			if (!village.exists()) {
				SpeedrunMcAlt.LOGGER.warn("[scan] {} no village at {},{}", seed, vx, vz);
				server.stop(false);
				return;
			}
			ServerWorld world = server.getWorld(World.OVERWORLD);
			VillageLoot.Result r = VillageLoot.scan(world, village.xzBox());
			SpeedrunMcAlt.LOGGER.info("[scan] {} {}", seed, r);
			try (FileWriter out = new FileWriter("results.csv", true)) {
				out.write(seed + "," + r.ironIngots + "," + r.hasIronPickaxe
						+ "," + r.hasIronArmor + "," + r.chestsFound
						+ "," + r.smithChests + "," + r.diamonds + "\n");
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[scan] could not write results.csv", e);
			}
			server.stop(false);
		});
	}
}
