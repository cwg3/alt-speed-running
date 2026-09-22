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
				// Hoisted so the CSV can carry them. The container count
				// around the SHIPPED bastion coordinate is the whole
				// point of this probe: a run of PASS rows proves the
				// seeds are good, and proves nothing about whether the
				// player is sent to the right place.
				int probeX = Integer.MIN_VALUE;
				int probeZ = Integer.MIN_VALUE;
				int probeContainers = -1;
				// "seed x z netherSeed": pretend to be a match world.
				//
				// A dedicated server has ONE seed, so every probe built
				// with one could never reproduce the two-seed bug that
				// put three bastions in the wrong place. Setting
				// MatchState here makes the structure-seed mixin fire
				// exactly as it does in a real match, so this harness
				// finally has the shape of the product.
				if (parts.length >= 4) {
					com.speedrunmcalt.match.MatchState.netherSeed = Long.parseLong(parts[3]);
					com.speedrunmcalt.match.MatchState.matchStartMillis = System.currentTimeMillis();
					com.speedrunmcalt.match.MatchState.matchId = "probe";
					SpeedrunMcAlt.LOGGER.info(
							"[netherlocate] simulating a match world: world seed {}, nether seed {}",
							seed, parts[3]);
				}
				// Optional "seed x z": scan for containers around that
				// point as well, to test whether a structure the locator
				// missed is actually present there.
				if (parts.length >= 3) {
					probeX = Integer.parseInt(parts[1]);
					probeZ = Integer.parseInt(parts[2]);
					int px = probeX;
					int pz = probeZ;
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
					probeContainers = found;
					SpeedrunMcAlt.LOGGER.info("[netherprobe] {} containers within 48 blocks of {},{}",
							found, px, pz);
				}
				ServerWorld nether = server.getWorld(World.NETHER);

				// Enumerate STRUCTURE STARTS rather than asking the locator.
				//
				// locateStructure does not answer the question the rules ask.
				// It walks outward through the structure region grid and
				// returns the first viable placement it meets, which is "a
				// bastion in an early ring", not "the nearest bastion". On
				// pool pair b0699117 it returned one 265 blocks out while a
				// real hoglin stable - nine chests, exactly the type we ship -
				// stood 160 blocks out at the coordinate we had shipped. Judged
				// by the locator that seed failed the 14-chunk rule; judged by
				// what the game generated it passes comfortably.
				//
				// Generating each chunk to STRUCTURE_STARTS is cheap - no
				// terrain, no features - and it is ground truth: it is the same
				// pass that decides where the structure really goes.
				java.util.List<BlockPos> bastions =
						startsWithin(nether, 0, 0, 20, StructureFeature.BASTION_REMNANT);

				BlockPos bastion = bastions.isEmpty() ? null : bastions.get(0);
				double bd = bastion == null ? -1 : Math.hypot(bastion.getX(), bastion.getZ());
				// The rule is not only "close" but "significantly closer than
				// any other competing bastion" - a runner who cannot tell which
				// one was intended has no route, only a guess.
				double bd2 = bastions.size() < 2 ? -1
						: Math.hypot(bastions.get(1).getX(), bastions.get(1).getZ());

				// Fortress measured FROM THE BASTION, per the rule. Measuring
				// from spawn instead produced a 90% failure rate that was an
				// artefact of asking the wrong question.
				BlockPos fortress = null;
				double fd = -1;
				if (bastion != null) {
					java.util.List<BlockPos> forts = startsWithin(
							nether, bastion.getX(), bastion.getZ(), 20, StructureFeature.FORTRESS);
					if (!forts.isEmpty()) {
						fortress = forts.get(0);
						fd = Math.hypot(fortress.getX() - bastion.getX(),
								fortress.getZ() - bastion.getZ());
					}
				}

				boolean pass = bd >= 0 && bd <= 14 * 16 && fd >= 0 && fd <= 16 * 16;

				SpeedrunMcAlt.LOGGER.info(
						"[netherlocate] {} bastion={} ({} blocks, next nearest {}) fortress={} ({} from bastion) {}",
						seed,
						bastion == null ? "none" : bastion.getX() + "," + bastion.getZ(),
						bd < 0 ? "-" : String.valueOf(Math.round(bd)),
						bd2 < 0 ? "-" : String.valueOf(Math.round(bd2)),
						fortress == null ? "none" : fortress.getX() + "," + fortress.getZ(),
						fd < 0 ? "-" : String.valueOf(Math.round(fd)),
						pass ? "PASS" : "FAIL");

				// How far the coordinate we SHIP is from the bastion the game
				// actually generated. This is the number a player experiences:
				// 0 means we sent them exactly right, and anything in the
				// hundreds is what was reported in play as "the bastion wasn't
				// at the coords you gave me".
				double shipErr = (probeX == Integer.MIN_VALUE || bastion == null) ? -1
						: Math.hypot(bastion.getX() - probeX, bastion.getZ() - probeZ);

				SpeedrunMcAlt.LOGGER.info(
						"[netherlocate] shipped {},{} is {} blocks from the generated bastion, "
								+ "{} containers found there",
						probeX, probeZ,
						shipErr < 0 ? "-" : String.valueOf(Math.round(shipErr)), probeContainers);

				try (FileWriter out = new FileWriter("netherlocate.csv", true)) {
					out.write(seed + ","
							+ (bastion == null ? "" : bastion.getX()) + ","
							+ (bastion == null ? "" : bastion.getZ()) + ","
							+ (fortress == null ? "" : fortress.getX()) + ","
							+ (fortress == null ? "" : fortress.getZ()) + ","
							+ Math.round(bd) + "," + Math.round(fd) + ","
							+ (pass ? "PASS" : "FAIL") + ","
							+ (probeX == Integer.MIN_VALUE ? "" : String.valueOf(probeX)) + ","
							+ (probeZ == Integer.MIN_VALUE ? "" : String.valueOf(probeZ)) + ","
							+ Math.round(shipErr) + ","
							+ probeContainers + ","
							+ Math.round(bd2) + ","
							+ bastions.size() + "\n");
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[netherlocate] failed", e);
			}
			server.stop(false);
		});
	}

	/**
	 * Every generated start of one structure within chunkRadius of a
	 * point, nearest first.
	 *
	 * hasChildren() is not optional. A chunk's structure-start map
	 * carries placeholder entries for features that were considered and
	 * not placed, and counting those would turn "no bastion here" into a
	 * confident wrong coordinate - the same shape of mistake as counting
	 * a crashed worker as a zero.
	 */
	private static java.util.List<BlockPos> startsWithin(ServerWorld world,
			int centerX, int centerZ, int chunkRadius, StructureFeature<?> feature) {
		final int ccx = centerX >> 4;
		final int ccz = centerZ >> 4;
		java.util.List<BlockPos> found = new java.util.ArrayList<>();
		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				net.minecraft.world.chunk.Chunk chunk = world.getChunk(
						ccx + dx, ccz + dz,
						net.minecraft.world.chunk.ChunkStatus.STRUCTURE_STARTS, true);
				if (chunk == null) {
					continue;
				}
				net.minecraft.structure.StructureStart<?> start =
						chunk.getStructureStarts().get(feature);
				if (start != null && start.hasChildren()) {
					found.add(start.getPos());
				}
			}
		}
		final double cx = centerX;
		final double cz = centerZ;
		found.sort(java.util.Comparator.comparingDouble(
				pos -> Math.hypot(pos.getX() - cx, pos.getZ() - cz)));
		return found;
	}
}
