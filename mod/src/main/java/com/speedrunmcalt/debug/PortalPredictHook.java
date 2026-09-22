package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.SurfaceChunkGenerator;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * TEMPORARY - can ruined portal usability be predicted WITHOUT
 * generating the world?
 *
 * Verifying RP by building each world costs ~13s a seed and only 35%
 * qualify, which makes it the one expensive type while the other four
 * are free. But the whole placement decision is noise maths: the
 * structure start picks a vertical placement and height from the chunk
 * generator, then a biome check at that height decides whether the
 * portal generates at all. None of that needs a chunk.
 *
 * Writes prediction against the position so it can be diffed against
 * the ground truth already collected by PortalVerifyHook.
 */
public class PortalPredictHook implements DedicatedServerModInitializer {
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
			if (!Files.exists(Paths.get("rppredict.txt"))) {
				return;
			}
			long startedAt = System.currentTimeMillis();
			int n = 0;
			try (FileWriter out = new FileWriter("rppredict.csv", false)) {
				List<String> lines = Files.readAllLines(Paths.get("rppredict.txt"));
				for (String line : lines) {
					String t = line.trim();
					if (t.isEmpty()) {
						continue;
					}
					String[] p = t.split("\\s+");
					long seed = Long.parseLong(p[0]);
					int px = Integer.parseInt(p[1]);
					int pz = Integer.parseInt(p[2]);

					VillageSmith.Village v = VillageSmith.inspect(
							server.getStructureManager(), seed,
							StructureFeature.RUINED_PORTAL, px, pz);

					SurfaceChunkGenerator gen = GeneratorOptions.createOverworldGenerator(seed);
					int surface = gen.getHeight(px, pz, Heightmap.Type.WORLD_SURFACE_WG);

					int topY = v.exists() ? v.predictedBox.maxY : -1;
					int baseY = v.exists() ? v.predictedBox.minY : -1;
					boolean above = v.exists() && topY >= surface;

					// cubiomes' note says the biome check happens after
					// the height is picked, so the portal can move
					// vertically into a biome that rejects it. Compare
					// the biome at the portal's own height against the
					// one at the surface and see whether the four known
					// false positives share a pattern.
					String biomeAtPortal = "-";
					String biomeAtSurface = "-";
					if (v.exists()) {
						net.minecraft.world.biome.source.BiomeSource src = gen.getBiomeSource();
						biomeAtSurface = String.valueOf(
								net.minecraft.util.registry.Registry.BIOME_KEY.getValue().getPath()
								+ ":" + src.getBiomeForNoiseGen(px >> 2, surface >> 2, pz >> 2)
										.getCategory());
						biomeAtPortal = String.valueOf(
								src.getBiomeForNoiseGen(px >> 2, baseY >> 2, pz >> 2)
										.getCategory());
					}
					out.write(seed + "," + v.exists() + "," + above + "," + topY
							+ "," + surface + "," + baseY
							+ "," + biomeAtPortal + "," + biomeAtSurface + "\n");
					n++;
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[rppredict] failed", e);
			}
			long ms = System.currentTimeMillis() - startedAt;
			SpeedrunMcAlt.LOGGER.info("[rppredict] {} seeds in {} ms ({} ms/seed)",
					n, ms, n == 0 ? 0 : ms / n);
			server.stop(false);
		});
	}
}
