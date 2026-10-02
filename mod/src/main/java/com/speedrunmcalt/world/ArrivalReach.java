package com.speedrunmcalt.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much work it takes to walk OUT of a nether arrival.
 *
 * The first arrival check counted standable ground in a 17x17 square,
 * which answers "is there floor here" and nothing else. Both arrivals
 * reported unplayable in play on 2026-10-01 got past it or through it:
 *
 *   - buried: sealed in netherrack. It scored 0 and got a pad, but a pad
 *     only fills lava and air, so it changed nothing.
 *   - dead end: 65 of 289 columns standable, a pass. The runner walked
 *     some 60 blocks of it and came back - the ground was there, it just
 *     did not lead anywhere.
 *
 * Neither is about how much floor there is. Both are about whether that
 * floor CONNECTS to the rest of the nether. So this searches outward
 * from where the player stands, the way a runner moves, and reports for
 * each ring distance the fewest blocks that have to be mined or placed
 * to get that far. A good arrival walks every ring for 0.
 *
 * Movement is a runner's, roughly: one block up with headroom, drops of
 * up to MAX_DROP, and two edits - mine a block in the way, or place one
 * to bridge a level gap. It deliberately never mines next to lava (that
 * floods the tunnel) or under a falling block, and never touches
 * anything obsidian-hard, so the cost it reports is one a player could
 * actually pay.
 *
 * It only reads blocks. Callers decide what a cost means.
 */
public final class ArrivalReach {
	/** Longest fall counted as walking. Costs hearts, not time. */
	private static final int MAX_DROP = 6;

	/** How far above and below the arrival the search may wander. */
	private static final int Y_BAND = 32;

	/** Hardness at or above which a block is not worth mining (obsidian is 50). */
	private static final float UNMINEABLE = 50.0f;

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	/** Answers whether a column can be read without generating it. */
	public interface Loaded {
		boolean test(int x, int z);
	}

	/** The search result. */
	public static final class Result {
		/** The rings asked about, in blocks from the arrival. */
		public final int[] rings;
		/** Fewest edits to reach each ring; -1 if not reached within maxCost. */
		public final int[] cost;
		/** True if the search ran into a column that was not loaded. */
		public final boolean hitUnloaded;
		/** Positions visited, for the log. */
		public final int visited;
		/** Edits along the cheapest path to the outermost ring reached, start first. */
		public final List<Edit> path;

		Result(int[] rings, int[] cost, boolean hitUnloaded, int visited, List<Edit> path) {
			this.rings = rings;
			this.cost = cost;
			this.hitUnloaded = hitUnloaded;
			this.visited = visited;
			this.path = path;
		}

		/** Edits needed to reach the outermost ring, or -1. */
		public int outer() {
			return cost[cost.length - 1];
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < rings.length; i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(rings[i]).append('=').append(cost[i] < 0 ? "x" : String.valueOf(cost[i]));
			}
			return sb.toString();
		}
	}

	/** One block the cheapest path changes. */
	public static final class Edit {
		public final BlockPos pos;
		/** True to place a floor block, false to mine one out. */
		public final boolean place;

		Edit(BlockPos pos, boolean place) {
			this.pos = pos;
			this.place = place;
		}
	}

	private ArrivalReach() {
	}

	/**
	 * Fewest edits from {@code start} (the player's feet) to each ring.
	 *
	 * Dial's algorithm: edge costs are small integers, walking is free,
	 * so the buckets are popped in cost order and the first position
	 * past a ring prices that ring exactly.
	 */
	public static Result measure(BlockView world, BlockPos start, int[] rings,
			int maxCost, Loaded loaded) {
		int outerRing = rings[rings.length - 1];
		int[] cost = new int[rings.length];
		java.util.Arrays.fill(cost, -1);
		int ringsLeft = rings.length;

		int minY = Math.max(1, start.getY() - Y_BAND);
		int maxY = Math.min(125, start.getY() + Y_BAND);
		int sx = start.getX();
		int sz = start.getZ();

		Map<Long, Integer> best = new HashMap<>();
		// Back-pointer per position: the position it was reached from.
		Map<Long, Long> from = new HashMap<>();
		List<ArrayDeque<Long>> buckets = new ArrayList<>();
		for (int i = 0; i <= maxCost; i++) {
			buckets.add(new ArrayDeque<>());
		}

		long startKey = start.asLong();
		best.put(startKey, 0);
		buckets.get(0).add(startKey);

		boolean hitUnloaded = false;
		long farthestKey = startKey;
		BlockPos.Mutable c = new BlockPos.Mutable();

		for (int k = 0; k <= maxCost && ringsLeft > 0; k++) {
			ArrayDeque<Long> bucket = buckets.get(k);
			while (!bucket.isEmpty() && ringsLeft > 0) {
				long key = bucket.poll();
				if (best.get(key) != k) {
					continue; // stale entry, already reached cheaper
				}
				int x = BlockPos.unpackLongX(key);
				int y = BlockPos.unpackLongY(key);
				int z = BlockPos.unpackLongZ(key);

				double d = Math.hypot(x - sx, z - sz);
				for (int r = 0; r < rings.length; r++) {
					if (cost[r] < 0 && d >= rings[r]) {
						cost[r] = k;
						ringsLeft--;
						farthestKey = key;
					}
				}
				if (ringsLeft == 0) {
					break;
				}

				for (int[] dir : DIRS) {
					int nx = x + dir[0];
					int nz = z + dir[1];
					if (Math.hypot(nx - sx, nz - sz) > outerRing + 1) {
						continue;
					}
					if (!loaded.test(nx, nz)) {
						hitUnloaded = true;
						continue;
					}
					for (int ny = y + 1; ny >= y - MAX_DROP; ny--) {
						if (ny < minY || ny > maxY) {
							continue;
						}
						int step = stepCost(world, c, x, y, z, nx, ny, nz);
						if (step < 0) {
							continue;
						}
						int nk = k + step;
						if (nk > maxCost) {
							continue;
						}
						long nkey = BlockPos.asLong(nx, ny, nz);
						Integer prev = best.get(nkey);
						if (prev == null || nk < prev) {
							best.put(nkey, nk);
							from.put(nkey, key);
							buckets.get(nk).add(nkey);
						}
					}
				}
			}
		}

		List<Edit> path = new ArrayList<>();
		if (farthestKey != startKey) {
			List<Long> chain = new ArrayList<>();
			for (Long at = farthestKey; at != null; at = from.get(at)) {
				chain.add(0, at);
			}
			for (int i = 1; i < chain.size(); i++) {
				collectEdits(world, c, chain.get(i - 1), chain.get(i), path);
			}
		}
		return new Result(rings, cost, hitUnloaded, best.size(), path);
	}

	/**
	 * Cost of moving feet from (x,y,z) to (nx,ny,nz): number of blocks to
	 * mine plus blocks to place, or -1 if the move is not one a runner
	 * can make.
	 */
	private static int stepCost(BlockView w, BlockPos.Mutable c,
			int x, int y, int z, int nx, int ny, int nz) {
		int edits = 0;

		// Clear space the body passes through. Stepping up needs headroom
		// over the CURRENT column; dropping needs the target column clear
		// from head height all the way down.
		if (ny > y) {
			int e = clearCost(w, c, x, y + 2, z);
			if (e < 0) {
				return -1;
			}
			edits += e;
		}
		int top = Math.max(ny + 1, y + 1);
		for (int yy = ny; yy <= top; yy++) {
			int e = clearCost(w, c, nx, yy, nz);
			if (e < 0) {
				return -1;
			}
			edits += e;
		}

		// Something to stand on.
		BlockState floor = w.getBlockState(c.set(nx, ny - 1, nz));
		if (floor.getMaterial() == Material.LAVA || !floor.getMaterial().blocksMovement()) {
			// Bridging only on the level - a runner places a block
			// in front of them, not at the bottom of a drop.
			if (ny != y) {
				return -1;
			}
			edits += 1;
		}
		return edits;
	}

	/** 0 if the cell is open, 1 if it can be mined out, -1 if not. */
	private static int clearCost(BlockView w, BlockPos.Mutable c, int x, int y, int z) {
		BlockState s = w.getBlockState(c.set(x, y, z));
		Material m = s.getMaterial();
		if (m == Material.LAVA) {
			return -1;
		}
		if (!m.blocksMovement()) {
			return 0;
		}
		float hardness = s.getHardness(w, c);
		if (hardness < 0 || hardness >= UNMINEABLE) {
			return -1;
		}
		// Mining next to lava lets it into the tunnel.
		for (int[] o : new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
			if (w.getBlockState(c.set(x + o[0], y + o[1], z + o[2])).getMaterial() == Material.LAVA) {
				c.set(x, y, z);
				return -1;
			}
		}
		// Gravel or sand overhead would fall into the gap.
		if (w.getBlockState(c.set(x, y + 1, z)).getBlock() instanceof FallingBlock) {
			c.set(x, y, z);
			return -1;
		}
		c.set(x, y, z);
		return 1;
	}

	/** Replays one step and records which blocks it changes. */
	private static void collectEdits(BlockView w, BlockPos.Mutable c, long fromKey, long toKey,
			List<Edit> out) {
		int x = BlockPos.unpackLongX(fromKey);
		int y = BlockPos.unpackLongY(fromKey);
		int z = BlockPos.unpackLongZ(fromKey);
		int nx = BlockPos.unpackLongX(toKey);
		int ny = BlockPos.unpackLongY(toKey);
		int nz = BlockPos.unpackLongZ(toKey);

		if (ny > y && clearCost(w, c, x, y + 2, z) == 1) {
			out.add(new Edit(new BlockPos(x, y + 2, z), false));
		}
		int top = Math.max(ny + 1, y + 1);
		for (int yy = ny; yy <= top; yy++) {
			if (clearCost(w, c, nx, yy, nz) == 1) {
				out.add(new Edit(new BlockPos(nx, yy, nz), false));
			}
		}
		BlockState floor = w.getBlockState(c.set(nx, ny - 1, nz));
		if (floor.getMaterial() == Material.LAVA || !floor.getMaterial().blocksMovement()) {
			out.add(new Edit(new BlockPos(nx, ny - 1, nz), true));
		}
	}
}
