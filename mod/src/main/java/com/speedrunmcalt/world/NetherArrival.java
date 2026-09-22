package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Makes sure a nether arrival has somewhere to stand.
 *
 * Reported from play: "really bad nether spawn - no terrain". A portal
 * can link into the middle of a lava sea or onto a ledge with nothing
 * around it, and the runner who draws that has no route while their
 * opponent, arriving elsewhere on the same seed, walks away normally.
 * Vanilla builds a small obsidian platform when it has to create a
 * portal, but that platform can sit over open lava with nothing
 * reachable from it.
 *
 * This checks whether the arrival is actually runnable, and builds a
 * modest netherrack pad only when it is not.
 *
 * TWO THINGS TO BE PLAIN ABOUT.
 *
 * First, this is a PLACEMENT, so the world stops being pure vanilla at
 * that spot. It is listed in the spec with everything else we add.
 *
 * Second, it breaks the usual "both players get byte-identical worlds"
 * property, because it fires where a player actually arrives and the
 * two of them build portals in different places. That is the same
 * property vanilla's own portal platform already has - going somewhere
 * changes the world there - and the alternative is worse: a guarantee
 * that only helps the player whose portal happened to link somewhere
 * nice is not a guarantee at all.
 */
public final class NetherArrival {
	/** How far around the player to judge the terrain. */
	private static final int SAMPLE_RADIUS = 8;

	/** Vertical band counted as reachable from where they stand. */
	private static final int UP = 3;
	private static final int DOWN = 6;

	/**
	 * Minimum share of sampled columns that must be standable.
	 *
	 * Calibrated DOWN from 0.25 on the first live firing. An arrival
	 * measuring 62 of 289 columns standable - 21.5% - tripped the check
	 * and got a pad, and the player reported the terrain looked
	 * perfectly fine. A pad appearing where vanilla was already
	 * runnable is a deviation bought for nothing.
	 *
	 * One tenth is the line between "hostile" and "nothing to stand
	 * on". The reported case this exists for - arriving over open lava
	 * with no route - measures near zero, not near a fifth.
	 *
	 * Still one observation. The passing case logs its percentage too,
	 * so this can be re-tuned on real numbers rather than re-guessed.
	 */
	private static final double MIN_STANDABLE = 0.10;

	/** Radius of the pad built when an arrival fails the check. */
	private static final int PAD_RADIUS = 5;

	private NetherArrival() {
	}

	/** @return true if a pad was built */
	public static boolean ensureRunnable(ServerWorld nether, BlockPos at) {
		int sampled = 0;
		int standable = 0;
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx++) {
			for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz++) {
				sampled++;
				for (int dy = UP; dy >= -DOWN; dy--) {
					int y = at.getY() + dy;
					if (y < 1 || y > 250) {
						continue;
					}
					cursor.set(at.getX() + dx, y, at.getZ() + dz);
					BlockState floor = nether.getBlockState(cursor);
					if (!floor.getMaterial().isSolid()) {
						continue;
					}
					// Solid, with room to stand on top of it.
					cursor.set(at.getX() + dx, y + 1, at.getZ() + dz);
					boolean headroom = nether.getBlockState(cursor).getMaterial().isReplaceable();
					cursor.set(at.getX() + dx, y + 2, at.getZ() + dz);
					boolean headroom2 = nether.getBlockState(cursor).getMaterial().isReplaceable();
					if (headroom && headroom2) {
						standable++;
					}
					break; // highest surface in the band decides this column
				}
			}
		}

		double share = sampled == 0 ? 1.0 : (double) standable / sampled;
		if (share >= MIN_STANDABLE) {
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Nether arrival is runnable ({}/{} columns standable)",
					standable, sampled);
			return false;
		}

		SpeedrunMcAlt.LOGGER.warn(
				"[speedrunmcalt] Nether arrival unrunnable ({}/{} columns standable) - building a pad",
				standable, sampled);
		buildPad(nether, at);
		return true;
	}

	/**
	 * A netherrack disc under the arrival, with headroom cleared.
	 *
	 * Netherrack rather than obsidian: it is what the nether is made
	 * of, it mines instantly, and a runner can dig through it or pillar
	 * off it exactly as they would off natural ground. An obsidian slab
	 * would be a permanent unmineable scar.
	 */
	private static void buildPad(ServerWorld nether, BlockPos at) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		int floorY = Math.max(1, at.getY() - 1);

		for (int dx = -PAD_RADIUS; dx <= PAD_RADIUS; dx++) {
			for (int dz = -PAD_RADIUS; dz <= PAD_RADIUS; dz++) {
				if (dx * dx + dz * dz > PAD_RADIUS * PAD_RADIUS) {
					continue; // round, not square - it reads as terrain
				}
				cursor.set(at.getX() + dx, floorY, at.getZ() + dz);
				BlockState existing = nether.getBlockState(cursor);
				// Replace lava and air; leave real ground alone.
				if (!existing.getMaterial().isSolid()
						|| existing.getBlock() == Blocks.LAVA) {
					nether.setBlockState(cursor, Blocks.NETHERRACK.getDefaultState(), 2);
				}
				// Clear somewhere to stand.
				for (int dy = 1; dy <= 3; dy++) {
					cursor.set(at.getX() + dx, floorY + dy, at.getZ() + dz);
					Block block = nether.getBlockState(cursor).getBlock();
					if (block == Blocks.LAVA) {
						nether.setBlockState(cursor, Blocks.AIR.getDefaultState(), 2);
					}
				}
			}
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Built nether arrival pad at {},{},{}",
				at.getX(), floorY, at.getZ());
	}
}
