package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Places the guaranteed surface lava pools for a match world.
 *
 * Village and desert temple openings both depend on a lava pool near
 * the structure - it is how the portal gets built. Lava lakes are
 * terrain features rather than structures, so they cannot be predicted
 * from cubiomes, and filtering for "structure near spawn AND lava pool
 * near structure AND loot minimums" is far too rare to search for at
 * any sane cost. So they are placed instead. This is the documented
 * trade: match worlds are not pure vanilla for their seed.
 *
 * DETERMINISM IS THE WHOLE POINT. Both players race the same seed, so
 * both must receive byte-identical worlds. Every decision here derives
 * from the seed and the structure position and nothing else - no
 * wall-clock, no client state, no iteration over an unordered
 * collection, no dependence on which chunks happen to be loaded. The
 * caller must have generated the candidate area first, so that terrain
 * queries return the same answers on both machines regardless of how
 * either player moved.
 */
public final class LavaPoolPlacer {
	/** Matches the published criteria: three pools near the structure. */
	public static final int POOL_COUNT = 3;

	/**
	 * Ring the pools are placed in, relative to the structure centre.
	 *
	 * Roughly two chunks out, which is where they sit naturally
	 * relative to a village - far enough not to land on the buildings,
	 * close enough to be part of the opening rather than a detour. The
	 * first version used 24-56 blocks, chosen only to clear the village
	 * footprint, which put some pools nearly four chunks away.
	 */
	private static final int MIN_RADIUS = 20;
	private static final int MAX_RADIUS = 40;

	/** Pool size. Large enough to cast a portal from. */
	private static final int POOL_RADIUS = 3;

	/** Terrain flatter than this across the pool footprint. */
	private static final int MAX_HEIGHT_VARIANCE = 2;

	/** Deterministic candidate budget before giving up. */
	private static final int MAX_CANDIDATES = 256;

	/**
	 * Chunks generated beyond the working area before anything is read.
	 *
	 * Chunk GENERATION is deterministic, but READING a chunk before its
	 * neighbours exist is not: features spill across chunk borders, so
	 * a surface heightmap can change once the neighbour generates. Both
	 * the ground search and the water search read heightmaps, so unless
	 * the whole working area is settled first, the answers depend on
	 * generation order - which is what made lava land in two different
	 * places on the same seed.
	 */
	private static final int SETTLE_MARGIN_CHUNKS = 2;

	/** Salt so this RNG stream cannot coincide with any other seeded off the same value. */
	private static final long SALT = 0x1A7A_9001_5EEDL;
	private static final long WATER_SALT = 0xA7E2_9001_5EEDL;

	/** Ruined portal: lava close enough to be part of the opening. */
	private static final int PORTAL_LAVA_MIN_RADIUS = 10;
	private static final int PORTAL_LAVA_MAX_RADIUS = 28;
	private static final int PORTAL_WATER_MIN_RADIUS = 10;
	private static final int PORTAL_WATER_MAX_RADIUS = 56;

	/**
	 * A water source this close to the portal is good enough, so none
	 * is placed. Four chunks is the working distance for fetching water
	 * as part of the opening.
	 */
	private static final int WATER_ACCEPTABLE_RADIUS = 64;

	/** Keep placed water this far from any lava. */
	private static final int LAVA_CLEARANCE = 9;

	private LavaPoolPlacer() {
	}

	/** Ground a pool may be carved into. Deliberately excludes anything
	 * that indicates a player-relevant structure or existing liquid. */
	private static boolean isNaturalGround(Block block) {
		return block == Blocks.GRASS_BLOCK
				|| block == Blocks.DIRT
				|| block == Blocks.COARSE_DIRT
				|| block == Blocks.PODZOL
				|| block == Blocks.SAND
				|| block == Blocks.RED_SAND
				|| block == Blocks.GRAVEL
				|| block == Blocks.STONE
				|| block == Blocks.SANDSTONE
				|| block == Blocks.TERRACOTTA
				|| block == Blocks.SNOW_BLOCK;
	}

	/**
	 * @param structureX/structureZ the qualifying structure's position
	 * @return how many pools were actually placed
	 */
	public static int place(ServerWorld world, long overworldSeed, int structureX, int structureZ) {
		return place(world, overworldSeed, structureX, structureZ,
				POOL_COUNT, MIN_RADIUS, MAX_RADIUS);
	}

	public static int place(ServerWorld world, long overworldSeed, int structureX, int structureZ,
			int poolCount, int minRadius, int maxRadius) {
		settleArea(world, structureX, structureZ, maxRadius);
		// Seeded from the world seed and the structure position only.
		Random random = new Random(overworldSeed ^ SALT ^ ((long) structureX << 32) ^ structureZ);

		rejectNoGround = rejectWet = rejectSlope = rejectTooClose = 0;
		List<BlockPos> placed = new ArrayList<>();
		for (int attempt = 0; attempt < MAX_CANDIDATES && placed.size() < poolCount; attempt++) {
			// Draw both values every iteration, before any rejection, so
			// the RNG stream advances identically regardless of which
			// candidates pass.
			double angle = random.nextDouble() * Math.PI * 2;
			double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);

			int x = structureX + (int) Math.round(Math.cos(angle) * radius);
			int z = structureZ + (int) Math.round(Math.sin(angle) * radius);

			// Keep pools apart so three pools are three opportunities
			// rather than one big puddle.
			boolean tooClose = false;
			for (BlockPos existing : placed) {
				int dx = existing.getX() - x;
				int dz = existing.getZ() - z;
				if (dx * dx + dz * dz < (POOL_RADIUS * 4) * (POOL_RADIUS * 4)) {
					tooClose = true;
					break;
				}
			}
			if (tooClose) {
				rejectTooClose++;
				continue;
			}

			BlockPos pos = findFlatGround(world, x, z);
			if (pos == null) {
				continue;
			}
			carvePool(world, pos);
			placed.add(pos);
			// Logged individually so a determinism check can diff the
			// exact positions between two runs, not just the count.
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] lava pool at {},{},{}",
					pos.getX(), pos.getY(), pos.getZ());
		}

		if (placed.size() < poolCount) {
			// Worth knowing about: it means the guarantee this class
			// exists to provide was not met for this seed.
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Only placed {}/{} lava pools near {},{}"
							+ " (rejected: noGround={} wet={} slope={} tooClose={})",
					placed.size(), poolCount, structureX, structureZ,
					rejectNoGround, rejectWet, rejectSlope, rejectTooClose);
		} else {
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Placed {} lava pools near {},{}",
					placed.size(), structureX, structureZ);
		}
		return placed.size();
	}

	/** Why candidates were rejected, for diagnosing a barren seed. */
	private static int rejectNoGround, rejectWet, rejectSlope, rejectTooClose;

	/**
	 * Walks down from the heightmap to the first real ground block.
	 *
	 * WORLD_SURFACE counts anything non-air, so on a vegetated seed it
	 * lands on grass, leaves or a log rather than the ground - which
	 * rejected every candidate on the first seed tried. Scanning down a
	 * short way finds the actual surface.
	 *
	 * @return ground Y, or -1 if none within reach
	 */
	private static int groundYAt(ServerWorld world, int x, int z) {
		int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
		if (top <= 1 || top >= 250) {
			return -1;
		}
		int lowest = Math.max(1, top - 12);
		for (int y = top; y >= lowest; y--) {
			Block block = world.getBlockState(new BlockPos(x, y, z)).getBlock();
			if (isNaturalGround(block)) {
				return y;
			}
			if (block == Blocks.WATER || block == Blocks.LAVA || block == Blocks.ICE) {
				return -1; // wet: never a pool site
			}
		}
		return -1;
	}

	/**
	 * @return the surface position to carve at, or null if this spot is
	 *         unsuitable (sloped, liquid, or not natural ground).
	 */
	/**
	 * Lava and water at a ruined portal, for the bucket route.
	 *
	 * When crying obsidian sits in a ruin's frame the frame is a
	 * write-off - crying obsidian is not portal material and needs a
	 * diamond pickaxe to clear - so the runner casts a fresh portal
	 * from lava instead. That needs a lava pool and a water source, and
	 * neither is guaranteed to generate near a ruin, so both are placed.
	 *
	 * Placed for every ruined portal seed, not just the crying ones.
	 * Deciding which frames are salvageable would mean parsing frame
	 * geometry, and an unused lava pool costs a runner nothing while a
	 * missing one ends the route.
	 *
	 * @return true if both landed
	 */
	/**
	 * Generates every chunk in range, plus a margin, in a fixed order.
	 *
	 * Must happen before any terrain is read or written, so that every
	 * read sees a fully settled world regardless of what the player had
	 * loaded or which order anything ran in.
	 */
	private static void settleArea(ServerWorld world, int centreX, int centreZ, int radius) {
		int chunks = (radius >> 4) + SETTLE_MARGIN_CHUNKS;
		int cx0 = (centreX >> 4) - chunks;
		int cx1 = (centreX >> 4) + chunks;
		int cz0 = (centreZ >> 4) - chunks;
		int cz1 = (centreZ >> 4) + chunks;
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				world.getChunk(cx, cz);
			}
		}
	}

	public static BlockPos placePortalAccess(ServerWorld world, long overworldSeed,
			int portalX, int portalZ) {
		settleArea(world, portalX, portalZ, WATER_ACCEPTABLE_RADIUS);
		// Look for existing water BEFORE placing any lava.
		//
		// Doing it the other way round is not deterministic, and a
		// determinism run caught it: lava beside water converts it to
		// stone and obsidian, that conversion is tick-timed, and so the
		// water the scan found first varied between two runs of the
		// same seed (-271,61,27 against -271,61,29). Scanning in a
		// fixed order was not enough, because the world itself differed
		// by the time the scan ran. Reading the world before modifying
		// it removes the dependency entirely.
		//
		// Only place water if the world does not already provide it:
		// every placement makes the world less vanilla, and a ruined
		// portal often generates near a river, lake or coast, so a
		// placed pond would frequently be deviation for nothing.
		boolean haveWater = waterBiomeNearby(world, portalX, portalZ, WATER_ACCEPTABLE_RADIUS);

		int pools = place(world, overworldSeed, portalX, portalZ,
				1, PORTAL_LAVA_MIN_RADIUS, PORTAL_LAVA_MAX_RADIUS);
		if (pools < 1) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No lava pool placed at portal {},{}",
					portalX, portalZ);
			return null;
		}

		if (haveWater) {
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] water biome within {} - none placed",
					WATER_ACCEPTABLE_RADIUS);
			// Report the portal itself: nothing was placed, and the
			// caller only needs to know the step succeeded.
			return new BlockPos(portalX, 63, portalZ);
		}
		return placeWaterSource(world, overworldSeed, portalX, portalZ);
	}

	/**
	 * Whether the world already supplies water near the portal.
	 *
	 * Decided from BIOME, not from block states. Scanning for water
	 * blocks looks more direct and is not deterministic: shoreline
	 * water is still settling when this runs, so "the first still water
	 * block" differed between two runs of the same seed (209,61,66
	 * against 208,61,64) even with every chunk generated first. Biomes
	 * are a pure function of the seed with no tick dependency, so both
	 * clients always agree.
	 *
	 * An ocean or river biome in range means water in range. It is a
	 * proxy, and a deliberately conservative one: when it says no, a
	 * source gets placed, and a redundant pond costs a runner nothing
	 * while a missing one ends the route.
	 */
	private static boolean waterBiomeNearby(ServerWorld world, int centreX, int centreZ,
			int radius) {
		for (int dx = -radius; dx <= radius; dx += 8) {
			for (int dz = -radius; dz <= radius; dz += 8) {
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				Biome biome = world.getBiome(new BlockPos(centreX + dx, 63, centreZ + dz));
				Biome.Category category = biome.getCategory();
				if (category == Biome.Category.OCEAN || category == Biome.Category.RIVER) {
					return true;
				}
			}
		}
		return false;
	}

	private static BlockPos placeWaterSource(ServerWorld world, long overworldSeed,
			int portalX, int portalZ) {
		Random random = new Random(overworldSeed ^ WATER_SALT
				^ ((long) portalX << 32) ^ portalZ);

		for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2;
			double radius = PORTAL_WATER_MIN_RADIUS
					+ random.nextDouble() * (PORTAL_WATER_MAX_RADIUS - PORTAL_WATER_MIN_RADIUS);
			int x = portalX + (int) Math.round(Math.cos(angle) * radius);
			int z = portalZ + (int) Math.round(Math.sin(angle) * radius);

			int ground = groundYAt(world, x, z);
			if (ground < 0) {
				continue;
			}

			// Refuse anywhere near lava: the point is to keep the two
			// apart until the player brings them together.
			if (lavaWithin(world, x, ground, z, LAVA_CLEARANCE)) {
				continue;
			}

			BlockPos water = new BlockPos(x, ground, z);
			// Wall the pit so the source cannot escape.
			world.setBlockState(water.down(), Blocks.STONE.getDefaultState(), 2);
			for (int[] side : new int[][] { {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) {
				BlockPos wall = new BlockPos(x + side[0], ground, z + side[1]);
				if (!world.getBlockState(wall).getMaterial().isSolid()) {
					world.setBlockState(wall, Blocks.STONE.getDefaultState(), 2);
				}
			}
			world.setBlockState(water, Blocks.WATER.getDefaultState(), 2);
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] water source at {},{},{}",
					x, ground, z);
			return water;
		}
		SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No water source placed at portal {},{}",
				portalX, portalZ);
		return null;
	}

	private static boolean lavaWithin(ServerWorld world, int x, int y, int z, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -2; dy <= 2; dy++) {
					if (world.getBlockState(new BlockPos(x + dx, y + dy, z + dz))
							.getBlock() == Blocks.LAVA) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static BlockPos findFlatGround(ServerWorld world, int x, int z) {
		int minGround = Integer.MAX_VALUE;
		int maxGround = Integer.MIN_VALUE;

		for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
			for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
				int ground = groundYAt(world, x + dx, z + dz);
				if (ground < 0) {
					rejectNoGround++;
					return null;
				}
				minGround = Math.min(minGround, ground);
				maxGround = Math.max(maxGround, ground);

				// Refuse anywhere already wet - a pool beside water
				// turns straight to obsidian and stone on placement.
				for (int dy = 1; dy <= 2; dy++) {
					Block near = world.getBlockState(
							new BlockPos(x + dx, ground + dy, z + dz)).getBlock();
					if (near == Blocks.WATER || near == Blocks.ICE) {
						rejectWet++;
						return null;
					}
				}
			}
		}
		if (maxGround - minGround > MAX_HEIGHT_VARIANCE) {
			rejectSlope++;
			return null;
		}
		return new BlockPos(x, minGround, z);
	}

	/**
	 * Carves a shallow bowl and fills it with lava source blocks, with
	 * headroom cleared above so the pool is usable rather than buried.
	 */
	private static void carvePool(ServerWorld world, BlockPos centre) {
		int cx = centre.getX();
		int cy = centre.getY();
		int cz = centre.getZ();

		for (int dx = -POOL_RADIUS; dx <= POOL_RADIUS; dx++) {
			for (int dz = -POOL_RADIUS; dz <= POOL_RADIUS; dz++) {
				int distSq = dx * dx + dz * dz;
				if (distSq > POOL_RADIUS * POOL_RADIUS) {
					continue;
				}

				BlockPos lava = new BlockPos(cx + dx, cy, cz + dz);
				// Solid floor under every lava block, or it drains.
				world.setBlockState(lava.down(), Blocks.STONE.getDefaultState(), 2);
				world.setBlockState(lava, Blocks.LAVA.getDefaultState(), 2);

				// Clear headroom so it reads as a surface pool.
				for (int dy = 1; dy <= 3; dy++) {
					world.setBlockState(new BlockPos(cx + dx, cy + dy, cz + dz),
							Blocks.AIR.getDefaultState(), 2);
				}

				// Rim the edge with stone so lava cannot spill downhill.
				if (distSq > (POOL_RADIUS - 1) * (POOL_RADIUS - 1)) {
					for (int dy = 0; dy <= 0; dy++) {
						BlockPos rim = new BlockPos(cx + dx, cy + dy, cz + dz);
						if (world.getBlockState(rim).getBlock() != Blocks.LAVA) {
							world.setBlockState(rim, Blocks.STONE.getDefaultState(), 2);
						}
					}
				}
			}
		}
	}
}
