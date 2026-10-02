package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.world.ArrivalReach;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - prices nether arrivals headlessly, to calibrate the
 * arrival guard against arrivals a player actually judged.
 *
 * Reads lines of "label x y z" from arrival.txt, where x y z is the
 * player's feet on arrival (from a replay). Run with level-seed set to
 * the NETHER seed: nether terrain comes from that seed alone, so a
 * one-seed server reproduces it. Appends one row per line to
 * arrival.csv: label,x,y,z,<ring costs>,visited.
 *
 * Every column is generated on demand here, which a live match cannot
 * afford and does not need to - this is the harness, not the product.
 */
public class ArrivalCheckHook implements DedicatedServerModInitializer {
	public static final int[] RINGS = {16, 32, 48, 64, 96};

	private static void quietCheck(ServerWorld world, BlockPos at) {
		com.speedrunmcalt.match.MatchState.quietBox = new net.minecraft.util.math.BlockBox(
				at.getX() - 10, at.getY() - 5, at.getZ() - 10, at.getX() + 10, at.getY() + 20, at.getZ() + 10);
		net.minecraft.entity.EntityType<?>[] types = {
				net.minecraft.entity.EntityType.ZOMBIE, net.minecraft.entity.EntityType.HUSK,
				net.minecraft.entity.EntityType.BAT};
		for (BlockPos pos : new BlockPos[] {at, at.add(40, 0, 0)}) {
			for (net.minecraft.entity.EntityType<?> type : types) {
				net.minecraft.entity.mob.MobEntity mob =
						(net.minecraft.entity.mob.MobEntity) type.create(world);
				mob.refreshPositionAndAngles(pos, 0, 0);
				boolean ok = mob.canSpawn(world, net.minecraft.entity.SpawnReason.NATURAL);
				SpeedrunMcAlt.LOGGER.info("[arrival] quiet {} at {} ({} the box): canSpawn={}",
						net.minecraft.util.registry.Registry.ENTITY_TYPE.getId(type).getPath(),
						pos.getX() + "," + pos.getY() + "," + pos.getZ(),
						com.speedrunmcalt.match.MatchState.quietBox.contains(pos) ? "inside" : "outside", ok);
			}
		}
		com.speedrunmcalt.match.MatchState.quietBox = null;
	}

	private static void appendCosts(StringBuilder row, ArrivalReach.Result r) {
		for (int c : r.cost) {
			row.append(',').append(c);
		}
	}

	private static void writeRows(java.util.List<StringBuilder> rows) {
		try (FileWriter csv = new FileWriter("arrival.csv", true)) {
			for (StringBuilder row : rows) {
				csv.write(row.append('\n').toString());
			}
		} catch (java.io.IOException e) {
			SpeedrunMcAlt.LOGGER.error("[arrival] could not write arrival.csv", e);
		}
	}

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("arrival.txt"))) {
				return;
			}
			java.util.List<BlockPos> arrivals = new java.util.ArrayList<>();
			java.util.List<StringBuilder> rows = new java.util.ArrayList<>();
			int flowTicks = 0;
			try {
				ServerWorld nether = server.getWorld(World.NETHER);
				for (String line : Files.readAllLines(Paths.get("arrival.txt"))) {
					String[] p = line.trim().split("\\s+");
					if (p[0].equals("flow") && p.length >= 2) {
						// "flow <ticks>": after pricing, let fluids run
						// this long and price again.
						flowTicks = Integer.parseInt(p[1]);
						continue;
					}
					if (p[0].equals("quiet") && p.length >= 4) {
						// "quiet x y z": set a quiet box round x,y,z and ask
						// whether a zombie, a husk and a bat may spawn there
						// naturally, then 40 blocks outside it.
						quietCheck(server.getWorld(World.OVERWORLD), new BlockPos(Integer.parseInt(p[1]),
								Integer.parseInt(p[2]), Integer.parseInt(p[3])));
						continue;
					}
					if (p[0].equals("bastion") && p.length >= 3) {
						// "bastion x z": the match's bastion, which the way
						// out is steered toward.
						com.speedrunmcalt.match.MatchState.bastionX = Integer.parseInt(p[1]);
						com.speedrunmcalt.match.MatchState.bastionZ = Integer.parseInt(p[2]);
						continue;
					}
					if (p[0].equals("match") && p.length >= 2) {
						// "match <netherSeed>": behave as a match world from
						// here on. Must come before anything touches the
						// nether, which generates nothing until asked.
						com.speedrunmcalt.match.MatchState.netherSeed = Long.parseLong(p[1]);
						com.speedrunmcalt.match.MatchState.matchStartMillis = System.currentTimeMillis();
						com.speedrunmcalt.match.MatchState.matchId = "probe";
						continue;
					}
					if (p[0].equals("hash") && p.length >= 4) {
						// "hash cx cz r": every block in the chunks within r of
						// chunk cx,cz, hashed - two worlds that agree on this
						// agree block for block.
						int cx = Integer.parseInt(p[1]);
						int cz = Integer.parseInt(p[2]);
						int r = Integer.parseInt(p[3]);
						// Load a ring past the area first: a neighbour places
						// its features into the chunk beside it when IT
						// generates, so a chunk read before its neighbours
						// exist is not finished yet.
						for (int x = cx - r - 1; x <= cx + r + 1; x++) {
							for (int z = cz - r - 1; z <= cz + r + 1; z++) {
								nether.getChunk(x, z);
							}
						}
						long h = 17;
						BlockPos.Mutable m = new BlockPos.Mutable();
						for (int x = (cx - r) * 16; x < (cx + r + 1) * 16; x++) {
							for (int z = (cz - r) * 16; z < (cz + r + 1) * 16; z++) {
								for (int y = 0; y < 128; y++) {
									h = 31 * h + net.minecraft.block.Block.getRawIdFromState(
											nether.getBlockState(m.set(x, y, z)));
								}
							}
						}
						SpeedrunMcAlt.LOGGER.info("[arrival] hash {},{} r{}: {}", cx, cz, r, Long.toHexString(h));
						continue;
					}
					if (p.length < 4) {
						continue;
					}
					if (p[0].equals("dump")) {
						// "dump x z ylo yhi": what a column is made of, top down.
						int dx = Integer.parseInt(p[1]);
						int dz = Integer.parseInt(p[2]);
						int hi = Integer.parseInt(p.length > 4 ? p[4] : "120");
						StringBuilder col = new StringBuilder();
						for (int y = hi; y >= Integer.parseInt(p[3]); y--) {
							net.minecraft.block.BlockState st = nether.getBlockState(new BlockPos(dx, y, dz));
							col.append(y).append(':').append(net.minecraft.util.registry.Registry.BLOCK
									.getId(st.getBlock()).getPath()).append(' ');
						}
						SpeedrunMcAlt.LOGGER.info("[arrival] dump {},{}: {}", dx, dz, col);
						continue;
					}
					BlockPos at = new BlockPos(Integer.parseInt(p[1]),
							Integer.parseInt(p[2]), Integer.parseInt(p[3]));
					// The guard copies only loaded terrain, as it must live;
					// here, generate it all first.
					for (int x = (at.getX() >> 4) - 7; x <= (at.getX() >> 4) + 7; x++) {
						for (int z = (at.getZ() >> 4) - 7; z <= (at.getZ() >> 4) + 7; z++) {
							nether.getChunk(x, z);
						}
					}
					long t0 = System.currentTimeMillis();
					ArrivalReach.Result r = com.speedrunmcalt.world.NetherArrival.price(nether, at, false);
					long searchMs = System.currentTimeMillis() - t0;
					ArrivalReach.Result predicted = com.speedrunmcalt.world.NetherArrival.price(nether, at, true);
					SpeedrunMcAlt.LOGGER.info("[arrival] {} at {},{},{}: as generated {}; lava predicted {} (visited {}, {} ms)",
							p[0], at.getX(), at.getY(), at.getZ(), r, predicted, r.visited, searchMs);
					StringBuilder row = new StringBuilder(p[0]).append(',')
							.append(at.getX()).append(',').append(at.getY()).append(',').append(at.getZ());
					appendCosts(row, r);
					row.append(',').append(r.visited).append(',').append(searchMs);
					appendCosts(row, predicted);

					// Run the real guard, then price what it left behind.
					com.speedrunmcalt.world.NetherArrival.check(nether, at, 0);
					ArrivalReach.Result after = com.speedrunmcalt.world.NetherArrival.price(nether, at, true);
					SpeedrunMcAlt.LOGGER.info("[arrival] {} after guard: {}", p[0], after);
					appendCosts(row, after);
					arrivals.add(at);
					rows.add(row);
				}
				if (flowTicks > 0) {
					// Let the world actually move: force the arrivals'
					// chunks so their fluids tick, run, and price again
					// as it stands - no prediction - then let the guard
					// have its later pass.
					for (BlockPos at : arrivals) {
						for (int x = (at.getX() >> 4) - 6; x <= (at.getX() >> 4) + 6; x++) {
							for (int z = (at.getZ() >> 4) - 6; z <= (at.getZ() >> 4) + 6; z++) {
								nether.setChunkForced(x, z, true);
							}
						}
					}
					int[] ticks = {0};
					int stopAt = flowTicks;
					ServerTickEvents.END_SERVER_TICK.register(srv -> {
						if (++ticks[0] != stopAt) {
							return;
						}
						for (int i = 0; i < arrivals.size(); i++) {
							BlockPos at = arrivals.get(i);
							ArrivalReach.Result flowed = com.speedrunmcalt.world.NetherArrival.price(nether, at, false);
							com.speedrunmcalt.world.NetherArrival.check(nether, at, stopAt);
							ArrivalReach.Result fin = com.speedrunmcalt.world.NetherArrival.price(nether, at, false);
							SpeedrunMcAlt.LOGGER.info("[arrival] {} after {} ticks of flow: {}; after recheck: {}",
									rows.get(i).toString().split(",")[0], stopAt, flowed, fin);
							appendCosts(rows.get(i), flowed);
							appendCosts(rows.get(i), fin);
						}
						writeRows(rows);
						srv.stop(false);
					});
					return;
				}
				writeRows(rows);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[arrival] failed", e);
			}
			server.stop(false);
		});
	}
}
