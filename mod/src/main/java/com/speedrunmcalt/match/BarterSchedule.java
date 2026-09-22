package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
import net.minecraft.loot.context.LootContext;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The barter outcomes for a match, fixed in advance and identical for
 * both players.
 *
 * Two separate problems, and vanilla bartering has both:
 *
 * 1. LUCK BETWEEN PLAYERS. Pearls are about a 2% chance per ingot, so
 *    one player can have pearls by their fourth trade and the other
 *    still be trading at forty. That decides matches on RNG rather
 *    than skill, which is the opposite of what a ladder is for. The
 *    schedule is derived from the match seed alone, so both players
 *    trade down the same queue and get the same outcome in the same
 *    order.
 *
 * 2. LUCK WITHIN A RUN. Even mirrored, an unlucky sequence can leave
 *    both players unable to finish. So every 72 barters - exactly 8
 *    gold blocks - guarantees 3 pearl trades and at least 6 obsidian.
 *    A runner who mines and trades 8 blocks is mathematically certain
 *    to have enough to finish.
 *
 * This replaces an earlier policy of "force something if a 20-barter
 * window produced nothing". That was a safety net and nothing more: it
 * never addressed the discrepancy BETWEEN players, which is the larger
 * unfairness of the two.
 *
 * Everything outside the guaranteed slots is still rolled from the
 * vanilla bartering table, so the texture of trading is unchanged -
 * it is the distribution that is pinned, not the contents.
 */
public final class BarterSchedule {
	/** Exactly 8 gold blocks' worth of ingots. */
	public static final int CYCLE = 72;

	/** Pearl trades per cycle - exactly, never more. */
	private static final int PEARL_TRADES = 3;

	/**
	 * Obsidian trades per cycle.
	 *
	 * The floor is 6, and the target is vanilla's rate lifted by the
	 * documented ~35%: 72 barters at 8.53% is about 6.1 trades, so the
	 * buffed figure is about 8.
	 *
	 * Fixing the count explicitly matters, because the obvious
	 * implementation drifts high. Forcing 6 slots and letting the other
	 * 63 roll vanilla adds ~5.4 more, landing near 11 trades a cycle -
	 * comfortably past the standard rather than short of it, which is
	 * its own kind of wrong on a ladder meant to be comparable. So
	 * obsidian is placed deliberately, like pearls, and excluded from
	 * the free rolls.
	 */
	private static final int OBSIDIAN_MIN = 6;
	private static final double VANILLA_OBSIDIAN_RATE = 0.0853;
	private static final double OBSIDIAN_BUFF = 1.35;

	/** Vanilla gives 4-8 pearls on a pearl trade. */
	private static final int PEARLS_MIN = 4;
	private static final int PEARLS_MAX = 8;

	private static final long SALT = 0xBA27E12L;

	private static volatile BarterSchedule active;

	private final List<List<ItemStack>> queue = new ArrayList<>();
	private int index = 0;

	private BarterSchedule() {
	}

	/**
	 * Discards any existing schedule. Called when a match starts so a
	 * second match in the same session cannot inherit the first one's
	 * queue position.
	 */
	public static void reset() {
		active = null;
	}

	/**
	 * Builds on first use, from the match seed only.
	 *
	 * @param barterer the piglin being traded with. Only ever used as
	 *                 loot-context furniture - the vanilla bartering
	 *                 table is a flat weighted pool with no conditions
	 *                 that read the entity - so the schedule stays a
	 *                 function of the match seed alone.
	 */
	public static synchronized BarterSchedule get(ServerWorld world, long matchSeed,
			Entity barterer) {
		if (active == null) {
			active = build(world, matchSeed, barterer);
		}
		return active;
	}

	/** The next barter's contents. Cycles forever. */
	public synchronized List<ItemStack> next() {
		if (queue.isEmpty()) {
			return Collections.emptyList();
		}
		List<ItemStack> outcome = queue.get(index % queue.size());
		index++;
		List<ItemStack> copy = new ArrayList<>(outcome.size());
		for (ItemStack stack : outcome) {
			copy.add(stack.copy());
		}
		return copy;
	}

	private static BarterSchedule build(ServerWorld world, long matchSeed, Entity barterer) {
		BarterSchedule schedule = new BarterSchedule();
		Random random = new Random(matchSeed ^ SALT);

		// Pick the guaranteed slots first, so the cycle's counts are
		// exact regardless of what the vanilla rolls produce.
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < CYCLE; i++) {
			slots.add(i);
		}
		Collections.shuffle(slots, random);

		int obsidianTrades = Math.max(OBSIDIAN_MIN,
				(int) Math.round(CYCLE * VANILLA_OBSIDIAN_RATE * OBSIDIAN_BUFF));

		boolean[] pearlSlot = new boolean[CYCLE];
		boolean[] obsidianSlot = new boolean[CYCLE];
		for (int i = 0; i < PEARL_TRADES; i++) {
			pearlSlot[slots.get(i)] = true;
		}
		for (int i = PEARL_TRADES; i < PEARL_TRADES + obsidianTrades; i++) {
			obsidianSlot[slots.get(i)] = true;
		}

		LootTable table = world.getServer().getLootManager()
				.getTable(LootTables.PIGLIN_BARTERING_GAMEPLAY);

		for (int i = 0; i < CYCLE; i++) {
			List<ItemStack> outcome = new ArrayList<>();
			if (pearlSlot[i]) {
				int count = PEARLS_MIN + random.nextInt(PEARLS_MAX - PEARLS_MIN + 1);
				outcome.add(new ItemStack(Items.ENDER_PEARL, count));
			} else if (obsidianSlot[i]) {
				outcome.add(new ItemStack(Items.OBSIDIAN, 1));
			} else {
				// Vanilla roll, but never pearls or obsidian: both are
				// placed deliberately above, and a natural extra would
				// push the cycle past what it promises.
				outcome.addAll(rollPlain(world, table, random, barterer));
			}
			schedule.queue.add(outcome);
		}

		// Count what was actually produced rather than what was
		// intended - the two have diverged here once already.
		int pearls = 0;
		int obsidian = 0;
		for (List<ItemStack> outcome : schedule.queue) {
			for (ItemStack stack : outcome) {
				if (stack.getItem() == Items.ENDER_PEARL) {
					pearls++;
				} else if (stack.getItem() == Items.OBSIDIAN) {
					obsidian++;
				}
			}
		}
		SpeedrunMcAlt.LOGGER.info(
				"[speedrunmcalt] Barter schedule: {} barters, {} pearl trades, {} obsidian trades",
				CYCLE, pearls, obsidian);
		return schedule;
	}

	private static List<ItemStack> rollPlain(ServerWorld world, LootTable table, Random random,
			Entity barterer) {
		for (int attempt = 0; attempt < 16; attempt++) {
			// THIS_ENTITY and nothing else. LootContextTypes.BARTER
			// permits exactly that one parameter, and Builder.build()
			// rejects any other outright - passing POSITION here threw
			// IllegalArgumentException on the server thread the first
			// time a player ever completed a trade, six live runs after
			// the code was written. The validation is a runtime check
			// against the context type's parameter set, so nothing about
			// it is visible at compile time.
			LootContext context = new LootContext.Builder(world)
					.parameter(LootContextParameters.THIS_ENTITY, barterer)
					.random(random.nextLong())
					.build(LootContextTypes.BARTER);
			List<ItemStack> rolled = table.generateLoot(context);

			boolean reserved = false;
			for (ItemStack stack : rolled) {
				if (stack.getItem() == Items.ENDER_PEARL
						|| stack.getItem() == Items.OBSIDIAN) {
					reserved = true;
					break;
				}
			}
			if (!reserved) {
				return rolled;
			}
		}
		// Sixteen reserved rolls in a row should not happen; fall back
		// to something harmless rather than looping.
		return Collections.singletonList(new ItemStack(Items.GRAVEL, 1));
	}
}
