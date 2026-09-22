package com.speedrunmcalt.seed;

import com.speedrunmcalt.mixin.WorldChunkAccessor;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the loot containers belonging to a structure.
 *
 * Shared rather than duplicated, because the details here were each got
 * wrong once and every one of them failed quietly:
 *
 * 1. Chests are not the only containers. Taiga and snowy villages use
 *    BARRELS, so matching ChestBlockEntity alone misses them.
 *
 * 2. A freshly generated chunk keeps block entities as packed NBT until
 *    something asks for them, so listing only instantiated ones returns
 *    almost nothing for a village that has just generated. Both maps
 *    are read here - see WorldChunkAccessor.
 *
 * 3. The structure box's X and Z are exact but its Y is not: the real
 *    structure shifts vertically to fit terrain, by as much as fifty
 *    blocks. So Y is ignored entirely and chunks are taken whole.
 *
 * Scanning block states was the previous approach to (2) and (3). It
 * worked and it crashed the real client - roughly 9.4 million block
 * reads for a village, which the launcher's Rosetta-translated Java 8
 * JVM did not survive. Reading the chunk's own block entity maps costs
 * a few dozen lookups instead.
 *
 * Results are sorted by position so callers that pick "the first"
 * container get the same answer on every machine; several guarantees
 * depend on both players' worlds being identical.
 */
public final class ContainerScan {
	/** Chunks of slack around the structure box, for a container on the edge. */
	private static final int CHUNK_MARGIN = 1;

	private ContainerScan() {
	}

	/**
	 * @return every loot container in the structure's chunks, in
	 *         deterministic order
	 */
	/**
	 * Containers strictly INSIDE the box, Y included.
	 *
	 * find() takes whole chunks and ignores Y, which is right when the
	 * box came from a structure prediction - those are exact in X and Z
	 * and have been observed wrong in Y by fifty blocks.
	 *
	 * It is wrong when we BUILT the thing and know exactly where it is.
	 * A placed ruined portal sits a few blocks from the buried vanilla
	 * one it replaced, and the chunk-wide scan reached that buried
	 * chest 22 blocks underground: the guarantee counted its flint and
	 * steel as the player's light source and posted the player's iron
	 * into it. The player stood at the surface portal with no way to
	 * light it, in a match that then had to be voided.
	 *
	 * So when the caller knows the real box, it says so and gets it.
	 */
	public static List<BlockPos> findWithin(ServerWorld world, BlockBox box) {
		List<BlockPos> inside = new ArrayList<>();
		for (BlockPos pos : find(world, box)) {
			if (box.contains(pos)) {
				inside.add(pos);
			}
		}
		return inside;
	}

	public static List<BlockPos> find(ServerWorld world, BlockBox box) {
		List<BlockPos> found = new ArrayList<>();

		int minChunkX = (box.minX >> 4) - CHUNK_MARGIN;
		int maxChunkX = (box.maxX >> 4) + CHUNK_MARGIN;
		int minChunkZ = (box.minZ >> 4) - CHUNK_MARGIN;
		int maxChunkZ = (box.maxZ >> 4) + CHUNK_MARGIN;

		int chunks = 0;
		int candidates = 0;
		int alreadyLoaded = 0;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) {
			for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
				chunks++;
				if (world.getChunkManager().isChunkLoaded(cx, cz)) {
					alreadyLoaded++;
				}
				// Generates the chunk if needed, which is what places the
				// structure and its containers.
				WorldChunk chunk = world.getChunk(cx, cz);
				WorldChunkAccessor accessor = (WorldChunkAccessor) chunk;

				// Union of built and not-yet-built block entities. A
				// position can be in both, hence the set.
				Set<BlockPos> positions = new HashSet<>();
				positions.addAll(accessor.speedrunmcalt$getBlockEntities().keySet());
				positions.addAll(accessor.speedrunmcalt$getPendingBlockEntityTags().keySet());
				candidates += positions.size();

				for (BlockPos pos : positions) {
					// IMMEDIATE builds the entity from its pending tag
					// rather than returning null for one not yet made.
					BlockEntity entity = chunk.getBlockEntity(pos, WorldChunk.CreationType.IMMEDIATE);
					if (entity instanceof LootableContainerBlockEntity) {
						found.add(pos.toImmutable());
					}
				}
			}
		}

		// Diagnostic for the case that keeps happening: the scan returns
		// nothing while the structure provably has chests. Distinguishes
		// "no chunks", "chunks but no block entities" and "block
		// entities but none lootable".
		if (found.isEmpty()) {
			com.speedrunmcalt.SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] ContainerScan found nothing: {} chunks visited, "
							+ "{} already loaded, {} block entities seen",
					chunks, alreadyLoaded, candidates);
		}

		Collections.sort(found, new Comparator<BlockPos>() {
			@Override
			public int compare(BlockPos a, BlockPos b) {
				if (a.getX() != b.getX()) {
					return Integer.compare(a.getX(), b.getX());
				}
				if (a.getY() != b.getY()) {
					return Integer.compare(a.getY(), b.getY());
				}
				return Integer.compare(a.getZ(), b.getZ());
			}
		});
		return found;
	}
}
