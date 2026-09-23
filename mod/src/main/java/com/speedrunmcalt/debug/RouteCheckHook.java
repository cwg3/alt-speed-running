package com.speedrunmcalt.debug;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.seed.ContainerScan;
import com.speedrunmcalt.seed.MagmaRavine;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * TEMPORARY - verifies a pool pair's OVERWORLD OPENING against the game.
 *
 * Tier 4 verified the nether: the bastion is where we ship it and the
 * distances hold. The overworld route was never verified by instrument
 * at all. The filter checks that a structure is PREDICTED near spawn,
 * and then trusts MatchWorldSetup to supply everything a route actually
 * needs - lava to cast a portal from, a portal that can be lit, chests
 * with a floor under them.
 *
 * Nobody ever checked that the supplying WORKED. It does not always: a
 * ruined portal seed reached a player with its vanilla portal submerged
 * in open ocean and the fallback placer unable to find anywhere dry to
 * build. Three warnings went to the log and the race started anyway.
 * The player had no route to the nether at all.
 *
 * So this runs the REAL MatchWorldSetup - same code, same order, same
 * world - and then asks whether the opening it was supposed to create
 * is actually there.
 *
 * MatchState is set in onInitializeServer rather than inside the
 * SERVER_STARTED handler, because MatchWorldSetup reads it from that
 * same event. Setting it in the handler would be a race this would lose
 * about half the time, and losing it silently looks exactly like a
 * passing seed.
 *
 * Reads routecheck.txt:
 *   seedType overworldSeed netherSeed structX structZ smithX smithZ
 */
public class RouteCheckHook implements DedicatedServerModInitializer {
	/** How far from the objective to look for lava to cast a portal. */
	private static final int LAVA_RADIUS = 96;

	/** How far from the objective a chest may be and still be its chest. */
	private static final int CHEST_RADIUS = 96;

	/** The published magma ravine rule: 2 within 10 chunks. */
	private static final int RAVINE_RADIUS = 160;

	private static String seedType;
	private static int smithX;
	private static int smithZ;
	private static boolean armed = false;

	@Override
	public void onInitializeServer() {
		// BEFORE any SERVER_STARTED handler, so MatchWorldSetup sees a
		// match world and does its real work.
		try {
			if (!Files.exists(Paths.get("routecheck.txt"))) {
				return;
			}
			String[] p = new String(Files.readAllBytes(
					Paths.get("routecheck.txt")), "UTF-8").trim().split("\\s+");
			seedType = p[0];
			MatchState.seedType = p[0];
			MatchState.overworldSeed = Long.parseLong(p[1]);
			MatchState.netherSeed = Long.parseLong(p[2]);
			MatchState.structureX = Integer.parseInt(p[3]);
			MatchState.structureZ = Integer.parseInt(p[4]);
			smithX = Integer.parseInt(p[5]);
			smithZ = Integer.parseInt(p[6]);
			MatchState.smithX = smithX;
			MatchState.smithZ = smithZ;
			MatchState.matchId = "routecheck";
			MatchState.matchStartMillis = System.currentTimeMillis();
			armed = true;
			SpeedrunMcAlt.LOGGER.info("[routecheck] armed: {} overworld={} nether={} struct={},{}",
					seedType, MatchState.overworldSeed, MatchState.netherSeed,
					MatchState.structureX, MatchState.structureZ);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[routecheck] could not arm", e);
			return;
		}

		// Registered after MatchWorldSetup's own handler, so this runs
		// once setup has finished and can judge its results.
		ServerLifecycleEvents.SERVER_STARTED.register(this::check);
	}

	private void check(net.minecraft.server.MinecraftServer server) {
		if (!armed) {
			return;
		}
		String verdict = "PASS";
		String detail = "";
		try {
			ServerWorld world = server.getWorld(World.OVERWORLD);
			int sx = MatchState.structureX;
			int sz = MatchState.structureZ;

			// 1. Did setup itself admit to failing? This is the check
			//    that would have caught the submerged-portal seed, and
			//    it costs nothing.
			if (MatchState.setupFailure != null) {
				verdict = "FAIL";
				detail = "setup: " + MatchState.setupFailure;
			}

			// 2. Type-specific: is the opening actually there?
			int lava = -1;
			int chests = -1;
			String extra = "";
			if ("ruined_portal".equals(seedType)) {
				// A placed portal is now the ONLY acceptable outcome -
				// the keep-vanilla branch is gone, so its absence means
				// placement failed rather than that vanilla's was good.
				if (MatchState.placedPortal != null) {
					extra = "portal=placed@" + MatchState.placedPortal.getX()
							+ "," + MatchState.placedPortal.getZ();
				} else if ("PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no portal was placed";
				}
				chests = countChests(world, sx, sz, CHEST_RADIUS);

				// Can the portal actually be LIT? Existing is not
				// enough. A player reached a placed portal with flint,
				// obsidian and a golden pickaxe and no way to open it,
				// because the guarantee had been satisfied from a chest
				// buried 22 blocks down in the vanilla portal it
				// replaced. "A portal is here" was true and useless.
				BlockPos pp = MatchState.placedPortal;
				int ix = pp != null ? pp.getX() : sx;
				int iz = pp != null ? pp.getZ() : sz;
				if (!hasIgniter(world, ix, iz, 24) && "PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no flint and steel or fire charge in reach of the portal";
				}
			} else if ("village".equals(seedType) || "desert_temple".equals(seedType)) {
				// Both openings cast a portal from lava, so lava near
				// the objective IS the route. Counting it is the whole
				// point: "a pool was requested" is not "a pool exists".
				int ox = "village".equals(seedType) && smithX != 0 ? smithX : sx;
				int oz = "village".equals(seedType) && smithZ != 0 ? smithZ : sz;
				lava = countLava(world, ox, oz, LAVA_RADIUS);
				chests = countChests(world, ox, oz, CHEST_RADIUS);
				if (lava == 0 && "PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no lava within " + LAVA_RADIUS + " blocks of the objective";
				}
				if (chests == 0 && "PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no containers within " + CHEST_RADIUS + " blocks of the objective";
				}
			} else {
				// Shipwreck and buried treasure route through an ocean
				// magma ravine rather than a surface pool.
				// 160 blocks = 10 chunks, which is the PUBLISHED rule
				// (SPEC.md: "2 magma ravines within 10 chunks") and what
				// RavineCheckHook uses in tier 3.
				//
				// This was 128 on its first run and quarantined a seed
				// tier 3 had just passed. The seed was fine; the checker
				// was stricter than the rule it was checking. A verifier
				// that disagrees with the spec is not a stricter
				// verifier, it is a wrong one - and it fails seeds in a
				// way that looks exactly like a real defect.
				MagmaRavine.Result r = MagmaRavine.find(world, sx, sz, RAVINE_RADIUS);
				// WHERE, not just how far. A distance tells a verifier
				// the rule holds; it does not tell a player where to
				// swim.
				extra = "ravine=" + r.ravine + " magma=" + r.magmaBlocks
						+ " nearest=" + r.nearestDistance
						+ (r.nearestPos == null ? ""
								: " at " + r.nearestPos.getX() + ";" + r.nearestPos.getY()
										+ ";" + r.nearestPos.getZ());
				chests = countChests(world, sx, sz, CHEST_RADIUS);
				if (!r.usable() && "PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no usable magma ravine";
				}
				if (chests == 0 && "PASS".equals(verdict)) {
					verdict = "FAIL";
					detail = "no containers within " + CHEST_RADIUS + " blocks of the structure";
				}
			}

			SpeedrunMcAlt.LOGGER.info(
					"[routecheck] {} {} lava={} chests={} {} {}",
					seedType, MatchState.overworldSeed, lava, chests, extra,
					verdict + (detail.isEmpty() ? "" : " - " + detail));

			try (FileWriter out = new FileWriter("routecheck.csv", true)) {
				out.write(seedType + "," + MatchState.overworldSeed + ","
						+ lava + "," + chests + "," + verdict + ","
						+ detail.replace(',', ';') + "," + extra.replace(',', ';') + "\n");
			}
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[routecheck] failed", e);
			try (FileWriter out = new FileWriter("routecheck.csv", true)) {
				out.write(seedType + "," + MatchState.overworldSeed
						+ ",-1,-1,ERROR," + e.getClass().getSimpleName() + ",\n");
			} catch (Exception ignored) {
				// nothing useful left to do
			}
		}
		server.stop(false);
	}

	/**
	 * Lava a runner can actually put in a bucket: a SOURCE block with
	 * air directly above it.
	 *
	 * The first version of this counted any lava source between y4 and
	 * y80 and returned numbers like 1269 for a village - because deep
	 * underground lava is nearly everywhere, and a check that passes
	 * everything is not a check. A runner cannot bucket lava sealed
	 * under a hundred blocks of stone; they need it open to the sky or
	 * to a cave they are standing in. Air above is the cheap test for
	 * that, and it is the one that would notice a seed whose only lava
	 * is buried.
	 */
	private static int countLava(ServerWorld world, int cx, int cz, int radius) {
		int found = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		BlockPos.Mutable above = new BlockPos.Mutable();
		for (int x = cx - radius; x <= cx + radius; x += 2) {
			for (int z = cz - radius; z <= cz + radius; z += 2) {
				int surface = world.getTopY(
						net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x, z);
				// Only near the surface. Deep lava seas are irrelevant
				// to a portal cast in the first two minutes.
				int lowest = Math.max(4, surface - 24);
				for (int y = surface + 2; y >= lowest; y--) {
					pos.set(x, y, z);
					if (world.getBlockState(pos).getBlock() != Blocks.LAVA
							|| !world.getFluidState(pos).isStill()) {
						continue;
					}
					above.set(x, y + 1, z);
					if (world.getBlockState(above).isAir()) {
						found++;
					}
					break;
				}
			}
		}
		return found;
	}

	/** A means of lighting the portal, in a container near it. */
	private static boolean hasIgniter(ServerWorld world, int cx, int cz, int radius) {
		BlockBox box = new BlockBox(cx - radius, 0, cz - radius, cx + radius, 255, cz + radius);
		for (BlockPos cp : ContainerScan.findWithin(world, box)) {
			net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(cp);
			if (!(be instanceof net.minecraft.inventory.Inventory)) {
				continue;
			}
			net.minecraft.inventory.Inventory inv = (net.minecraft.inventory.Inventory) be;
			for (int i = 0; i < inv.size(); i++) {
				net.minecraft.item.Item item = inv.getStack(i).getItem();
				if (item == net.minecraft.item.Items.FLINT_AND_STEEL
						|| item == net.minecraft.item.Items.FIRE_CHARGE) {
					return true;
				}
			}
		}
		return false;
	}

	private static int countChests(ServerWorld world, int cx, int cz, int radius) {
		BlockBox box = new BlockBox(cx - radius, 0, cz - radius, cx + radius, 255, cz + radius);
		java.util.List<BlockPos> found = ContainerScan.find(world, box);

		// Log what is IN them, not just how many there are.
		//
		// "chests=2" passed a ruined portal seed whose player could not
		// light the portal. A count answers "is there a container", and
		// the question the route asks is "is the thing you need in it".
		for (BlockPos cp : found) {
			net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(cp);
			if (!(be instanceof net.minecraft.inventory.Inventory)) {
				continue;
			}
			net.minecraft.inventory.Inventory inv = (net.minecraft.inventory.Inventory) be;
			StringBuilder items = new StringBuilder();
			for (int i = 0; i < inv.size(); i++) {
				net.minecraft.item.ItemStack st = inv.getStack(i);
				if (st.isEmpty()) {
					continue;
				}
				if (items.length() > 0) {
					items.append(", ");
				}
				items.append(st.getCount()).append("x ")
						.append(net.minecraft.util.registry.Registry.ITEM.getId(st.getItem()));
			}
			SpeedrunMcAlt.LOGGER.info("[routecheck] container {},{},{}: {}",
					cp.getX(), cp.getY(), cp.getZ(),
					items.length() == 0 ? "(empty)" : items.toString());
		}
		return found.size();
	}
}
