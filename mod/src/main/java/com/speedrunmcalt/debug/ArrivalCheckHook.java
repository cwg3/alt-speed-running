package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.world.ArrivalReach;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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

	@Override
	public void onInitializeServer() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!Files.exists(Paths.get("arrival.txt"))) {
				return;
			}
			try (FileWriter csv = new FileWriter("arrival.csv", true)) {
				ServerWorld nether = server.getWorld(World.NETHER);
				for (String line : Files.readAllLines(Paths.get("arrival.txt"))) {
					String[] p = line.trim().split("\\s+");
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
					long t0 = System.currentTimeMillis();
					ArrivalReach.Result r = ArrivalReach.measure(nether, at, RINGS, 60, (x, z) -> true);
					SpeedrunMcAlt.LOGGER.info("[arrival] {} at {},{},{}: {} (visited {}, {} ms)",
							p[0], at.getX(), at.getY(), at.getZ(), r, r.visited,
							System.currentTimeMillis() - t0);
					StringBuilder row = new StringBuilder(p[0]).append(',')
							.append(at.getX()).append(',').append(at.getY()).append(',').append(at.getZ());
					for (int c : r.cost) {
						row.append(',').append(c);
					}
					row.append(',').append(r.visited);

					// Now the terrain is loaded: time the search alone, which
					// is what a live match pays, then run the real guard and
					// price the arrival again to see what it left behind.
					long t1 = System.currentTimeMillis();
					ArrivalReach.measure(nether, at, RINGS, 60, (x, z) -> true);
					long searchMs = System.currentTimeMillis() - t1;
					com.speedrunmcalt.world.NetherArrival.check(nether, at, 0);
					ArrivalReach.Result after = ArrivalReach.measure(nether, at, RINGS, 60, (x, z) -> true);
					SpeedrunMcAlt.LOGGER.info("[arrival] {} search alone {} ms; after guard: {}",
							p[0], searchMs, after);
					row.append(',').append(searchMs);
					for (int c : after.cost) {
						row.append(',').append(c);
					}
					row.append('\n');
					csv.write(row.toString());
				}
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[arrival] failed", e);
			}
			server.stop(false);
		});
	}
}
