package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - ground truth for nether structure positions.
 *
 * cubiomes predicts bastion and fortress positions, and query.c has
 * carried a comment since it was written saying the NETHER predictions
 * do not match generated worlds. That was dismissed because several
 * bastions did match - until a player walked to a shipped bastion
 * coordinate and found nothing there, and the loot top-up reported "no
 * bastion chests found" because it was searching empty nether.
 *
 * This asks the GAME where the structure is, via the same locator the
 * /locate command uses, so the answer is authoritative rather than
 * another prediction. Reads "<seed>" from netherlocate.txt; the world
 * must be generated with that seed.
 */
public class NetherLocateHook implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("netherlocate.txt"))) {
				return;
			}
			try {
				String[] parts = new String(Files.readAllBytes(
						Paths.get("netherlocate.txt")), "UTF-8").trim().split("\\s+");
				String seed = parts[0];
				// Optional "seed x z": scan for containers around that
				// point as well, to test whether a structure the locator
				// missed is actually present there.
				if (parts.length >= 3) {
					int px = Integer.parseInt(parts[1]);
					int pz = Integer.parseInt(parts[2]);
					ServerWorld nw = server.getWorld(World.NETHER);
					net.minecraft.util.math.BlockBox probe =
							new net.minecraft.util.math.BlockBox(
									px - 48, 0, pz - 48, px + 48, 255, pz + 48);
					int found = 0;
					for (BlockPos cp : com.speedrunmcalt.seed.ContainerScan.find(nw, probe)) {
						net.minecraft.block.entity.BlockEntity be = nw.getBlockEntity(cp);
						String table = be instanceof net.minecraft.block.entity.LootableContainerBlockEntity
								? String.valueOf(((com.speedrunmcalt.mixin.LootableContainerAccessor) be)
										.speedrunmcalt$getLootTableId())
								: "?";
						SpeedrunMcAlt.LOGGER.info("[netherprobe] {},{},{} table={}",
								cp.getX(), cp.getY(), cp.getZ(), table);
						if (++found >= 15) break;
					}
					SpeedrunMcAlt.LOGGER.info("[netherprobe] {} containers within 48 blocks of {},{}",
							found, px, pz);
				}
				ServerWorld nether = server.getWorld(World.NETHER);
				BlockPos origin = new BlockPos(0, 64, 0);

				BlockPos bastion = nether.locateStructure(
						StructureFeature.BASTION_REMNANT, origin, 100, false);
				// Searched from the BASTION, not the origin. The rule is
				// "fortress within 16 chunks of the intended bastion",
				// and the fortress nearest spawn is frequently a
				// different one - measuring to that produced a 90%
				// failure rate that was an artefact of the question
				// being asked wrong, not a property of the seeds.
				BlockPos fortress = bastion == null ? null
						: nether.locateStructure(StructureFeature.FORTRESS, bastion, 100, false);

				// The standard's rules, measured against the GAME rather
				// than against cubiomes:
				//   bastion  <= 14 chunks from nether spawn
				//   fortress <= 16 chunks from THAT BASTION
				double bd = bastion == null ? -1
						: Math.hypot(bastion.getX(), bastion.getZ());
				double fd = (bastion == null || fortress == null) ? -1
						: Math.hypot(fortress.getX() - bastion.getX(),
								fortress.getZ() - bastion.getZ());
				boolean pass = bd >= 0 && bd <= 14 * 16 && fd >= 0 && fd <= 16 * 16;

				SpeedrunMcAlt.LOGGER.info(
						"[netherlocate] {} bastion={} ({} blocks) fortress={} ({} from bastion) {}",
						seed,
						bastion == null ? "none" : bastion.getX() + "," + bastion.getZ(),
						bd < 0 ? "-" : String.valueOf(Math.round(bd)),
						fortress == null ? "none" : fortress.getX() + "," + fortress.getZ(),
						fd < 0 ? "-" : String.valueOf(Math.round(fd)),
						pass ? "PASS" : "FAIL");

				try (FileWriter out = new FileWriter("netherlocate.csv", true)) {
					out.write(seed + ","
							+ (bastion == null ? "" : bastion.getX()) + ","
							+ (bastion == null ? "" : bastion.getZ()) + ","
							+ (fortress == null ? "" : fortress.getX()) + ","
							+ (fortress == null ? "" : fortress.getZ()) + ","
							+ Math.round(bd) + "," + Math.round(fd) + ","
							+ (pass ? "PASS" : "FAIL") + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[netherlocate] failed", e);
			}
			server.stop(false);
		});
	}
}
