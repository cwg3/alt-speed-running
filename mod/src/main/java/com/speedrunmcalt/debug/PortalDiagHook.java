package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * TEMPORARY - what a ruined portal's frame is actually made of.
 *
 * Needed before any "complete the frame" logic can be written: the
 * deficit depends on how much obsidian is standing, and crying obsidian
 * occupies frame positions while being unusable for a portal.
 */
public class PortalDiagHook implements DedicatedServerModInitializer {
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
			if (!Files.exists(Paths.get("portal.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("portal.txt")), "UTF-8")
						.trim().split("\\s+");
				long seed = Long.parseLong(p[0]);
				int px = Integer.parseInt(p[1]);
				int pz = Integer.parseInt(p[2]);

				ServerWorld world = server.getWorld(World.OVERWORLD);
				VillageSmith.Village v = VillageSmith.inspect(
						server.getStructureManager(), seed, StructureFeature.RUINED_PORTAL, px, pz);
				if (!v.exists()) {
					SpeedrunMcAlt.LOGGER.error("[portal] no structure at {},{}", px, pz);
					server.stop(false);
					return;
				}

				SpeedrunMcAlt.LOGGER.info("[portal] predicted box x[{}..{}] z[{}..{}]",
						v.predictedBox.minX, v.predictedBox.maxX, v.predictedBox.minZ, v.predictedBox.maxZ);

				// Sweep a radius around the predicted position, not just
				// the predicted box - a box-only scan cannot tell "the
				// structure is absent" from "the structure is offset".
				int radius = p.length > 3 ? Integer.parseInt(p[3]) : 80;
				List<BlockPos> obsidian = new ArrayList<>();
				List<BlockPos> crying = new ArrayList<>();
				for (int cx = (px - radius) >> 4; cx <= (px + radius) >> 4; cx++) {
					for (int cz = (pz - radius) >> 4; cz <= (pz + radius) >> 4; cz++) {
						world.getChunk(cx, cz);
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								for (int y = 0; y <= 255; y++) {
									BlockPos pos = new BlockPos((cx << 4) + x, y, (cz << 4) + z);
									Block b = world.getBlockState(pos).getBlock();
									if (b == Blocks.OBSIDIAN) obsidian.add(pos);
									else if (b == Blocks.CRYING_OBSIDIAN) crying.add(pos);
								}
							}
						}
					}
				}

				SpeedrunMcAlt.LOGGER.info("[portal] seed={} obsidian={} crying={}",
						seed, obsidian.size(), crying.size());
				for (BlockPos b : obsidian) {
					SpeedrunMcAlt.LOGGER.info("[portal]   OBS {},{},{}", b.getX(), b.getY(), b.getZ());
				}
				for (BlockPos b : crying) {
					SpeedrunMcAlt.LOGGER.info("[portal]   CRY {},{},{}", b.getX(), b.getY(), b.getZ());
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[portal] failed", e);
			}
			server.stop(false);
		});
	}
}
