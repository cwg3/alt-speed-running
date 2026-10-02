package com.speedrunmcalt.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Material;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;

/**
 * A copy of the terrain around a nether arrival, with the lava that is
 * about to move already moved.
 *
 * Two problems from play on 2026-10-01, the first match the rewritten
 * guard fired in. It priced the arrival, opened a tunnel that really did
 * lead out, and the player still voted the seed bad: a lavafall from a
 * ceiling spring started running when the player's arrival loaded it,
 * spread across the floor at the portal, and cut the pocket off from the
 * tunnel. The guard had read a world that was still in motion. And the
 * search itself took 4.7 seconds on the server thread - a freeze at
 * exactly the moment the runner needs to move.
 *
 * So the guard no longer searches the live world. This copies it a few
 * chunks per tick, then {@link #predictLava} floods every open cell lava
 * can reach the way vanilla nether lava flows, and the search runs on
 * the copy off the server thread. The copy answers LAVA for a cell lava
 * is about to fill, so the search treats it exactly like lava that is
 * already there: never walked through, never mined beside, and bridged
 * over by placing a block - which also dams it.
 *
 * The prediction errs wet on purpose. Vanilla lava with a hole within
 * four blocks flows only toward the hole; this lets it go every way, so
 * it can only mark too much, never too little. Marking too much costs
 * a few extra blocks of path; marking too little strands the runner.
 */
public final class ArrivalSnapshot implements BlockView {
	/** Nether lava loses one level per block sideways: seven blocks from a source. */
	private static final int LAVA_REACH = 8;

	private final int minX;
	private final int minY;
	private final int minZ;
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final BlockState[] states;
	private final boolean[] future;
	private final boolean[] loadedChunk;
	private final int chunkMinX;
	private final int chunkMinZ;
	private final int chunksX;
	private final int chunksZ;

	/** Next chunk to copy, in row order. */
	private int nextChunk = 0;

	/** Cells the prediction marked, for the log. */
	public int predicted = 0;

	/**
	 * An empty copy covering {@code radius} blocks around {@code at}
	 * across and {@code yBand} above and below.
	 */
	public ArrivalSnapshot(BlockPos at, int radius, int yBand) {
		this.minX = at.getX() - radius;
		this.minZ = at.getZ() - radius;
		this.minY = Math.max(0, at.getY() - yBand);
		int maxY = Math.min(127, at.getY() + yBand);
		this.sizeX = 2 * radius + 1;
		this.sizeZ = 2 * radius + 1;
		this.sizeY = maxY - minY + 1;
		this.states = new BlockState[sizeX * sizeY * sizeZ];
		this.future = new boolean[states.length];
		this.chunkMinX = minX >> 4;
		this.chunkMinZ = minZ >> 4;
		this.chunksX = ((minX + sizeX - 1) >> 4) - chunkMinX + 1;
		this.chunksZ = ((minZ + sizeZ - 1) >> 4) - chunkMinZ + 1;
		this.loadedChunk = new boolean[chunksX * chunksZ];
	}

	/**
	 * Copies chunks until {@code budgetNanos} has gone. True once every
	 * chunk has been visited. A chunk that is not loaded is skipped and
	 * reads as unloaded - it is never generated here, unless
	 * {@code generate}, which only the harness may ask for: generating
	 * on the server thread is the freeze this class exists to avoid.
	 */
	public boolean copySome(ServerWorld world, long budgetNanos, boolean generate) {
		long started = System.nanoTime();
		BlockPos.Mutable m = new BlockPos.Mutable();
		while (nextChunk < loadedChunk.length) {
			int i = nextChunk++;
			int cx = chunkMinX + i % chunksX;
			int cz = chunkMinZ + i / chunksX;
			if (generate || world.getChunkManager().isChunkLoaded(cx, cz)) {
				copyChunk(world.getChunk(cx, cz), cx, cz, m);
				loadedChunk[i] = true;
			}
			if (System.nanoTime() - started >= budgetNanos) {
				break;
			}
		}
		return nextChunk >= loadedChunk.length;
	}

	private void copyChunk(WorldChunk chunk, int cx, int cz, BlockPos.Mutable m) {
		int x0 = Math.max(minX, cx << 4);
		int x1 = Math.min(minX + sizeX - 1, (cx << 4) + 15);
		int z0 = Math.max(minZ, cz << 4);
		int z1 = Math.min(minZ + sizeZ - 1, (cz << 4) + 15);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				for (int y = minY; y < minY + sizeY; y++) {
					states[index(x, y, z)] = chunk.getBlockState(m.set(x, y, z));
				}
			}
		}
	}

	/** Whether the column at x,z was copied. */
	public boolean isLoaded(int x, int z) {
		if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ) {
			return false;
		}
		return loadedChunk[((z >> 4) - chunkMinZ) * chunksX + ((x >> 4) - chunkMinX)];
	}

	/**
	 * Marks every open cell lava will flow into from the lava already in
	 * the copy, by vanilla's rules at nether speed (FlowableFluid,
	 * LavaFluid): lava that can fall, falls, and a fall lands with full
	 * reach; lava that cannot fall - or a source - spreads sideways one
	 * level weaker per block, and only toward the nearest drop within
	 * four blocks when there is one, every way when there is not.
	 *
	 * The drop rule matters. Letting every cell spread every way marks
	 * whole floors vanilla never floods, and in a world that has already
	 * settled it flooded the very ground the runner stood on.
	 *
	 * Which directions a cell spreads in depends only on what is solid,
	 * and lava filling a cell never makes it solid, so levels only rise
	 * and this settles to one answer whatever order cells are visited in.
	 */
	public void predictLava() {
		int[] level = new int[states.length];
		boolean[] falling = new boolean[states.length];
		boolean[] source = new boolean[states.length];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		for (int i = 0; i < states.length; i++) {
			BlockState s = states[i];
			if (s == null || s.getMaterial() != Material.LAVA) {
				continue;
			}
			FluidState f = s.getFluidState();
			level[i] = f.getLevel();
			source[i] = f.isStill();
			falling[i] = !source[i] && f.get(FlowableFluid.FALLING);
			queue.add(i);
		}

		int layer = sizeX * sizeZ;
		while (!queue.isEmpty()) {
			int i = queue.poll();
			int y = i / layer;
			int below = y > 0 ? i - layer : -1;

			if (below >= 0 && flowsInto(below, source)) {
				if (!falling[below]) {
					falling[below] = true;
					level[below] = LAVA_REACH;
					mark(below);
					queue.add(below);
				}
				continue;
			}
			// Flowing lava over lava does not spread; a source does.
			if (!source[i] && below >= 0 && isLava(below)) {
				continue;
			}
			int spread = falling[i] ? LAVA_REACH - 1 : level[i] - 1;
			if (spread <= 0) {
				continue;
			}
			for (int n : spreadTargets(i, source)) {
				if (level[n] < spread) {
					level[n] = spread;
					mark(n);
					queue.add(n);
				}
			}
		}
	}

	/** Nether lava looks this far for a drop before spreading every way. */
	private static final int DROP_SEARCH = 4;

	/** FlowableFluid.getSpread: the sideways neighbours nearest a drop. */
	private int[] spreadTargets(int i, boolean[] source) {
		int[] out = new int[4];
		int count = 0;
		int bestDistance = 1000;
		for (int d = 0; d < 4; d++) {
			int n = neighbour(i, d);
			if (n < 0 || !flowsInto(n, source)) {
				continue;
			}
			int distance = dropBelow(n) ? 0 : distanceToDrop(n, 1, opposite(d), source);
			if (distance < bestDistance) {
				count = 0;
				bestDistance = distance;
			}
			if (distance == bestDistance) {
				out[count++] = n;
			}
		}
		return java.util.Arrays.copyOf(out, count);
	}

	private int distanceToDrop(int i, int distance, int from, boolean[] source) {
		int best = 1000;
		for (int d = 0; d < 4; d++) {
			if (d == from) {
				continue;
			}
			int n = neighbour(i, d);
			if (n < 0 || !flowsInto(n, source)) {
				continue;
			}
			if (dropBelow(n)) {
				return distance;
			}
			if (distance < DROP_SEARCH) {
				best = Math.min(best, distanceToDrop(n, distance + 1, opposite(d), source));
			}
		}
		return best;
	}

	/** Open below, or lava below: either way lava here would fall. */
	private boolean dropBelow(int i) {
		int below = i - sizeX * sizeZ;
		return below >= 0 && (fillable(below) || isLava(below));
	}

	/** Lava can move into the cell: open space, or flowing lava it can raise. */
	private boolean flowsInto(int i, boolean[] source) {
		return fillable(i) || (isLava(i) && !source[i]);
	}

	/** -x, +x, -z, +z; -1 off the edge of the copy. */
	private int neighbour(int i, int d) {
		int x = i % sizeX;
		int z = (i / sizeX) % sizeZ;
		switch (d) {
			case 0: return x > 0 ? i - 1 : -1;
			case 1: return x < sizeX - 1 ? i + 1 : -1;
			case 2: return z > 0 ? i - sizeX : -1;
			default: return z < sizeZ - 1 ? i + sizeX : -1;
		}
	}

	private static int opposite(int d) {
		return d ^ 1;
	}

	private boolean isLava(int i) {
		return future[i] || (states[i] != null && states[i].getMaterial() == Material.LAVA);
	}

	private void mark(int i) {
		if (!future[i] && fillable(i)) {
			future[i] = true;
			predicted++;
		}
	}

	/** Open space lava would flow into. A portal it cannot replace. */
	private boolean fillable(int i) {
		BlockState s = states[i];
		if (future[i] || s == null) {
			return false; // already lava, or not copied - and unknown does not flood
		}
		Material m = s.getMaterial();
		return !m.blocksMovement() && m != Material.LAVA && m != Material.WATER
				&& m != Material.PORTAL;
	}

	private int index(int x, int y, int z) {
		return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ
				|| y < minY || y >= minY + sizeY) {
			// Outside the copy: never something to walk into or mine.
			return Blocks.BEDROCK.getDefaultState();
		}
		int i = index(x, y, z);
		if (future[i]) {
			return Blocks.LAVA.getDefaultState();
		}
		BlockState s = states[i];
		return s == null ? Blocks.BEDROCK.getDefaultState() : s;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}
}
