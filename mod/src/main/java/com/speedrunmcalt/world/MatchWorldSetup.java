package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.seed.LootTopUp;
import com.speedrunmcalt.seed.VillageSmith;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.StructureFeature;

/**
 * Applies a match world's guarantees once the integrated server starts.
 *
 * A filtered seed only gets a structure near spawn; everything a route
 * actually depends on - lava to cast a portal from, a floor under the
 * chest loot, a portal that can be lit - is placed here. Searching for
 * seeds that satisfy all of it at once is not affordable, so the seed
 * supplies the structure and this supplies the rest. That trade is
 * deliberate and published: match worlds are not pure vanilla for their
 * seed.
 *
 * Runs before the player is in the world, and synchronously, so nobody
 * is standing in terrain while it changes underneath them.
 *
 * DETERMINISM: every decision below derives from the match seed and the
 * structure position. Both players race the same seed, so both must get
 * the same world - a difference here would void the match, and would do
 * it intermittently.
 */
public final class MatchWorldSetup {
	/**
	 * How close a player must be for the bastion top-up to run.
	 *
	 * Far enough out that the chunks are already loaded by the game and
	 * the player has not reached a chest yet; close enough that it is
	 * unambiguous they are going there.
	 */
	private static final double BASTION_TRIGGER_RADIUS = 96.0;

	/**
	 * Vertical reach of a temple's quiet zone, measured from its chests.
	 *
	 * A desert temple's chests are in the buried room at the bottom, and
	 * the structure rises about twenty blocks above them to the roof.
	 * The margin below covers the room itself.
	 */
	private static final int TEMPLE_BELOW_CHESTS = 4;
	private static final int TEMPLE_ABOVE_CHESTS = 24;

	private MatchWorldSetup() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(MatchWorldSetup::onServerStarted);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK
				.register(MatchWorldSetup::onTick);
	}

	/**
	 * Tops up the bastion once the player is near it.
	 *
	 * Neither of the obvious moments works. At world creation it costs
	 * nine seconds of loading, because generating chunks that contain a
	 * bastion runs its jigsaw assembly too. On stepping through the
	 * portal it is the same nine seconds as a mid-run freeze, which is
	 * worse.
	 *
	 * The third option is to wait. A runner walks to the bastion, and
	 * those chunks load as they approach - so by the time they are in
	 * range the work is already done by the game itself, and this only
	 * has to read what is there. No generation at any point, and the
	 * top-up lands well before anyone opens a chest.
	 *
	 * Still deterministic: the same chunks from the same seed produce
	 * the same chests, so both players get the same result. Only the
	 * moment it happens differs, and nothing observable depends on
	 * that.
	 */
	private static void onTick(MinecraftServer server) {
		if (!MatchState.inMatch()) {
			return;
		}

		// Arrival check first, and independent of the bastion work: a
		// player stranded over lava never reaches the bastion at all,
		// so gating this behind that would be backwards.
		if (!MatchState.netherArrivalChecked) {
			ServerWorld netherWorld = server.getWorld(World.NETHER);
			if (netherWorld != null && !netherWorld.getPlayers().isEmpty()) {
				MatchState.netherArrivalChecked = true;
				try {
					NetherArrival.ensureRunnable(netherWorld,
							netherWorld.getPlayers().get(0).getBlockPos());
				} catch (Exception e) {
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Nether arrival check failed", e);
				}
			}
		}

		if (MatchState.bastionLootApplied) {
			return;
		}
		if (MatchState.bastionX == 0 && MatchState.bastionZ == 0) {
			return;
		}
		ServerWorld nether = server.getWorld(World.NETHER);
		if (nether == null) {
			return;
		}

		boolean nearby = false;
		for (net.minecraft.server.network.ServerPlayerEntity player : nether.getPlayers()) {
			double dx = player.getX() - MatchState.bastionX;
			double dz = player.getZ() - MatchState.bastionZ;
			if (dx * dx + dz * dz <= BASTION_TRIGGER_RADIUS * BASTION_TRIGGER_RADIUS) {
				nearby = true;
				break;
			}
		}
		if (!nearby) {
			return;
		}

		MatchState.bastionLootApplied = true;
		long startedAt = System.currentTimeMillis();
		try {
			applyBastion(server);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Bastion top-up failed", e);
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Bastion top-up took {} ms",
				System.currentTimeMillis() - startedAt);
	}

	private static void onServerStarted(MinecraftServer server) {
		String seedType = MatchState.seedType;
		if (seedType == null) {
			return; // not a match world - leave it alone
		}

		ServerWorld world = server.getWorld(World.OVERWORLD);
		if (world == null) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No overworld to set up");
			return;
		}

		long seed = MatchState.overworldSeed;
		int x = MatchState.structureX;
		int z = MatchState.structureZ;
		long startedAt = System.currentTimeMillis();

		try {
			long t0 = System.currentTimeMillis();
			apply(world, seedType, seed, x, z);
			long overworldMs = System.currentTimeMillis() - t0;

			// The bastion is NOT done here - see onTick. Measured at
			// 9 seconds of loading when it was, against 105ms for the
			// whole overworld.
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Overworld setup {} ms", overworldMs);

			// NO bastion "correction" here. See correctBastionPosition.
			// Where the bastion actually IS, as opposed to where it is
			// anchored.
			//
			// The pool ships cubiomes' structure position, which is the
			// corner the generator counts from. A bastion sprawls up to
			// seventy blocks from it: a player sent to 48,112 stood at
			// the anchor, saw empty nether, and the buildings were
			// twenty-seven blocks away. Same mistake as sending someone
			// to a village anchor and calling it the blacksmith.
			//
			// Jigsaw only - no chunks - so this is a few milliseconds
			// and can happen here, long before the player needs it.
			logBastionCentre(server);
		} catch (Exception e) {
			// A failed guarantee is worth knowing about loudly, but it
			// must not stop the player entering the world - a degraded
			// match beats a crash on world load.
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] World setup failed for {} seed", seedType, e);
		}
		SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] World setup for {} took {} ms",
				seedType, System.currentTimeMillis() - startedAt);
	}

	/**
	 * How far out to ask the game for the bastion, in chunks.
	 *
	 * The filter promises one within 16 chunks of nether spawn, so 16
	 * would do if the shipped position were trustworthy. It is not:
	 * one measured seed had its real bastion 25 chunks out while
	 * cubiomes pointed 500 blocks the other way, so the search has to
	 * be wide enough to find a bastion the pool mislocated.
	 */
	private static final int BASTION_SEARCH_CHUNKS = 64;

	/**
	 * Replaces the shipped bastion position with the game's own answer.
	 *
	 * The pool's coordinates come from cubiomes, and cubiomes is
	 * INTERMITTENTLY WRONG about nether structures. Measured: one seed
	 * where it agreed with the game exactly, another where it claimed
	 * 112,-208 and the bastion was at -368,192 - about 500 blocks away
	 * in the opposite direction. A player walked to the shipped
	 * coordinate twice and found empty nether, and the loot top-up
	 * scanned that emptiness and reported "no bastion chests" in 23ms,
	 * working exactly as designed on the wrong place.
	 *
	 * query.c has carried a comment since it was written saying the
	 * nether predictions did not match generated worlds. It was
	 * dismissed because several bastions happened to line up.
	 *
	 * locateStructure is what /locate uses: it reads structure starts
	 * and does not generate terrain, so it is affordable here and it is
	 * the same source of truth the game itself answers from.
	 *
	 * The correction is logged loudly when it is large, because how
	 * often this fires is worth knowing - it is the measurement that
	 * says whether the pool's nether data can be trusted at all.
	 */
	/**
	 * DISABLED - kept as a record of a wrong conclusion.
	 *
	 * This replaced the shipped bastion position with
	 * ServerWorld.locateStructure's answer, on the belief that the game
	 * was authoritative and cubiomes was wrong on ~30% of seeds.
	 *
	 * It is the other way round. On seed 132890337480255 locateStructure
	 * reported a bastion at -368,192 - 415 blocks from the origin -
	 * while ignoring one at 88,-193, only 237 blocks out, where a probe
	 * found TWELVE bastion chests including bastion_hoglin_stable,
	 * matching the type the pool shipped. cubiomes and Chunkbase both
	 * name that closer bastion; only locateStructure disagrees.
	 *
	 * So the "30% wrong" figure measured locateStructure, and turning
	 * this on would have overwritten correct coordinates with bad ones
	 * on a third of seeds.
	 *
	 * The lesson is the one this project keeps relearning: "ask the
	 * game" is only better than a model if you ask it the right
	 * question. locateStructure answers something subtler than "where
	 * is the nearest bastion", and I did not check what before building
	 * on it.
	 */
	@SuppressWarnings("unused")
	private static void correctBastionPositionDisabled(MinecraftServer server) {
	}

	/** Logs the bastion's real centre alongside the shipped anchor. */
	private static void logBastionCentre(MinecraftServer server) {
		if (MatchState.bastionX == 0 && MatchState.bastionZ == 0) {
			return;
		}
		try {
			VillageSmith.Village bastion = VillageSmith.inspect(
					server.getStructureManager(), MatchState.netherSeed,
					StructureFeature.BASTION_REMNANT,
					MatchState.bastionX, MatchState.bastionZ, true);
			if (!bastion.exists()) {
				SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No bastion predicted at {},{}",
						MatchState.bastionX, MatchState.bastionZ);
				return;
			}
			BlockBox box = bastion.predictedBox;
			int cx = (box.minX + box.maxX) / 2;
			int cz = (box.minZ + box.maxZ) / 2;
			SpeedrunMcAlt.LOGGER.info(
					"[speedrunmcalt] Bastion centre {},{} (anchor {},{}, spans x[{}..{}] z[{}..{}])",
					cx, cz, MatchState.bastionX, MatchState.bastionZ,
					box.minX, box.maxX, box.minZ, box.maxZ);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] Could not work out the bastion centre", e);
		}
	}

	private static void apply(ServerWorld world, String seedType, long seed, int x, int z) {
		switch (seedType) {
			case "village":
			case "desert_temple":
				// Both openings cast a portal from a lava pool, and
				// lava lakes are terrain features rather than
				// structures, so they cannot be filtered for.
				// The return value is how many pools were actually
				// placed, and ignoring it was the same mistake made
				// with the ruined portal placer: a guarantee that
				// quietly failed and shipped anyway. Both openings cast
				// a portal from lava, so no lava is no route.
				int pools = LavaPoolPlacer.place(world, seed, x, z);
				if (pools == 0) {
					MatchState.setupFailure =
							"no lava pool could be placed near the objective - "
									+ "this opening casts its portal from lava";
					SpeedrunMcAlt.LOGGER.error(
							"[speedrunmcalt] GUARANTEE FAILED: {} (seed {} at {},{})",
							MatchState.setupFailure, seed, x, z);
				}
				break;
			case "ruined_portal": {
				// NOTHING IS BUILT. The pool filter guarantees this
				// seed's own vanilla ruined portal is a vertical frame
				// at most two real obsidian short of complete, which is
				// what the chest's obsidian floor covers.
				//
				// This replaced always-placing, which replaced
				// keep-vanilla-when-good, in one day. The short version:
				// the keep-or-place DECISION was unfixable, so it was
				// removed and everything was placed - and then placing
				// turned out to owe a faithful reconstruction of the
				// whole structure, because a real ruined portal is 168
				// netherrack, 13 magma blocks, a stone-brick debris
				// palette and FOUR GOLD BLOCKS, and the last of those is
				// bartering material a placed frame silently withheld.
				//
				// Filtering costs 3 minutes of pool build per accepted
				// seed at the measured 22% pass rate. The ravine filter
				// has run at 3% since it was written. That is the whole
				// argument: the cheap resource is build time, and it
				// buys a portal that IS vanilla rather than one that
				// imitates it.
				//
				// The loot top-up still runs below, so the two missing
				// obsidian and a light source are guaranteed.
				LavaPoolPlacer.placePortalAccess(world, seed, x, z);
				break;
			}
			default:
				// Shipwreck and buried treasure route through ocean
				// ravines rather than a surface pool.
				break;
		}

		LootTopUp.SeedType type = lootTypeFor(seedType);
		if (type == null) {
			return;
		}
		// A placed portal has no structure start, so build a box around
		// what was built instead of asking the generator about it.
		if (MatchState.placedPortal != null) {
			BlockPos p = MatchState.placedPortal;
			net.minecraft.util.math.BlockBox box = new net.minecraft.util.math.BlockBox(
					p.getX() - 8, p.getY() - 4, p.getZ() - 8,
					p.getX() + 8, p.getY() + 8, p.getZ() + 8);
			// strictBox: we BUILT this portal, so its box is measured
			// rather than predicted. Without it the chunk-wide scan
			// reaches the buried vanilla portal a few blocks away and
			// satisfies the guarantees from a chest the player will
			// never open - which is exactly what happened, and cost a
			// match.
			LootTopUp.apply(world, type, box, seed, true);
			return;
		}

		StructureFeature<?> feature = featureFor(seedType);
		VillageSmith.Village structure = VillageSmith.inspect(
				world.getServer().getStructureManager(), seed, feature, x, z);
		if (!structure.exists()) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No {} structure at {},{} - loot not topped up",
					seedType, x, z);
			return;
		}
		// The smith is the objective on a village seed, and the anchor
		// the pool ships can be a hundred blocks from it, so say where
		// it actually is.
		if (type == LootTopUp.SeedType.VILLAGE
				&& (MatchState.smithX != 0 || MatchState.smithZ != 0)) {
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Blacksmith at {},{} (village anchor {},{})",
					MatchState.smithX, MatchState.smithZ, x, z);
		}

		// BEFORE the loot top-up, not after: a chest placed here is one
		// the top-up must see, or the iron and food guarantees would be
		// counted against a wreck that is short a chest and then land
		// in the wrong one.
		if (type == LootTopUp.SeedType.SHIPWRECK) {
			try {
				ShipwreckChests.ensureAllThree(world, structure.xzBox(), seed);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Shipwreck chest normalisation failed", e);
			}
		}

		LootTopUp.apply(world, type, structure.xzBox(), seed);

		// Remember a temple's box so mobs and bats can be kept out of it
		// - they pollute the pie-ray reading runners use to locate
		// underground structures.
		//
		// X and Z come from the prediction, which is exact. Y comes
		// from the chests that were actually found, because the
		// prediction's Y is not - it was out by fifty blocks on the
		// temple that was measured, so using it directly gave a box
		// hovering above the structure: the mixin tested every mob
		// against empty air, cancelled nothing, and temples kept their
		// mobs through two rounds of being "fixed".
		//
		// The chests sit in the bottom room, so the box grows upward
		// from them to cover the structure and only a little below.
		if (type == LootTopUp.SeedType.DESERT_TEMPLE) {
			BlockBox chests = LootTopUp.lastContainerBounds();
			if (chests != null) {
				MatchState.quietBox = new BlockBox(
						structure.predictedBox.minX, chests.minY - TEMPLE_BELOW_CHESTS, structure.predictedBox.minZ,
						structure.predictedBox.maxX, chests.maxY + TEMPLE_ABOVE_CHESTS, structure.predictedBox.maxZ);
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Temple quiet zone y[{}..{}] (chests at y{}, predicted y[{}..{}])",
						MatchState.quietBox.minY, MatchState.quietBox.maxY, chests.minY,
						structure.predictedBox.minY, structure.predictedBox.maxY);
			} else {
				SpeedrunMcAlt.LOGGER.warn(
						"[speedrunmcalt] No temple chests found - mobs not suppressed");
			}
		}
	}

	/**
	 * Tops up the bastion's chests while the world is still loading.
	 *
	 * Done here rather than when the player first reaches the nether,
	 * which was the original design. Measurement settled it: generating
	 * the bastion's chunks costs about a second (962-1377ms across the
	 * four bastion types) while the loot work itself is 2-4ms. Deferring
	 * it therefore bought nothing and spent a one-second freeze at the
	 * exact moment a runner steps through the portal and is being
	 * timed. On the loading screen the same second is free.
	 */
	private static void applyBastion(MinecraftServer server) {
		if (MatchState.bastionX == 0 && MatchState.bastionZ == 0) {
			return; // no bastion position from the backend
		}
		ServerWorld nether = server.getWorld(World.NETHER);
		if (nether == null) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No nether world to set up");
			return;
		}

		// Nether generator, not overworld: structures are looked up
		// through the biome at their position, so an overworld
		// generator reports every bastion as absent.
		VillageSmith.Village bastion = VillageSmith.inspect(
				server.getStructureManager(), MatchState.netherSeed,
				StructureFeature.BASTION_REMNANT,
				MatchState.bastionX, MatchState.bastionZ, true);
		if (!bastion.exists()) {
			SpeedrunMcAlt.LOGGER.warn("[speedrunmcalt] No bastion at {},{} - loot not topped up",
					MatchState.bastionX, MatchState.bastionZ);
			return;
		}
		com.speedrunmcalt.seed.BastionLoot.apply(nether, bastion.xzBox(), MatchState.overworldSeed);
	}

	private static LootTopUp.SeedType lootTypeFor(String seedType) {
		switch (seedType) {
			case "village": return LootTopUp.SeedType.VILLAGE;
			case "desert_temple": return LootTopUp.SeedType.DESERT_TEMPLE;
			case "ruined_portal": return LootTopUp.SeedType.RUINED_PORTAL;
			case "shipwreck": return LootTopUp.SeedType.SHIPWRECK;
			case "buried_treasure": return LootTopUp.SeedType.BURIED_TREASURE;
			default: return null;
		}
	}

	private static StructureFeature<?> featureFor(String seedType) {
		switch (seedType) {
			case "village": return StructureFeature.VILLAGE;
			case "desert_temple": return StructureFeature.DESERT_PYRAMID;
			case "ruined_portal": return StructureFeature.RUINED_PORTAL;
			case "shipwreck": return StructureFeature.SHIPWRECK;
			case "buried_treasure": return StructureFeature.BURIED_TREASURE;
			default: return null;
		}
	}
}
