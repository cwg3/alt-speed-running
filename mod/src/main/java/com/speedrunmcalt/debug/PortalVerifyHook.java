package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.PortalVerify;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/** TEMPORARY - batch verifier for ruined portal seeds. */
public class PortalVerifyHook implements DedicatedServerModInitializer {
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
			if (!Files.exists(Paths.get("rpverify.txt"))) {
				return;
			}
			try {
				String[] p = new String(Files.readAllBytes(Paths.get("rpverify.txt")), "UTF-8")
						.trim().split("\\s+");
				String seed = p[0];
				int px = Integer.parseInt(p[1]);
				int pz = Integer.parseInt(p[2]);
				ServerWorld world = server.getWorld(World.OVERWORLD);
				PortalVerify.Result r = PortalVerify.check(
						world, server.getStructureManager(), Long.parseLong(seed), px, pz);
				SpeedrunMcAlt.LOGGER.info("[rp] {} {} usable={}", seed, r, r.usable());
				try (FileWriter out = new FileWriter("rpverify.csv", true)) {
					out.write(seed + "," + r.exists + "," + r.aboveGround + "," + r.submerged
							+ "," + r.topY + "," + r.terrainY + "," + r.blocks
							+ "," + r.usable() + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[rp] failed", e);
			}
			server.stop(false);
		});
	}
}
