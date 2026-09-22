package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A mirrored, pity-capped sequence of yes/no drops.
 *
 * Same idea as {@link BarterSchedule}, for the other RNG that decides
 * matches. Blaze rods are the clearest case: a flat 50% per kill means
 * one runner can have seven rods from ten blazes while the other has
 * three, and that gap is often the match. Vanilla offers no floor at
 * all - a dozen blazes can yield nothing.
 *
 * Two properties, both from the match seed:
 *
 *   mirrored  - both players get the same outcome on the same kill
 *               number, so neither is ahead on luck
 *   pity      - each cycle contains a fixed number of drops, and no
 *               run of misses longer than MAX_MISS_STREAK, so a
 *               drought cannot end a run
 *
 * The rate is unchanged from vanilla. Six drops per twelve kills is
 * the same 50%; what goes away is the variance around it.
 */
public final class DropSchedule {
	/** Kills per cycle. */
	private static final int BLAZE_CYCLE = 12;

	/** Drops per cycle - vanilla's 50%, without the variance. */
	private static final int BLAZE_DROPS = 6;

	/**
	 * Longest run of misses allowed.
	 *
	 * An exact count per cycle still permits all six misses in a row if
	 * the shuffle is unkind, which is the drought the pity is meant to
	 * remove. Capping the streak is what makes the guarantee felt
	 * rather than merely true on average.
	 */
	private static final int MAX_MISS_STREAK = 2;

	private static final long BLAZE_SALT = 0xB1A2E20DL;

	/**
	 * Hoglin porkchops per kill, and hides.
	 *
	 * Food is the constraint that decides whether a runner can keep
	 * moving, and vanilla hands it out unevenly: one player can leave a
	 * bastion fed and the other hungry off the same number of kills.
	 * Same fix as rods - the per-kill count varies, the total over a
	 * cycle does not, and both players get the identical sequence.
	 *
	 * The cycle averages 3 per kill, which sits in the middle of
	 * vanilla's range rather than being generous.
	 */
	private static final int HOGLIN_CYCLE = 8;
	private static final int HOGLIN_TOTAL = 24;
	private static final int HOGLIN_MIN = 2;
	private static final int HOGLIN_MAX = 4;
	private static final long HOGLIN_SALT = 0x409117ADL;

	/** Hides per cycle. Vanilla is 0-1 per kill; this keeps the mean. */
	private static final int HOGLIN_HIDES = 4;

	/**
	 * Flint from gravel.
	 *
	 * Vanilla is a flat 10% with no floor, and a runner needs exactly
	 * one piece for flint and steel. That makes it a coin flip with a
	 * long tail: one player takes flint off the first block while the
	 * other breaks fifteen and has none, on the same seed, for the same
	 * work. Two in twenty keeps vanilla's rate exactly.
	 *
	 * The streak cap is what does the real work here. Nine misses
	 * maximum means flint arrives within ten breaks, always - which is
	 * the difference between an unlucky minute and a lost run.
	 *
	 * Vanilla's fortune bonus is not modelled. A runner breaking gravel
	 * for their first flint has no fortune shovel, so it never applies
	 * to the case this exists for.
	 */
	private static final int FLINT_CYCLE = 20;
	private static final int FLINT_DROPS = 2;
	private static final int FLINT_MAX_MISS_STREAK = 9;
	private static final long FLINT_SALT = 0xF11A7L;

	private static volatile DropSchedule blazeRods;
	private static volatile DropSchedule flint;
	private static volatile int[] hoglinPorkchops;
	private static volatile boolean[] hoglinHides;
	private static volatile int hoglinIndex = 0;

	private final boolean[] cycle;
	private int index = 0;

	private DropSchedule(boolean[] cycle) {
		this.cycle = cycle;
	}

	public static void reset() {
		blazeRods = null;
		flint = null;
		hoglinPorkchops = null;
		hoglinHides = null;
		hoglinIndex = 0;
	}

	/** One hoglin's drop, as {porkchops, hides}. Advances the queue. */
	public static synchronized int[] nextHoglinDrop(long matchSeed) {
		if (hoglinPorkchops == null) {
			Random random = new Random(matchSeed ^ HOGLIN_SALT);
			hoglinPorkchops = buildCounts(random, HOGLIN_CYCLE, HOGLIN_TOTAL,
					HOGLIN_MIN, HOGLIN_MAX);
			hoglinHides = build(matchSeed ^ HOGLIN_SALT ^ 0x81DEL,
					HOGLIN_CYCLE, HOGLIN_HIDES).cycle;
			int sum = 0;
			for (int n : hoglinPorkchops) {
				sum += n;
			}
			// Counted from what was produced, not what was intended -
			// these two have diverged before.
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Hoglin schedule: {} porkchops and {} hides per {} kills",
					sum, HOGLIN_HIDES, HOGLIN_CYCLE);
		}
		int i = hoglinIndex % HOGLIN_CYCLE;
		hoglinIndex++;
		return new int[] { hoglinPorkchops[i], hoglinHides[i] ? 1 : 0 };
	}

	/**
	 * A cycle of counts in [min,max] summing to exactly total.
	 *
	 * Starts everything at min and distributes the remainder one at a
	 * time over randomly chosen slots, so the total is exact by
	 * construction rather than by rejection sampling - which would make
	 * the number of random draws depend on luck, and the schedule is
	 * required to be identical on both machines.
	 */
	private static int[] buildCounts(Random random, int length, int total, int min, int max) {
		int[] counts = new int[length];
		java.util.Arrays.fill(counts, min);
		int remaining = total - min * length;

		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < length; i++) {
			slots.add(i);
		}
		while (remaining > 0) {
			Collections.shuffle(slots, random);
			boolean placed = false;
			for (int slot : slots) {
				if (remaining == 0) {
					break;
				}
				if (counts[slot] < max) {
					counts[slot]++;
					remaining--;
					placed = true;
				}
			}
			if (!placed) {
				// Every slot is at max: the total asked for is more than
				// length*max. Caller's constants are wrong; stop rather
				// than spin.
				SpeedrunMcAlt.LOGGER.warn(
						"[speedrunmcalt] Count schedule cannot reach {} with {} slots capped at {}",
						total, length, max);
				break;
			}
		}
		return counts;
	}

	public static synchronized DropSchedule blazeRods(long matchSeed) {
		if (blazeRods == null) {
			blazeRods = build(matchSeed ^ BLAZE_SALT, BLAZE_CYCLE, BLAZE_DROPS);
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Blaze rod schedule: {} drops per {} kills",
					BLAZE_DROPS, BLAZE_CYCLE);
		}
		return blazeRods;
	}

	public static synchronized DropSchedule flint(long matchSeed) {
		if (flint == null) {
			flint = build(matchSeed ^ FLINT_SALT, FLINT_CYCLE, FLINT_DROPS,
					FLINT_MAX_MISS_STREAK);
			// Counted from what was built, not what was asked for: the
			// builder falls back when a streak cap is unreachable, and a
			// fallback that changed the rate is exactly the bug this
			// logging exists to catch.
			int actual = 0;
			for (boolean slot : flint.cycle) {
				if (slot) {
					actual++;
				}
			}
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Flint schedule: {} per {} gravel breaks (max {} misses in a row)",
					actual, FLINT_CYCLE, FLINT_MAX_MISS_STREAK);
		}
		return flint;
	}

	/** Whether this kill drops. Advances the queue. */
	public synchronized boolean next() {
		boolean drop = cycle[index % cycle.length];
		index++;
		return drop;
	}

	private static DropSchedule build(long seed, int cycleLength, int drops) {
		return build(seed, cycleLength, drops, MAX_MISS_STREAK);
	}

	/**
	 * @param maxMissStreak longest run of misses allowed. MUST be
	 *                      achievable: a cap of 2 with 2 drops in 20
	 *                      slots is impossible, and the old code had
	 *                      that constant hardcoded - a low-rate
	 *                      schedule would exhaust its attempts and fall
	 *                      through to an alternating pattern, quietly
	 *                      turning a 10% drop into a 50% one.
	 */
	private static DropSchedule build(long seed, int cycleLength, int drops, int maxMissStreak) {
		Random random = new Random(seed);

		// Reshuffle until the streak cap holds. With six drops in
		// twelve this succeeds almost immediately; the bound is only
		// there so a pathological seed cannot spin forever.
		for (int attempt = 0; attempt < 64; attempt++) {
			List<Boolean> slots = new ArrayList<>(cycleLength);
			for (int i = 0; i < cycleLength; i++) {
				slots.add(i < drops);
			}
			Collections.shuffle(slots, random);

			int streak = 0;
			boolean ok = true;
			for (boolean slot : slots) {
				streak = slot ? 0 : streak + 1;
				if (streak > maxMissStreak) {
					ok = false;
					break;
				}
			}
			if (ok) {
				boolean[] cycle = new boolean[cycleLength];
				for (int i = 0; i < cycleLength; i++) {
					cycle[i] = slots.get(i);
				}
				return new DropSchedule(cycle);
			}
		}

		// Fall back to drops spread as evenly as the cycle allows.
		//
		// This used to alternate, which satisfies the streak cap and
		// silently DESTROYS the drop count: a schedule asking for 2
		// drops in 20 would have produced 10, turning a 10% rate into
		// 50%. The count is the guarantee - it is what both players are
		// promised - so the fallback preserves it exactly and merely
		// gives up on randomness.
		boolean[] cycle = new boolean[cycleLength];
		for (int i = 0; i < drops; i++) {
			cycle[(int) ((long) i * cycleLength / Math.max(1, drops))] = true;
		}
		SpeedrunMcAlt.LOGGER.warn(
				"[speedrunmcalt] Drop schedule fell back to even spacing ({} in {}, streak cap {})",
				drops, cycleLength, maxMissStreak);
		return new DropSchedule(cycle);
	}
}
