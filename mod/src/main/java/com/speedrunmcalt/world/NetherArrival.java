package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Material;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 * harness world rebuilt the way those match worlds were - WorldSetupVersion
 * 1, see HarnessNetherSeedMixin; a one-seed world got them badly wrong. Every
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
 * has loaded, normally a few seconds in. If it never all loads, an
 * arrival that only looks bad because of the missing terrain is left
 * alone.
 *
 * WHAT THE FIRST LIVE FIRING TAUGHT (2026-10-01, the match after this
 * shipped). It priced a sealed pocket, opened a tunnel that genuinely led
 * out - and the runner still voted the seed bad, because:
 *
 *   - a lavafall from a ceiling spring only started running once the
 *     arrival loaded it, spread over the floor at the portal, and cut the
 *     pocket off from the tunnel. The check had read a world in motion.
 *     ArrivalSnapshot now floods the copy with the lava that is about to
 *     move before the search sees it, and the check runs again at
 *     RECHECK_AFTER in case anything else moved;
 *   - the tunnel ran east and the bastion lay northwest, so a runner
 *     heading for the bastion never met it. The path now takes the exit
 *     facing the bastion when one costs at most SLACK more;
 *   - the search took 4.7 seconds on the server thread, a freeze at the
 *     moment the runner most needs to move. The terrain is now copied a
 *     few chunks per tick and searched on its own thread; only the edits
 *     are made on the server thread.
 */
public final class NetherArrival {
	/** Ring distances priced, in blocks from the arrival. The last one decides. */
	private static final int[] RINGS = {16, 32, 48, 64, 96};

	/** Most edits to reach the outer ring that still counts as runnable. */
	private static final int TOLERANCE = 10;

	/** Search gives up past this many edits. */
	private static final int MAX_COST = 60;

	/** Extra edits worth paying for an exit that faces the bastion. */
	private static final int SLACK = 6;

	/** Chunk radius requested around the arrival: covers the outer ring. */
	private static final int LOAD_RADIUS = 7;

	/** Longest wait for that terrain before checking what has loaded. */
	private static final int MAX_WAIT_TICKS = 200;

	/**
	 * Ticks between passes after the first. A spring's fall and the
	 * spread at its foot take seconds to run, so later passes see lava
	 * the prediction missed, if any did.
	 */
	private static final int[] RECHECK_AFTER = {200, 400};

	/**
	 * Copy reach around the arrival. The search walks to one past the
	 * outer ring, reads a block either side of anything it mines and two
	 * above where it stands, within ArrivalReach's Y band of 32.
	 */
	private static final int COPY_RADIUS = 98;
	private static final int COPY_Y_BAND = 34;

	/** Server-thread time spent copying terrain per tick. */
	private static final long COPY_BUDGET_NANOS = 8_000_000L;

	/** Keeps the arrival's terrain loading; expires on its own. */
	private static final ChunkTicketType<ChunkPos> TICKET =
			ChunkTicketType.create("speedrunmcalt_arrival",
					Comparator.comparingLong(ChunkPos::toLong), MAX_WAIT_TICKS + 100);

	/** Radius of the pad built when no way out is found at all. */
	private static final int PAD_RADIUS = 5;

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "speedrunmcalt-arrival");
		thread.setDaemon(true);
		return thread;
	});

	private NetherArrival() {
	}

	/** One match's arrival check, across its passes. */
	public static final class Job {
		final BlockPos at;
		int pass = 0;
		int waited = 0;
		int idle = 0;
		/** An earlier pass opened a way out, so any cost now means it was blocked again. */
		boolean opened = false;
		ArrivalSnapshot copying;
		long copyNanos;
		long longestCopyNanos;
		int copyTicks;
		CompletableFuture<Measured> searching;

		Job(BlockPos at) {
			this.at = at;
		}
	}

	/** A search result and what it cost, from the search thread. */
	private static final class Measured {
		final ArrivalReach.Result reach;
		final int predicted;
		final long searchMs;

		Measured(ArrivalReach.Result reach, int predicted, long searchMs) {
			this.reach = reach;
			this.predicted = predicted;
			this.searchMs = searchMs;
		}
	}

	/**
	 * Called every server tick until every pass has run. The first call
	 * records where the player arrived and asks for the terrain around
	 * it; later calls advance whichever stage the check is in.
	 */
	public static void tick(ServerWorld nether, BlockPos playerPos) {
		Job job = MatchState.netherArrivalJob;
		if (job == null) {
			job = new Job(playerPos);
			MatchState.netherArrivalJob = job;
			requestTerrain(nether, playerPos);
			return;
		}

		if (job.searching != null) {
			if (!job.searching.isDone()) {
				return;
			}
			Measured m;
			try {
				m = job.searching.join();
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Nether arrival search failed", e);
				m = null;
			}
			job.searching = null;
			if (m != null) {
				job.opened |= apply(nether, job.at, m, job.opened ? 0 : TOLERANCE,
						String.format("pass %d, copied in %d ms over %d ticks, longest %d ms, after %d ticks",
								job.pass + 1, job.copyNanos / 1_000_000, job.copyTicks,
								job.longestCopyNanos / 1_000_000, job.waited));
			}
			job.pass++;
			if (job.pass > RECHECK_AFTER.length) {
				MatchState.netherArrivalChecked = true;
				return;
			}
			job.idle = RECHECK_AFTER[job.pass - 1];
			return;
		}

		if (job.copying == null) {
			if (job.idle > 0) {
				job.idle--;
				return;
			}
			if (job.pass == 0) {
				job.waited++;
				if (!allLoaded(nether, job.at) && job.waited < MAX_WAIT_TICKS) {
					return;
				}
			} else {
				requestTerrain(nether, job.at);
			}
			job.copying = new ArrivalSnapshot(job.at, COPY_RADIUS, COPY_Y_BAND);
			job.copyNanos = 0;
			job.longestCopyNanos = 0;
			job.copyTicks = 0;
		}

		long started = System.nanoTime();
		boolean copied = job.copying.copySome(nether, COPY_BUDGET_NANOS, false);
		long took = System.nanoTime() - started;
		job.copyNanos += took;
		job.longestCopyNanos = Math.max(job.longestCopyNanos, took);
		job.copyTicks++;
		if (!copied) {
			return;
		}
		ArrivalSnapshot snapshot = job.copying;
		job.copying = null;
		BlockPos at = job.at;
		BlockPos toward = bastion(at);
		job.searching = CompletableFuture.supplyAsync(() -> measure(snapshot, at, toward), EXECUTOR);
	}

	private static void requestTerrain(ServerWorld nether, BlockPos at) {
		ChunkPos chunk = new ChunkPos(at);
		nether.getChunkManager().addTicket(TICKET, chunk, LOAD_RADIUS, chunk);
	}

	/**
	 * Whether the copy will find every chunk it needs. Asks the same
	 * question ArrivalSnapshot does: in 0.1.16 this asked isChunkLoaded,
	 * which says yes a little before getWorldChunk hands the chunk over,
	 * so the first pass copied half-loaded terrain, could not price past
	 * 48 blocks, and left the arrival to the pass ten seconds later.
	 */
	private static boolean allLoaded(ServerWorld nether, BlockPos at) {
		int cx = at.getX() >> 4;
		int cz = at.getZ() >> 4;
		int r = (RINGS[RINGS.length - 1] >> 4) + 1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (nether.getChunkManager().getWorldChunk(cx + dx, cz + dz) == null) {
					return false;
				}
			}
		}
		return true;
	}

	/** Where the path out should face: this match's bastion, if known. */
	private static BlockPos bastion(BlockPos at) {
		if (MatchState.bastionX == 0 && MatchState.bastionZ == 0) {
			return null;
		}
		return new BlockPos(MatchState.bastionX, at.getY(), MatchState.bastionZ);
	}

	/** Search thread: predict the lava, then price the way out. */
	private static Measured measure(ArrivalSnapshot snapshot, BlockPos at, BlockPos toward) {
		long started = System.currentTimeMillis();
		snapshot.predictLava();
		ArrivalReach.Result r = ArrivalReach.measure(snapshot, at, RINGS, MAX_COST,
				snapshot::isLoaded, toward, SLACK);
		return new Measured(r, snapshot.predicted, System.currentTimeMillis() - started);
	}

	/**
	 * The whole check at once, on the calling thread - copy, predict,
	 * search, edit. For the harness, where a freeze costs nothing: it
	 * generates any terrain that is missing.
	 */
	public static void check(ServerWorld nether, BlockPos at, int waited) {
		long started = System.currentTimeMillis();
		ArrivalSnapshot snapshot = new ArrivalSnapshot(at, COPY_RADIUS, COPY_Y_BAND);
		snapshot.copySome(nether, Long.MAX_VALUE, true);
		long copyMs = System.currentTimeMillis() - started;
		apply(nether, at, measure(snapshot, at, bastion(at)), TOLERANCE,
				String.format("harness, copied in %d ms, after %d ticks", copyMs, waited));
	}

	/**
	 * Prices the arrival on a fresh copy without changing anything.
	 * For the harness; {@code predict} false prices the world as it
	 * stands, lava where it is now.
	 */
	public static ArrivalReach.Result price(ServerWorld nether, BlockPos at, boolean predict) {
		ArrivalSnapshot snapshot = new ArrivalSnapshot(at, COPY_RADIUS, COPY_Y_BAND);
		snapshot.copySome(nether, Long.MAX_VALUE, true);
		if (predict) {
			snapshot.predictLava();
		}
		return ArrivalReach.measure(snapshot, at, RINGS, MAX_COST, snapshot::isLoaded);
	}

	/**
	 * Server thread: act on a search result. True if it opened a way out.
	 *
	 * {@code tolerance} is TOLERANCE on a first look and 0 once a way has
	 * been opened: in the 2026-10-01 arrival, lava that crossed the
	 * opened path left a detour of three blocks - under TOLERANCE, and
	 * still a seed voted bad, because nothing told the runner the way
	 * existed, let alone that it now went round the lava.
	 */
	private static boolean apply(ServerWorld nether, BlockPos at, Measured m, int tolerance, String how) {
		ArrivalReach.Result r = m.reach;
		int outer = r.outer();
		// An unloaded column stops the search like a wall would. If the
		// verdict then comes out bad, it may be the missing terrain
		// talking, so the world is left alone - unknown is not unrunnable.
		boolean unknown = r.hitUnloaded && (outer < 0 || outer > tolerance);
		if ((outer >= 0 && outer <= tolerance) || unknown) {
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Nether arrival at {},{},{} is runnable ({}; {} cells of lava predicted; "
							+ "searched in {} ms off-thread; {}{})",
					at.getX(), at.getY(), at.getZ(), r, m.predicted, m.searchMs, how,
					unknown ? "; terrain not all loaded - left alone" : "");
			return false;
		}

		if (r.path.isEmpty()) {
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Nether arrival at {},{},{} has no way out within {} edits ({}; {}) - building a pad",
					at.getX(), at.getY(), at.getZ(), MAX_COST, r, how);
			buildPad(nether, at);
			return false;
		}

		int mined = 0;
		int placed = 0;
		int skipped = 0;
		for (ArrivalReach.Edit e : r.path) {
			// The search ran on a copy, and the world has had a few
			// ticks since. Never open a block onto lava that has arrived
			// in the meantime, and never place one inside a player; the
			// next pass prices whatever that leaves.
			if (e.place) {
				if (inPlayer(nether, e.pos)) {
					skipped++;
					continue;
				}
				nether.setBlockState(e.pos, Blocks.NETHERRACK.getDefaultState(), 3);
				placed++;
			} else {
				if (besideLava(nether, e.pos)) {
					skipped++;
					continue;
				}
				nether.setBlockState(e.pos, Blocks.AIR.getDefaultState(), 3);
				mined++;
			}
		}
		SpeedrunMcAlt.LOGGER.warn(
				"[speedrunmcalt] Nether arrival at {},{},{} was unrunnable ({}) - opened a way out to {},{},{}: "
						+ "{} mined, {} placed, {} skipped ({} cells of lava predicted; searched in {} ms off-thread; {})",
				at.getX(), at.getY(), at.getZ(), r, r.exit.getX(), r.exit.getY(), r.exit.getZ(),
				mined, placed, skipped, m.predicted, m.searchMs, how);
		return true;
	}

	private static boolean besideLava(ServerWorld nether, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (nether.getBlockState(pos.offset(d)).getMaterial() == Material.LAVA) {
				return true;
			}
		}
		return false;
	}

	private static boolean inPlayer(ServerWorld nether, BlockPos pos) {
		Box box = new Box(pos);
		for (ServerPlayerEntity player : nether.getPlayers()) {
			if (player.getBoundingBox().intersects(box)) {
				return true;
			}
		}
		return false;
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
