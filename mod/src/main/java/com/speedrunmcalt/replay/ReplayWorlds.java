package com.speedrunmcalt.replay;

import com.speedrunmcalt.SpeedrunMcAlt;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Replay worlds are a CACHE, and were being treated as a save.
 *
 * Minecraft's createLevel opens an existing directory of that name
 * rather than generating a new one, so a replay watched once was
 * frozen at whatever the mod did that day. After the nether seed
 * redirect was fixed, re-watching the same match still showed no
 * bastion - the structure locate found it at 96,-80 (seed maths, no
 * chunks needed) while the scan found zero containers there, because
 * the blocks came from chunks saved before the fix. The fix was live
 * and invisible.
 *
 * They also accumulate. Four of them had reached 163MB, and every
 * replay ever watched adds another 40MB that nothing ever removes.
 *
 * Deleting is safe in a way that deleting a save never is: every one
 * of these is regenerated from two seeds in a few seconds, and holds
 * nothing a player made. What makes it safe is the guard below, not
 * that claim - the name must start with the prefix this mod uses, and
 * the resolved path must sit directly inside the saves directory.
 */
public final class ReplayWorlds {
	public static final String PREFIX = "replay-";

	/**
	 * How many to leave behind. Re-watching the same match twice in a
	 * row is common enough to be worth a cache; twenty of them is
	 * just disk.
	 */
	private static final int KEEP = 3;

	private ReplayWorlds() {
	}

	public static String nameFor(String matchId) {
		return PREFIX + matchId;
	}

	/**
	 * Removes this match's cached world so it regenerates under the
	 * CURRENT rules, then trims the oldest of what is left.
	 *
	 * Never throws. A replay that cannot clear its cache should still
	 * open - it will show a stale world, which is worse than fresh and
	 * much better than nothing.
	 */
	public static void clearFor(MinecraftClient client, String matchId) {
		try {
			Path saves = client.getLevelStorage().getSavesDirectory();
			com.speedrunmcalt.world.GeneratedWorlds.delete(
					saves, saves.resolve(nameFor(matchId)), PREFIX);
			com.speedrunmcalt.world.GeneratedWorlds.prune(saves, PREFIX, KEEP, null);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.warn(
					"[speedrunmcalt] Could not clear cached replay worlds", e);
		}
	}

}
