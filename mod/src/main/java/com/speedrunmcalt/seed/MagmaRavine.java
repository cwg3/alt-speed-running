package com.speedrunmcalt.seed;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds open lava near an ocean structure - the "magma ravine" the
 * shipwreck and buried treasure routes use to build a portal.
 *
 * Ravines are carvers, generated during chunk generation rather than
 * placed as structures, so cubiomes cannot see them any more than it
 * can see a lava lake. The only way to know is to generate and look.
 *
 * What counts is specific: a magma RAVINE, or an above-ground lava
 * pool.
 *
 * A magma ravine is recognised in play by the BUBBLE COLUMNS rising
 * from it, and those come from magma blocks under water - not from
 * lava. That is the signature to look for. A runner spots the bubbles,
 * swims down, and uses the ravine's lava for the portal; without the
 * bubbles there is nothing to spot and it is not the route.
 *
 * Two earlier versions of this got it wrong in different directions,
 * and both would have passed seeds that cannot be played:
 *
 *   v1 - counted any exposed lava below y=31, so lava caves passed as
 *        ravines and every seed looked fine
 *   v2 - distinguished ravines from caves by open shaft height, which
 *        is closer, but still looked for lava rather than the magma
 *        blocks that actually mark a magma ravine
 *
 * The requirement is TWO ravines within 10 chunks, not one within 5.
 * That pair is the whole point: the nearest fissure is often a bad one
 * - a seed tested in a real match had its ravine ten blocks from the
 * shipwreck and bottoming out at y=10, under thirty-five blocks of
 * stone, and the player never found it in twenty minutes of looking. A
 * single-ravine guarantee passes that seed. A two-ravine guarantee
 * gives somewhere else to go.
 *
 * Kelp is required near a ravine, and not only as scenery. It is the
 * marker a runner scans for from a boat - kelp, then ravine, then
 * bubbles, in that order - and it is also used for the air column
 * while placing blocks underwater. A ravine with no kelp near it is
 * one a runner cannot find by the normal method and cannot work in
 * comfortably once there. Frozen and cold oceans have neither kelp nor
 * magma ravines, which is why they are excluded at the cubiomes stage
 * before any world is built.
 *
 * The structure is specific and local: magma blocks sit DIRECTLY ON
 * TOP of natural lava source columns, under water. That stack is the
 * whole feature - bubbles above to find it, magma layer, lava beneath.
 * A runner breaks through, water meets lava, obsidian forms exactly
 * where the portal is wanted, and doors make the air pocket to work
 * in. No diamond pickaxe, no surface lava, under twenty seconds.
 *
 * So the test is that stack, not proximity and not connectivity. Four
 * earlier versions each tested something adjacent to it:
 *
 *   v1 - any exposed lava below y=31, so lava caves passed and every
 *        seed looked fine
 *   v2 - lava with an open shaft above, which separates ravines from
 *        caves but still looks for lava rather than magma
 *   v3 - magma blocks and ravine lava anywhere in the same radius,
 *        which passes bubbles at one end and unrelated lava at the
 *        other
 *   v4 - a flood fill from the magma through water to lava. Sounds
 *        rigorous, and was exactly backwards: magma is SOLID, and the
 *        lava is underneath it, so the fill could never arrive. It
 *        rejected the real thing and cut buried treasure to 1 in 10.
 *
 * Deliberately allocation-light. A naive version of this scan - a fresh
 * BlockPos per read across a large box - is what crashed the real
 * client, whose launcher runs an x86 Java 8 JVM under Rosetta. One
 * mutable cursor is reused instead, and the vertical band is kept to
 * where ravine lava actually sits.
 */
public final class MagmaRavine {
	/** Ravine lava pools out around this level and below. */
	private static final int MIN_Y = 6;
	private static final int MAX_Y = 31;

	/** Coarse step for the first pass; ravines are far wider than this. */
	private static final int STEP = 2;

	/**
	 * Open blocks needed above underground lava for it to count as a
	 * ravine rather than a cave pocket. Ravines cut tall narrow shafts;
	 * caves rarely clear this much straight up.
	 */
	private static final int RAVINE_CLEARANCE = 8;

	/** How close to its own column's surface lava must be to count as a pool. */
	private static final int SURFACE_TOLERANCE = 3;

	/**
	 * How far below a magma block to look for its lava column.
	 *
	 * The lava sits immediately underneath, so this is deliberately
	 * short - a long reach would start finding unrelated cave lava and
	 * reintroduce the false positives of earlier versions.
	 */
	private static final int LAVA_DEPTH = 4;

	/** Two separate fissures must be this far apart to count as two. */
	private static final int CLUSTER_SEPARATION = 48;

	/** Kelp must be within this of a ravine for it to count. */
	private static final int KELP_RADIUS = 32;

	/**
	 * The fissure has to be open from the seabed down to the magma.
	 *
	 * Magma sitting at y=16 or below is normal - ravines cut down that
	 * far. What matters is whether the crack is OPEN above it, because
	 * a runner follows the fissure down from the ocean floor. A pocket
	 * of magma sealed under thirty blocks of stone scores the same on a
	 * horizontal-distance check and cannot be found or reached: one
	 * such seed was tested in a real match and the player never found
	 * it at all, from ten blocks away.
	 *
	 * So the test is water all the way up, not depth.
	 */


	public static final class Result {
		/** Magma blocks under water - the bubble columns a runner spots. */
		public final boolean ravine;
		/** How many distinct fissures were found. */
		public int ravineCount;
		/** Whether kelp sits near at least one of them. */
		public boolean kelpNearby;
		public final boolean surfacePool;
		public final int magmaBlocks;
		/** Magma blocks that actually sit on a lava column. */
		public final int stackedOnLava;
		public final int surfaceBlocks;
		public final int nearestDistance;
		/** Where the nearest qualifying magma block is. */
		public final BlockPos nearestPos;

		Result(boolean ravine, boolean surfacePool, int magmaBlocks, int stackedOnLava,
				int surfaceBlocks, int nearestDistance, BlockPos nearestPos) {
			this.ravine = ravine;
			this.surfacePool = surfacePool;
			this.magmaBlocks = magmaBlocks;
			this.stackedOnLava = stackedOnLava;
			this.surfaceBlocks = surfaceBlocks;
			this.nearestDistance = nearestDistance;
			this.nearestPos = nearestPos;
		}

		/** Either route works. */
		public boolean usable() {
			return ravine || surfacePool;
		}

		@Override
		public String toString() {
			return "ravine=" + ravine + " surfacePool=" + surfacePool
					+ " magma=" + magmaBlocks + " stacked=" + stackedOnLava
					+ " surface=" + surfaceBlocks + " nearest=" + nearestDistance
					+ (nearestPos == null ? "" : " at " + nearestPos.getX() + ","
							+ nearestPos.getY() + "," + nearestPos.getZ());
		}
	}

	private MagmaRavine() {
	}

	/**
	 * Does this magma block actually produce a visible bubble column?
	 *
	 * That is the whole marker. A magma block under water pushes a
	 * downward bubble column, and the column rises through CONTIGUOUS
	 * WATER until it meets a non-water block. A runner spots the column
	 * from above and rides it down - they do not swim down hunting for
	 * the magma, the column carries them.
	 *
	 * So the test is a vertical water column, not reachability. An
	 * earlier version flood-filled sideways through swimmable space,
	 * which is a fine model of swimming and a wrong model of this: a
	 * bubble column cannot turn corners, so a magma block reachable
	 * only via a slanted crack produces nothing anyone can see. That
	 * version scored FEWER seeds than the straight test, which was the
	 * clue it had the mechanic backwards.
	 */
	private static boolean hasBubbleColumn(ServerWorld world, int x, int y, int z) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		int surface = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
		for (int at = y + 1; at < surface; at++) {
			cursor.set(x, at, z);
			Block block = world.getBlockState(cursor).getBlock();
			if (block == Blocks.WATER || block == Blocks.KELP || block == Blocks.KELP_PLANT
					|| block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS) {
				continue;
			}
			return false; // column is capped - no bubbles reach the surface
		}
		return surface > y + 1;
	}

	/**
	 * Kelp near any of the fissures.
	 *
	 * Checked on the column above each one, since kelp grows up from
	 * the seabed and is what a runner spots from the surface.
	 */
	private static boolean kelpNear(ServerWorld world, List<BlockPos> clusters) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (BlockPos centre : clusters) {
			for (int dx = -KELP_RADIUS; dx <= KELP_RADIUS; dx += 4) {
				for (int dz = -KELP_RADIUS; dz <= KELP_RADIUS; dz += 4) {
					for (int y = centre.getY(); y <= Math.min(63, centre.getY() + 60); y++) {
						cursor.set(centre.getX() + dx, y, centre.getZ() + dz);
						Block block = world.getBlockState(cursor).getBlock();
						if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	/**
	 * Is this magma block sitting on a lava column?
	 *
	 * That stack is what makes a magma ravine usable: break through the
	 * magma and the water above meets the lava below, which is the
	 * whole portal trick.
	 */
	private static boolean lavaBeneath(ServerWorld world, int x, int y, int z) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		for (int depth = 1; depth <= LAVA_DEPTH; depth++) {
			cursor.set(x, y - depth, z);
			Block below = world.getBlockState(cursor).getBlock();
			if (below == Blocks.LAVA) {
				return true;
			}
			// Keep going through more magma, stop at anything else -
			// the column is magma then lava, not magma then stone.
			if (below != Blocks.MAGMA_BLOCK) {
				return false;
			}
		}
		return false;
	}

	/**
	 * @param radius how far from the structure to look, in blocks
	 */
	public static Result find(ServerWorld world, int centreX, int centreZ, int radius) {
		for (int cx = (centreX - radius) >> 4; cx <= (centreX + radius) >> 4; cx++) {
			for (int cz = (centreZ - radius) >> 4; cz <= (centreZ + radius) >> 4; cz++) {
				world.getChunk(cx, cz);
			}
		}

		BlockPos.Mutable cursor = new BlockPos.Mutable();
		List<BlockPos> stackedPositions = new ArrayList<>();
		int magmaBlocks = 0;
		int ravineLava = 0;
		int surfaceBlocks = 0;
		int stackedOnLava = 0;
		int nearestSq = Integer.MAX_VALUE;
		BlockPos nearestPos = null;

		for (int x = centreX - radius; x <= centreX + radius; x += STEP) {
			for (int z = centreZ - radius; z <= centreZ + radius; z += STEP) {
				int dx = x - centreX;
				int dz = z - centreZ;
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				int surface = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);

				for (int y = MIN_Y; y <= Math.min(MAX_Y, surface); y++) {
					cursor.set(x, y, z);
					Block block = world.getBlockState(cursor).getBlock();

					if (block == Blocks.MAGMA_BLOCK) {
						// Only counts with water above it: that is what
						// makes the bubble column, and the bubbles are
						// what a runner actually sees.
						cursor.set(x, y + 1, z);
						if (world.getBlockState(cursor).getBlock() == Blocks.WATER) {
							magmaBlocks++;
							boolean stacked = lavaBeneath(world, x, y, z)
									&& hasBubbleColumn(world, x, y, z);
							if (stacked) {
								stackedOnLava++;
								stackedPositions.add(new BlockPos(x, y, z));
							}
							int distSq = dx * dx + dz * dz;
							if (stacked && distSq < nearestSq) {
								nearestSq = distSq;
								nearestPos = new BlockPos(x, y, z);
							}
						}
						continue;
					}

					if (block != Blocks.LAVA) {
						continue;
					}

					// An above-ground pool sits at its own column's
					// surface. Anything much below that is underground,
					// whatever the absolute height.
					if (y >= surface - SURFACE_TOLERANCE) {
						surfaceBlocks++;
						int distSq = dx * dx + dz * dz;
						if (distSq < nearestSq) {
							nearestSq = distSq;
						}
					} else {
						// Lava down in the ravine - what the portal is
						// actually cast from, once the bubbles lead the
						// runner there.
						ravineLava++;
					}
				}
			}
		}

		// The feature is the stack: magma under water, lava under magma.
		boolean ravine = stackedOnLava > 0;
		Result result = new Result(ravine, surfaceBlocks > 0, magmaBlocks, stackedOnLava,
				surfaceBlocks,
				nearestSq == Integer.MAX_VALUE ? -1 : (int) Math.sqrt(nearestSq), nearestPos);

		// Group the stacked positions into distinct fissures, so two
		// magma blocks in one ravine are not counted as two ravines.
		List<BlockPos> clusters = new ArrayList<>();
		for (BlockPos pos : stackedPositions) {
			boolean merged = false;
			for (BlockPos seen : clusters) {
				int ddx = seen.getX() - pos.getX();
				int ddz = seen.getZ() - pos.getZ();
				if (ddx * ddx + ddz * ddz < CLUSTER_SEPARATION * CLUSTER_SEPARATION) {
					merged = true;
					break;
				}
			}
			if (!merged) {
				clusters.add(pos);
			}
		}
		result.ravineCount = clusters.size();
		result.kelpNearby = kelpNear(world, clusters);
		return result;
	}
}
