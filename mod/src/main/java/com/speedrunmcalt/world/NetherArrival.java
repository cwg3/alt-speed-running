package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.Comparator;

/**
 * Makes sure a nether arrival has a way out.
 *
 * Reported from play: "really bad nether spawn - no terrain". A portal
 * can link into the middle of a lava sea, into a sealed pocket of
 * netherrack, or onto ground that goes nowhere, and the runner who draws
 * that has no route while their opponent, arriving elsewhere on the same
 * seed, walks away normally.
 *
 * The first version of this counted standable ground in a 17x17 square
 * and built a netherrack pad when there was almost none. On 2026-10-01
 * it fired twice in play and missed both arrivals the player reported:
 * a buried one, where it fired but the pad - which only fills lava and
 * air - changed nothing, and a dead end that passed with a fifth of the
 * square standable, because the ground was there and simply did not lead
 * anywhere. How much floor there is was the wrong question.
 *
 * So this asks the right one: what does it take to WALK OUT? ArrivalReach
 * searches outward the way a runner moves and prices each ring distance
 * in blocks mined or placed. When reaching the outer ring costs more than
 * TOLERANCE, this makes exactly the edits on that cheapest path - mines
 * the tunnel, places the bridge - and nothing else.
 *
 * Calibrated on the ten nether arrivals played on 2026-10-01, priced in a
 * harness world built the way a match world is (see
 * HarnessNetherSeedMixin; a one-seed world got these badly wrong). Every
 * arrival the player walked away from cost at most 4. The dead end cost
 * 20, the buried arrival 42, and a small lava island - which the player
 * bridged off in about a minute and did not report - 36. No line
 * separates the island from the dead end, so the island is caught too;
 * what it gets is the bridge it needed.
 *
 * THREE THINGS TO BE PLAIN ABOUT.
 *
 * First, this is a PLACEMENT, so the world stops being pure vanilla along
 * that path. It is listed in the spec with everything else we add.
 *
 * Second, it breaks the usual "both players get byte-identical worlds"
 * property, because it fires where a player actually arrives and the two
 * of them build portals in different places. That is the same property
 * vanilla's own portal platform already has - going somewhere changes the
 * world there - and the alternative is worse: a guarantee that only helps
 * the player whose portal happened to link somewhere nice is not a
 * guarantee at all.
 *
 * Third, it is not instant. The terrain 96 blocks out does not exist when
 * the player steps through, and generating it on the spot would freeze
 * the game. It is requested in the background and the check runs once it
 * has loaded, normally a few seconds in. If it never all loads, the check
 * runs on what has, and treats the rest as open.
 */
public final class NetherArrival {
	/** Ring distances priced, in blocks from the arrival. The last one decides. */
	private static final int[] RINGS = {16, 32, 48, 64, 96};

	/** Most edits to reach the outer ring that still counts as runnable. */
	private static final int TOLERANCE = 10;

	/** Search gives up past this many edits. */
	private static final int MAX_COST = 60;

	/** Chunk radius requested around the arrival: covers the outer ring. */
	private static final int LOAD_RADIUS = 7;

	/** Longest wait for that terrain before checking what has loaded. */
	private static final int MAX_WAIT_TICKS = 200;

	/** Keeps the arrival's terrain loading; expires on its own. */
	private static final ChunkTicketType<ChunkPos> TICKET =
			ChunkTicketType.create("speedrunmcalt_arrival",
					Comparator.comparingLong(ChunkPos::toLong), MAX_WAIT_TICKS + 100);

	/** Radius of the pad built when no way out is found at all. */
	private static final int PAD_RADIUS = 5;

	private NetherArrival() {
	}

	/**
	 * Called every server tick while the check has not run. The first
	 * call records where the player arrived and asks for the terrain
	 * around it; later calls run the check once it is there.
	 */
	public static void tick(ServerWorld nether, BlockPos playerPos) {
		if (MatchState.netherArrivalAt == null) {
			MatchState.netherArrivalAt = playerPos;
			ChunkPos chunk = new ChunkPos(playerPos);
			nether.getChunkManager().addTicket(TICKET, chunk, LOAD_RADIUS, chunk);
			return;
		}

		BlockPos at = MatchState.netherArrivalAt;
		int waited = ++MatchState.netherArrivalWaited;
		boolean loaded = allLoaded(nether, at);
		if (!loaded && waited < MAX_WAIT_TICKS) {
			return;
		}
		MatchState.netherArrivalChecked = true;
		check(nether, at, waited);
	}

	private static boolean allLoaded(ServerWorld nether, BlockPos at) {
		int cx = at.getX() >> 4;
		int cz = at.getZ() >> 4;
		int r = (RINGS[RINGS.length - 1] >> 4) + 1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (!nether.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) {
					return false;
				}
			}
		}
		return true;
	}

	/** Runs the check now, on whatever has loaded. Public for the harness. */
	public static void check(ServerWorld nether, BlockPos at, int waited) {
		long started = System.currentTimeMillis();
		// An unloaded column stops the search like a wall would. If the
		// verdict then comes out bad, it may be the missing terrain
		// talking, so the world is left alone - unknown is not unrunnable.
		ArrivalReach.Result r = ArrivalReach.measure(nether, at, RINGS, MAX_COST,
				(x, z) -> nether.getChunkManager().isChunkLoaded(x >> 4, z >> 4));
		long took = System.currentTimeMillis() - started;

		int outer = r.outer();
		boolean unknown = r.hitUnloaded && (outer < 0 || outer > TOLERANCE);
		if ((outer >= 0 && outer <= TOLERANCE) || unknown) {
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Nether arrival at {},{},{} is runnable ({}; {} ms, after {} ticks{})",
					at.getX(), at.getY(), at.getZ(), r, took, waited,
					unknown ? ", terrain not all loaded - left alone" : "");
			return;
		}

		if (r.path.isEmpty()) {
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Nether arrival at {},{},{} has no way out within {} edits ({}) - building a pad",
					at.getX(), at.getY(), at.getZ(), MAX_COST, r);
			buildPad(nether, at);
			return;
		}

		int mined = 0;
		int placed = 0;
		for (ArrivalReach.Edit e : r.path) {
			if (e.place) {
				nether.setBlockState(e.pos, Blocks.NETHERRACK.getDefaultState(), 3);
				placed++;
			} else {
				nether.setBlockState(e.pos, Blocks.AIR.getDefaultState(), 3);
				mined++;
			}
		}
		SpeedrunMcAlt.LOGGER.warn(
				"[speedrunmcalt] Nether arrival at {},{},{} was unrunnable ({}) - opened a way out: "
						+ "{} mined, {} placed ({} ms)",
				at.getX(), at.getY(), at.getZ(), r, mined, placed, took);
	}

	/**
	 * A netherrack disc under the arrival, with headroom cleared. Only
	 * for an arrival the search found no way out of at all.
	 *
	 * Netherrack rather than obsidian: it is what the nether is made
	 * of, it mines instantly, and a runner can dig through it or pillar
	 * off it exactly as they would off natural ground. An obsidian slab
	 * would be a permanent unmineable scar.
	 */
	private static void buildPad(ServerWorld nether, BlockPos at) {
		BlockPos.Mutable cursor = new BlockPos.Mutable();
		int floorY = Math.max(1, at.getY() - 1);

		for (int dx = -PAD_RADIUS; dx <= PAD_RADIUS; dx++) {
			for (int dz = -PAD_RADIUS; dz <= PAD_RADIUS; dz++) {
				if (dx * dx + dz * dz > PAD_RADIUS * PAD_RADIUS) {
					continue; // round, not square - it reads as terrain
				}
				cursor.set(at.getX() + dx, floorY, at.getZ() + dz);
				BlockState existing = nether.getBlockState(cursor);
				// Replace lava and air; leave real ground alone.
				if (!existing.getMaterial().isSolid()
						|| existing.getBlock() == Blocks.LAVA) {
					nether.setBlockState(cursor, Blocks.NETHERRACK.getDefaultState(), 2);
				}
				// Clear somewhere to stand.
				for (int dy = 1; dy <= 3; dy++) {
					cursor.set(at.getX() + dx, floorY + dy, at.getZ() + dz);
					Block block = nether.getBlockState(cursor).getBlock();
					if (block == Blocks.LAVA) {
						nether.setBlockState(cursor, Blocks.AIR.getDefaultState(), 2);
					}
				}
			}
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Built nether arrival pad at {},{},{}",
				at.getX(), floorY, at.getZ());
	}
}
