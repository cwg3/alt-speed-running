package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.mixin.LootableContainerAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.Random;

/**
 * Builds a ruined portal when the seed's own one is unusable.
 *
 * Roughly two thirds of ruined portal seeds cannot be played as
 * generated: 20% have no portal at all - cubiomes documents that
 * presence cannot be predicted, because the biome check runs after the
 * height is chosen - and 45% generate underground. Verifying and
 * discarding those at pool-build time costs a world generation per
 * candidate and throws away most of them.
 *
 * So the seed supplies the portal where it can, and this supplies one
 * where it cannot. Vanilla's portal is left completely alone wherever
 * it is good, which is most of what a player sees; placement only
 * touches seeds that would otherwise be unplayable.
 *
 * This is the most visible thing the mod builds, and it is worth being
 * plain about the trade: a placed portal is not at a vanilla-determined
 * location, so "seed X has a portal at Y" stops being checkable against
 * the game. It stays reproducible from the published algorithm - but
 * that is verifiable against our spec, not against Minecraft.
 *
 * The frame is a correct minimum portal rather than a replica of
 * vanilla's seven shapes. It plays identically; it is less ornate.
 * Vanilla's variety still shows up on every seed where its own portal
 * is kept.
 */
public final class RuinedPortalPlacer {
	/** Where to look for a spot, relative to the predicted position. */
	private static final int MIN_RADIUS = 8;
	private static final int MAX_RADIUS = 40;

	/** Interior of a minimum portal: 2 wide, 3 tall. */
	private static final int INNER_WIDTH = 2;
	private static final int INNER_HEIGHT = 3;

	/**
	 * NO CRYING OBSIDIAN IN THE FRAME. Every placed portal is
	 * completable from the obsidian in its own chest.
	 *
	 * One in five frames used to get crying obsidian, which cannot form
	 * a portal and needs a diamond pickaxe to clear - the frame was a
	 * write-off by design, and the player was expected to walk to the
	 * lava and water placed nearby and cast a new portal instead. That
	 * was justified as matching vanilla's 80/20 split, and vanilla is
	 * the wrong thing to match here.
	 *
	 * Measured off an MCSR Ranked install: three ruined portal worlds,
	 * 0, 1 and 0 crying obsidian, every frame exactly two real obsidian
	 * short of complete. They filter for completable frames, so every
	 * player on an RP seed runs the SAME opening.
	 *
	 * Ours did not. Vanilla's variety is worth keeping where it does
	 * not change the route - shape, orientation, position, which
	 * corners are missing. Crying obsidian changes the route outright,
	 * from "place two obsidian" to "find lava, find water, cast a
	 * portal", and two players on two RP seeds were running materially
	 * different openings. On a ranked ladder that is a fairness bug
	 * wearing a flavour costume.
	 *
	 * Reported from play at 1:01 into a match, on a frame that was
	 * almost entirely crying obsidian.
	 */

	/**
	 * Frame blocks removed on a clean portal.
	 *
	 * Capped at the chest's obsidian floor, so a clean frame is always
	 * completable from what the chest provides. A gap of five with four
	 * obsidian in the chest would be a portal that cannot be finished
	 * the intended way.
	 */
	private static final int MAX_CLEAN_GAPS = 2;

	private static final int MAX_CANDIDATES = 96;
	private static final long SALT = 0x120B7A1L;

	private RuinedPortalPlacer() {
	}

	private static boolean isNaturalGround(Block block) {
		return block == Blocks.GRASS_BLOCK || block == Blocks.DIRT
				|| block == Blocks.COARSE_DIRT || block == Blocks.PODZOL
				|| block == Blocks.SAND || block == Blocks.RED_SAND
				|| block == Blocks.GRAVEL || block == Blocks.STONE
				|| block == Blocks.SANDSTONE || block == Blocks.TERRACOTTA
				|| block == Blocks.SNOW_BLOCK;
	}

	/**
	 * @return the portal's base position, or null if nowhere suitable
	 */
	public static BlockPos place(ServerWorld world, long seed, int nearX, int nearZ) {
		Random random = new Random(seed ^ SALT ^ ((long) nearX << 32) ^ nearZ);

		// Always a clean, completable frame - see above.
		boolean alongX = random.nextBoolean();

		for (int attempt = 0; attempt < MAX_CANDIDATES; attempt++) {
			// Both draws before any rejection, so the stream advances
			// identically whichever candidates fail.
			double angle = random.nextDouble() * Math.PI * 2;
			double radius = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
			int x = nearX + (int) Math.round(Math.cos(angle) * radius);
			int z = nearZ + (int) Math.round(Math.sin(angle) * radius);

			int ground = groundAt(world, x, z, alongX);
			if (ground < 0) {
				continue;
			}

			BlockPos base = new BlockPos(x, ground, z);
			build(world, base, alongX, random);
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Placed ruined portal at {},{},{} ({}, {})",
					base.getX(), base.getY(), base.getZ(),
					alongX ? "x-aligned" : "z-aligned",
					"clean - completable");
			return base;
		}

		SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Found nowhere to place a ruined portal near {},{}",
				nearX, nearZ);
		return null;
	}

	/**
	 * Flat, natural ground wide enough for the frame and its chest.
	 *
	 * @return ground Y, or -1 if unsuitable
	 */
	private static int groundAt(ServerWorld world, int x, int z, boolean alongX) {
		int width = INNER_WIDTH + 2;
		int spanX = alongX ? width : 3;
		int spanZ = alongX ? 3 : width;

		int minTop = Integer.MAX_VALUE;
		int maxTop = Integer.MIN_VALUE;
		BlockPos.Mutable cursor = new BlockPos.Mutable();

		for (int dx = -1; dx <= spanX; dx++) {
			for (int dz = -1; dz <= spanZ; dz++) {
				int top = world.getTopY(Heightmap.Type.WORLD_SURFACE, x + dx, z + dz);
				if (top <= 1 || top >= 250) {
					return -1;
				}
				// Walk down past vegetation to real ground - the
				// surface heightmap counts grass and leaves.
				int ground = -1;
				for (int y = top; y >= Math.max(1, top - 12); y--) {
					cursor.set(x + dx, y, z + dz);
					Block block = world.getBlockState(cursor).getBlock();
					if (isNaturalGround(block)) {
						ground = y;
						break;
					}
					if (block == Blocks.WATER || block == Blocks.LAVA || block == Blocks.ICE) {
						return -1;
					}
				}
				if (ground < 0) {
					return -1;
				}
				minTop = Math.min(minTop, ground);
				maxTop = Math.max(maxTop, ground);
			}
		}
		if (maxTop - minTop > 1) {
			return -1; // too uneven to stand a frame on
		}
		return minTop + 1;
	}

	private static void build(ServerWorld world, BlockPos base, boolean alongX,
			Random random) {
		int width = INNER_WIDTH + 2;
		int height = INNER_HEIGHT + 2;

		// Frame positions: the full rectangle outline. Corners are not
		// required for a portal to light, which is what makes them the
		// safe blocks to damage.
		// Never more gaps than the chest's obsidian floor can fill.
		int gapsAllowed = random.nextInt(MAX_CLEAN_GAPS + 1);

		for (int u = 0; u < width; u++) {
			for (int v = 0; v < height; v++) {
				boolean edge = u == 0 || u == width - 1 || v == 0 || v == height - 1;
				BlockPos pos = offset(base, alongX, u, v);

				if (!edge) {
					// Interior must be clear for the portal to light.
					world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
					continue;
				}

				boolean corner = (u == 0 || u == width - 1) && (v == 0 || v == height - 1);
				Block block = Blocks.OBSIDIAN;

				if (corner) {
					// Corners are cosmetic; damage lands here first.
					block = random.nextBoolean() ? Blocks.OBSIDIAN : Blocks.AIR;
				} else if (gapsAllowed > 0 && random.nextInt(8) == 0) {
					block = Blocks.AIR;
					gapsAllowed--;
				}
				world.setBlockState(pos, block.getDefaultState(), 2);
			}
		}

		// A floor under the frame, so it is not standing on air where
		// the ground dipped.
		for (int u = -1; u <= width; u++) {
			BlockPos below = offset(base, alongX, u, -1);
			if (!world.getBlockState(below).getMaterial().isSolid()) {
				world.setBlockState(below, Blocks.NETHERRACK.getDefaultState(), 2);
			}
		}

		placeChest(world, base, alongX, random);
	}

	/**
	 * The portal's chest, with the vanilla ruined portal loot table.
	 *
	 * Set as a loot table rather than filled directly, so LootTopUp
	 * finds it exactly like a natural one and applies the same
	 * guarantees - 27 nuggets, 2-4 obsidian, a light source. No special
	 * case, and no second copy of those numbers to drift.
	 */
	private static void placeChest(ServerWorld world, BlockPos base, boolean alongX,
			Random random) {
		BlockPos chestPos = offset(base, alongX, -2, 0);
		BlockPos under = chestPos.down();
		if (!world.getBlockState(under).getMaterial().isSolid()) {
			world.setBlockState(under, Blocks.NETHERRACK.getDefaultState(), 2);
		}
		world.setBlockState(chestPos, Blocks.CHEST.getDefaultState(), 2);

		BlockEntity entity = world.getBlockEntity(chestPos);
		if (entity instanceof LootableContainerBlockEntity) {
			((LootableContainerBlockEntity) entity).setLootTable(
					new Identifier("minecraft", "chests/ruined_portal"), random.nextLong());
		} else {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Placed portal chest has no block entity");
		}
	}

	/** Frame-local coordinates to world position. */
	private static BlockPos offset(BlockPos base, boolean alongX, int u, int v) {
		return alongX
				? new BlockPos(base.getX() + u, base.getY() + v, base.getZ())
				: new BlockPos(base.getX(), base.getY() + v, base.getZ() + u);
	}
}
