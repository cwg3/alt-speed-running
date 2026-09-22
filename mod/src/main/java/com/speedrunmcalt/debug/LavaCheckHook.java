package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.world.LavaPoolPlacer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - drives lava/water placement headlessly so two runs on the
 * same seed can be diffed. Determinism is the property the whole
 * placement design rests on: if the two players' worlds differ the
 * match is void, and it would fail intermittently rather than loudly.
 */
public class LavaCheckHook implements DedicatedServerModInitializer {
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
			if (Files.exists(Paths.get("portal.txt")) || Files.exists(Paths.get("loot.txt"))) {
				return;
			}
			if (!Files.exists(Paths.get("lava.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("lava.txt")), "UTF-8")
						.trim().split("\\s+");
				long seed = Long.parseLong(p[0]);
				int sx = Integer.parseInt(p[1]);
				int sz = Integer.parseInt(p[2]);
				boolean portalMode = p.length > 3 && "portal".equals(p[3]);

				ServerWorld world = server.getWorld(World.OVERWORLD);
				if (portalMode) {
					BlockPos waterPos = LavaPoolPlacer.placePortalAccess(world, seed, sx, sz);
					SpeedrunMcAlt.LOGGER.info("[lava] portalAccess={}", waterPos != null);
					if (waterPos != null) {
						// Check the exact block placed. Counting water in
						// a radius is meaningless here - these portals sit
						// near oceans, and the first attempt at this
						// "verified" 50120 sources, none of them ours.
						BlockState st = world.getBlockState(waterPos);
						SpeedrunMcAlt.LOGGER.info("[lava] waterAt={},{},{} block={} still={}",
								waterPos.getX(), waterPos.getY(), waterPos.getZ(),
								Registry.BLOCK.getId(st.getBlock()),
								st.getFluidState().isStill());
					}
				} else {
					int n = LavaPoolPlacer.place(world, seed, sx, sz);
					SpeedrunMcAlt.LOGGER.info("[lava] placed={}", n);
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[lava] failed", e);
			}
			server.stop(false);
		});
	}
}
