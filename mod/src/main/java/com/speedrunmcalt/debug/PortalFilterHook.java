package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.PortalFrame;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Pool filter for ruined portal seeds: keep only the ones whose VANILLA
 * portal a runner can actually finish.
 *
 * This is the stage that lets the mod stop building portals. Measured
 * pass rate 9 of 40 (22%), against 3% for the buried treasure ravine
 * filter that has run without complaint since it was written - so
 * filtering is the affordable option, and it buys a genuine vanilla
 * ruined portal: real debris, real magma, real gold blocks, none of
 * which a placer would reproduce for free.
 *
 * Reads "seed x z" from portalfilter.txt, writes one row to
 * portalfilter.csv:  seed,PASS|FAIL,detail
 */
public class PortalFilterHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("portalfilter.txt"))) {
				return;
			}
			String seed = "?";
			try {
				String[] p = new String(Files.readAllBytes(
						Paths.get("portalfilter.txt")), "UTF-8").trim().split("\\s+");
				seed = p[0];
				int sx = Integer.parseInt(p[1]);
				int sz = Integer.parseInt(p[2]);

				ServerWorld world = server.getWorld(World.OVERWORLD);
				com.speedrunmcalt.seed.VillageSmith.Village predicted =
						com.speedrunmcalt.seed.VillageSmith.inspect(
								server.getStructureManager(), Long.parseLong(seed),
								StructureFeature.RUINED_PORTAL, sx, sz);

				String verdict = "FAIL";
				String detail;
				if (!predicted.exists()) {
					detail = "no ruined portal structure predicted";
				} else {
					// X and Z from the prediction are exact; Y is not,
					// so take the whole column. Same reason
					// ContainerScan ignores Y.
					BlockBox box = predicted.xzBox();
					// Generate it before looking at it.
					for (int cx = box.minX >> 4; cx <= box.maxX >> 4; cx++) {
						for (int cz = box.minZ >> 4; cz <= box.maxZ >> 4; cz++) {
							world.getChunk(cx, cz);
						}
					}
					PortalFrame.Result frame = PortalFrame.check(world, box);
					// A portal with no chest can never be made playable:
					// the two missing obsidian, the light source and the
					// iron are all guaranteed INTO that chest. Two seeds
					// passed the frame check and then failed in the
					// route verifier with "no containers to top up",
					// which is this, found one stage too late.
					int chests = ContainerScan.find(world, box).size();
					boolean lightable = hasIgniterOrIron(world, box);
					detail = frame + " chests=" + chests + " chestCanLight=" + lightable;
					if (frame.usable() && chests > 0) {
						verdict = "PASS";
					}
				}
				SpeedrunMcAlt.LOGGER.info("[portalfilter] {} {} {}", seed, verdict, detail);
				try (FileWriter out = new FileWriter("portalfilter.csv", true)) {
					out.write(seed + "," + verdict + "," + detail.replace(',', ';') + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[portalfilter] failed", e);
				try (FileWriter out = new FileWriter("portalfilter.csv", true)) {
					out.write(seed + ",ERROR," + e.getClass().getSimpleName() + "\n");
				} catch (Exception ignored) {
					// nothing useful left to do
				}
			}
			server.stop(false);
		});
	}

	/** Informational: whether the portal's own chests carry a light source. */
	private static boolean hasIgniterOrIron(ServerWorld world, BlockBox box) {
		for (BlockPos cp : ContainerScan.find(world, box)) {
			net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(cp);
			if (!(be instanceof net.minecraft.inventory.Inventory)) {
				continue;
			}
			net.minecraft.inventory.Inventory inv = (net.minecraft.inventory.Inventory) be;
			for (int i = 0; i < inv.size(); i++) {
				net.minecraft.item.Item item = inv.getStack(i).getItem();
				if (item == Items.FLINT_AND_STEEL || item == Items.FIRE_CHARGE) {
					return true;
				}
			}
		}
		return false;
	}
}
