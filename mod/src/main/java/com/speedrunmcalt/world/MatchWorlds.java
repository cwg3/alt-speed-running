package com.speedrunmcalt.world;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Stops finished match worlds piling up forever.
 *
 * Every match leaves a world of roughly forty megabytes, and nothing
 * ever removed one. Fifty matches is two gigabytes of worlds nobody
 * will open again, and a tester who plays for a week has no idea why
 * their disk is filling up.
 *
 * THE CURRENT MATCH IS NEVER TOUCHED, and that distinction is the
 * whole reason this is not just a call to the replay cleaner. A replay
 * world is a cache: it is deleted on purpose before being recreated,
 * so a fixed mod produces a fresh world. A match world is a save in
 * progress. A player who crashes mid-run rejoins the SAME world and
 * expects their inventory, their portal and the hole they dug to still
 * be there - deleting it would turn a crash into a lost run.
 *
 * A few are kept rather than only the current one. A finished world is
 * occasionally worth opening again: checking what a seed actually
 * looked like after a dispute, or reproducing something a player
 * reported. The seeds are in the match record either way, so nothing
 * here is irreplaceable - it is just faster than rebuilding.
 */
public final class MatchWorlds {
	public static final String PREFIX = "match-";

	/**
	 * Finished match worlds to keep, beyond the one being played.
	 *
	 * Enough to look back at the last few matches, small enough that
	 * the folder does not grow without bound.
	 */
	private static final int KEEP = 3;

	private MatchWorlds() {
	}

	public static String nameFor(String matchId) {
		return PREFIX + matchId;
	}

	/**
	 * Most worlds to remove in one pass.
	 *
	 * The backlog can be enormous - nothing deleted match worlds until
	 * this class existed, and a machine that has been testing for a
	 * week holds hundreds of them, gigabytes in total. Clearing that in
	 * one go is a long disk operation, and it would land at the exact
	 * moment a timed race is starting.
	 *
	 * So it trims a few per match and catches up over several. A
	 * backlog that took weeks to build can take a few matches to clear;
	 * nothing depends on it being gone today.
	 */
	private static final int MAX_PER_PASS = 5;

	/**
	 * Trims old match worlds, sparing the one about to be entered.
	 *
	 * OFF THE GAME THREAD, and bounded. Deleting worlds is disk work
	 * and this runs as a match begins - the one moment in this mod
	 * where a stall is charged to somebody's run. It is fire and
	 * forget: the world being created lives in a different directory,
	 * so there is nothing to wait for.
	 *
	 * Never throws. Failing to tidy up must never stop a match from
	 * starting - a full disk is a problem for later, a match that will
	 * not load is a problem right now.
	 */
	public static void pruneOld(MinecraftClient client, String currentMatchId) {
		final Path saves;
		try {
			saves = client.getLevelStorage().getSavesDirectory();
		} catch (Exception e) {
			return;
		}
		final String spare = nameFor(currentMatchId);
		Thread t = new Thread(() -> {
			try {
				GeneratedWorlds.prune(saves, PREFIX, KEEP, spare, MAX_PER_PASS);
			} catch (Exception e) {
				SpeedrunMcAlt.LOGGER.warn(
						"[speedrunmcalt] Could not prune old match worlds", e);
			}
		}, "speedrunmcalt-world-prune");
		t.setDaemon(true);
		t.start();
	}
}
