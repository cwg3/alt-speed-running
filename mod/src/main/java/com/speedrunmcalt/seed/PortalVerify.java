package com.speedrunmcalt.seed;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureManager;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.chunk.SurfaceChunkGenerator;
import net.minecraft.world.gen.feature.StructureFeature;

/**
 * Is a seed's ruined portal actually usable?
 *
 * NO LONGER DECIDES ANYTHING. Kept for the debug hooks that measure
 * portal statistics; MatchWorldSetup always places a portal now and
 * never consults this.
 *
 * It was the keep-or-place decision until 2026-09-22, and it was not
 * equal to the job. It counts obsidian blocks and checks they are above
 * ground and not underwater - never that they form a VERTICAL FRAME, or
 * that the gaps can be filled with the 2 to 4 obsidian the seed ships.
 * Measured against the standard the incumbent filters for, the three
 * seeds it had passed needed 6, 7 and "not a frame at all". One reached
 * a player, whose portal was twelve obsidian lying flat on the ground.
 *
 * Repairing it was not worth doing: a correct check rejects nearly
 * every candidate, because a near-complete vanilla portal is a rare
 * tail that only a pool of a million seeds can afford to select for.
 * So the decision was removed rather than fixed.
 *
 * This type needs checking in the world in a way the others do not.
 * cubiomes documents that portal presence cannot be predicted - the
 * biome check runs after the portal's type and height are chosen, so a
 * portal can silently fail to generate - and measurement put that at
 * 20% of otherwise-qualifying seeds. A further 45% generate
 * underground, which is not a route.
 *
 * Three conditions, all of which need the world:
 *
 *   exists       - the structure start predicts one, but prediction
 *                  disagrees with reality about one seed in five
 *   above ground - partial burial is fine, it comes out with a stone
 *                  pickaxe; fully buried is not a route
 *   not submerged - unreachable early
 *
 * COST MATTERS HERE. This runs at world creation on the launcher's
 * x86 Java 8 JVM under Rosetta, where an earlier scan of comparable
 * size killed the process outright with a SIGBUS in the JIT. So the
 * search is driven off the predicted structure box - whose X and Z are
 * exact - rather than sweeping a radius, and it reuses one mutable
 * cursor instead of allocating a BlockPos per read. That is roughly
 * 13k reads against the 2.4M the first version did.
 */
public final class PortalVerify {
	/** Vertical slack around the predicted box. */
	private static final int Y_MARGIN = 16;

	/** Blocks of slack around the predicted footprint. */
	private static final int XZ_MARGIN = 8;

	private PortalVerify() {
	}

	public static final class Result {
		public final boolean exists;
		public final boolean aboveGround;
		public final boolean submerged;
		public final int topY;
		public final int terrainY;
		public final int blocks;

		Result(boolean exists, boolean aboveGround, boolean submerged,
				int topY, int terrainY, int blocks) {
			this.exists = exists;
			this.aboveGround = aboveGround;
			this.submerged = submerged;
			this.topY = topY;
			this.terrainY = terrainY;
			this.blocks = blocks;
		}

		public boolean usable() {
			return exists && aboveGround && !submerged;
		}

		@Override
		public String toString() {
			return "exists=" + exists + " above=" + aboveGround + " submerged=" + submerged
					+ " topY=" + topY + " terrainY=" + terrainY + " blocks=" + blocks;
		}
	}

	public static Result check(ServerWorld world, StructureManager structureManager,
			long seed, int portalX, int portalZ) {
		VillageSmith.Village predicted = VillageSmith.inspect(
				structureManager, seed, StructureFeature.RUINED_PORTAL, portalX, portalZ);
		if (!predicted.exists()) {
			return new Result(false, false, false, -1, -1, 0);
		}

		BlockBox box = predicted.predictedBox;
		int minX = box.minX - XZ_MARGIN;
		int maxX = box.maxX + XZ_MARGIN;
		int minZ = box.minZ - XZ_MARGIN;
		int maxZ = box.maxZ + XZ_MARGIN;
		// The predicted Y is close for ruined portals specifically -
		// within a few blocks across every seed compared against real
		// generation - so a modest band is enough. It is NOT reliable
		// for every structure type; shipwrecks were out by fifty.
		int minY = Math.max(1, box.minY - Y_MARGIN);
		int maxY = Math.min(254, box.maxY + Y_MARGIN);

		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				world.getChunk(cx, cz);
			}
		}

		BlockPos.Mutable cursor = new BlockPos.Mutable();
		int blocks = 0;
		int topY = Integer.MIN_VALUE;
		int topX = portalX;
		int topZ = portalZ;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					cursor.set(x, y, z);
					Block block = world.getBlockState(cursor).getBlock();
					if (block != Blocks.OBSIDIAN && block != Blocks.CRYING_OBSIDIAN) {
						continue;
					}
					blocks++;
					if (y > topY) {
						topY = y;
						topX = x;
						topZ = z;
					}
				}
			}
		}

		if (blocks == 0) {
			return new Result(false, false, false, -1, -1, 0);
		}

		// Ground level from the generator's noise rather than the
		// heightmap at the portal's own column - the heightmap counts
		// the portal's obsidian, which would call every portal above
		// ground including a fully buried one.
		SurfaceChunkGenerator generator = GeneratorOptions.createOverworldGenerator(seed);
		int terrainY = generator.getHeight(topX, topZ, Heightmap.Type.WORLD_SURFACE_WG);

		boolean aboveGround = topY >= terrainY;
		cursor.set(topX, topY + 1, topZ);
		boolean submerged = world.getBlockState(cursor).getBlock() == Blocks.WATER;

		return new Result(true, aboveGround, submerged, topY, terrainY, blocks);
	}
}
