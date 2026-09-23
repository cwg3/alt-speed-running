package com.speedrunmcalt.seed;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Is a seed's ruined portal a frame a runner can finish?
 *
 * This replaces the question PortalVerify was asking. That one counted
 * obsidian blocks and checked they were above ground and not
 * underwater - never that they formed a VERTICAL FRAME, and it counted
 * crying obsidian, which cannot form one. Every seed it passed was
 * unplayable; one reached a player as twelve obsidian lying flat on the
 * ground.
 *
 * The standard here is measured, not invented. Three ruined portal
 * worlds read out of an MCSR Ranked install, with the same seeds
 * regenerated unmodified for comparison - 0 blocks differ, so they ship
 * vanilla and filter for it - gave the same shape every time:
 *
 *     a vertical frame, 4 wide by 5 tall,
 *     built of real obsidian,
 *     exactly 2 non-corner blocks missing.
 *
 * Two missing is what the chest's obsidian floor covers, which is why
 * that is the acceptance limit here. Corners are ignored because a
 * portal lights without them.
 *
 * Measured pass rate on our own candidates: 9 of 40, 22%. Affordable -
 * the buried treasure filter runs at 3% and has never been questioned.
 */
public final class PortalFrame {
	/** A portal needs a 4x5 opening; bigger frames are fine. */
	private static final int MIN_WIDTH = 4;
	private static final int MIN_HEIGHT = 5;

	/** What the chest's obsidian floor can fill. See LootTopUp. */
	public static final int MAX_MISSING = 2;

	private PortalFrame() {
	}

	public static final class Result {
		public final boolean exists;
		public final int width;
		public final int height;
		/** Non-corner frame slots that are not real obsidian. */
		public final int missing;
		/** Crying obsidian sitting in frame slots - unfillable. */
		public final int cryingInFrame;
		public final boolean vertical;

		Result(boolean exists, int width, int height, int missing,
				int cryingInFrame, boolean vertical) {
			this.exists = exists;
			this.width = width;
			this.height = height;
			this.missing = missing;
			this.cryingInFrame = cryingInFrame;
			this.vertical = vertical;
		}

		/**
		 * Completable with the obsidian the chest guarantees.
		 *
		 * Crying obsidian in a frame slot fails outright rather than
		 * counting as a gap: clearing it needs a diamond pickaxe, which
		 * a runner does not have at the portal.
		 */
		public boolean usable() {
			return exists && vertical && cryingInFrame == 0 && missing <= MAX_MISSING;
		}

		@Override
		public String toString() {
			return "exists=" + exists + " " + width + "wx" + height + "h"
					+ " missing=" + missing + " cryingInFrame=" + cryingInFrame
					+ " vertical=" + vertical + " usable=" + usable();
		}
	}

	/** Analyses the largest obsidian frame plane inside the box. */
	public static Result check(ServerWorld world, BlockBox box) {
		Map<BlockPos, Boolean> obsidian = new HashMap<>();  // true = real
		for (int x = box.minX; x <= box.maxX; x++) {
			for (int y = box.minY; y <= box.maxY; y++) {
				for (int z = box.minZ; z <= box.maxZ; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					Block b = world.getBlockState(pos).getBlock();
					if (b == Blocks.OBSIDIAN) {
						obsidian.put(pos, Boolean.TRUE);
					} else if (b == Blocks.CRYING_OBSIDIAN) {
						obsidian.put(pos, Boolean.FALSE);
					}
				}
			}
		}
		if (obsidian.isEmpty()) {
			return new Result(false, 0, 0, 0, 0, false);
		}

		// A frame is a vertical plane, so it varies in exactly one of x
		// or z. Try both and keep whichever holds more blocks.
		Result best = new Result(true, 0, 0, Integer.MAX_VALUE, 0, false);
		for (int axis = 0; axis < 2; axis++) {
			Map<Integer, List<BlockPos>> planes = new HashMap<>();
			for (BlockPos p : obsidian.keySet()) {
				int key = axis == 0 ? p.getZ() : p.getX();
				planes.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
			}
			for (List<BlockPos> plane : planes.values()) {
				Result r = analyse(plane, obsidian, axis);
				if (r.usable() && !best.usable()) {
					best = r;
				} else if (r.usable() == best.usable()
						&& r.width * r.height > best.width * best.height) {
					best = r;
				}
			}
		}
		return best;
	}

	private static Result analyse(List<BlockPos> plane, Map<BlockPos, Boolean> obsidian, int axis) {
		Set<Integer> uSet = new HashSet<>();
		Set<Integer> ySet = new HashSet<>();
		for (BlockPos p : plane) {
			uSet.add(axis == 0 ? p.getX() : p.getZ());
			ySet.add(p.getY());
		}
		List<Integer> us = new ArrayList<>(uSet);
		List<Integer> ys = new ArrayList<>(ySet);
		java.util.Collections.sort(us);
		java.util.Collections.sort(ys);

		int width = us.size();
		int height = ys.size();
		boolean vertical = width >= MIN_WIDTH && height >= MIN_HEIGHT;

		int missing = 0;
		int cryingInFrame = 0;
		for (int u : us) {
			for (int y : ys) {
				boolean edgeU = u == us.get(0) || u == us.get(us.size() - 1);
				boolean edgeY = y == ys.get(0) || y == ys.get(ys.size() - 1);
				if (edgeU == edgeY) {
					continue;   // interior, or a corner - neither is required
				}
				BlockPos at = null;
				for (BlockPos p : plane) {
					int pu = axis == 0 ? p.getX() : p.getZ();
					if (pu == u && p.getY() == y) {
						at = p;
						break;
					}
				}
				if (at == null) {
					missing++;
				} else if (!Boolean.TRUE.equals(obsidian.get(at))) {
					cryingInFrame++;
					missing++;
				}
			}
		}
		return new Result(true, width, height, missing, cryingInFrame, vertical);
	}
}
