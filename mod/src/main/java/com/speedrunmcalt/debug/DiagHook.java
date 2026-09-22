package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.LootableContainerAccessor;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * TEMPORARY - ground truth. Dumps every container near a given point,
 * plus what blocks actually exist there, with no filtering at all.
 * Written after three separate hypotheses about the missing smith loot
 * each turned out to be wrong.
 */
public class DiagHook implements DedicatedServerModInitializer {
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
			if (!Files.exists(Paths.get("diag.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("diag.txt")), "UTF-8")
						.trim().split("\\s+");
				int cx = Integer.parseInt(p[1]);
				int cz = Integer.parseInt(p[2]);
				int radius = p.length > 3 ? Integer.parseInt(p[3]) : 24;

				ServerWorld world = server.getWorld(World.OVERWORLD);
				// Force the chunks in range.
				for (int chx = (cx - radius) >> 4; chx <= (cx + radius) >> 4; chx++) {
					for (int chz = (cz - radius) >> 4; chz <= (cz + radius) >> 4; chz++) {
						world.getChunk(chx, chz);
					}
				}

				Map<String, Integer> blockCounts = new HashMap<>();
				SpeedrunMcAlt.LOGGER.info("[diag] --- every container within {} of {},{} ---",
						radius, cx, cz);
				for (int x = cx - radius; x <= cx + radius; x++) {
					for (int z = cz - radius; z <= cz + radius; z++) {
						for (int y = 0; y <= 128; y++) {
							BlockPos pos = new BlockPos(x, y, z);
							Block block = world.getBlockState(pos).getBlock();
							String name = Registry.BLOCK.getId(block).toString();
							if (name.contains("chest") || name.contains("barrel")
									|| name.contains("smithing") || name.contains("grindstone")
									|| name.contains("anvil") || name.contains("furnace")) {
								blockCounts.merge(name, 1, Integer::sum);
							}
							BlockEntity be = world.getBlockEntity(pos);
							if (be instanceof LootableContainerBlockEntity) {
								SpeedrunMcAlt.LOGGER.info("[diag]   {} at {},{},{} table={}",
										name, x, y, z,
										((LootableContainerAccessor) be).speedrunmcalt$getLootTableId());
							}
						}
					}
				}
				SpeedrunMcAlt.LOGGER.info("[diag] --- block census ---");
				for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
					SpeedrunMcAlt.LOGGER.info("[diag]   {} x{}", e.getKey(), e.getValue());
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[diag] failed", e);
			}
			server.stop(false);
		});
	}
}
