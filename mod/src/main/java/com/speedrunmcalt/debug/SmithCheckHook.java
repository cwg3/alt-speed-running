package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * TEMPORARY - validates the cheap smith pre-filter against ground truth.
 *
 * Runs VillageSmith over every seed in smithbatch.txt in a single boot
 * and writes seed,hasSmith to smith.csv. Cross-checking that against
 * the measured iron counts is what proves the pre-filter is safe: a
 * seed it rejects must never be one that actually had iron, or the
 * pipeline would be silently discarding good seeds.
 */
public class SmithCheckHook implements DedicatedServerModInitializer {
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
			java.nio.file.Path input = Paths.get("smithbatch.txt");
			if (Files.exists(Paths.get("lava.txt")) || !Files.exists(input)) {
				return; // not a smith-check run; leave the server alone
			}
			long startedAt = System.currentTimeMillis();
			int checked = 0;
			try (FileWriter out = new FileWriter("smith.csv", false)) {
				List<String> lines = Files.readAllLines(input);
				for (String line : lines) {
					String trimmed = line.trim();
					if (trimmed.isEmpty()) {
						continue;
					}
					String[] parts = trimmed.split("\\s+");
					long seed = Long.parseLong(parts[0]);
					int vx = Integer.parseInt(parts[1]);
					int vz = Integer.parseInt(parts[2]);
					VillageSmith.Village village = VillageSmith.inspect(
							server.getStructureManager(), seed, vx, vz);
					// Also record the box size, to confirm the old fixed
					// four-chunk radius really was too small.
					int spanX = village.exists() ? (village.predictedBox.maxX - village.predictedBox.minX) : 0;
					int spanZ = village.exists() ? (village.predictedBox.maxZ - village.predictedBox.minZ) : 0;
					String at = village.smithPos == null ? ","
							: village.smithPos.getX() + "," + village.smithPos.getZ();
					out.write(seed + "," + village.hasSmith + "," + spanX + "," + spanZ
							+ "," + at + "\n");
					checked++;
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[smith] batch failed", e);
			}
			long elapsed = System.currentTimeMillis() - startedAt;
			SpeedrunMcAlt.LOGGER.info("[smith] checked {} seeds in {} ms ({} ms/seed)",
					checked, elapsed, checked == 0 ? 0 : elapsed / checked);
			server.stop(false);
		});
	}
}
