package com.speedrunmcalt.match;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.world.GeneratedWorlds;
import com.speedrunmcalt.world.MatchWorldCreator;
import com.speedrunmcalt.world.MatchWorlds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reset Race World: throw the match world away and start again on a
 * fresh copy of the same seed, back at spawn with nothing.
 *
 * THE CLOCK KEEPS RUNNING. A reset is a way out of a bad spawn, a
 * death in lava with everything on you, a portal lit in the wrong
 * place - never a do-over. The run start the server holds is not
 * touched, so the time the first attempt took, and the time the
 * reload takes, are both charged to the run. That is what makes it
 * safe to offer in a ranked match at all.
 *
 * Splits already earned stand: they happened, at the times reported.
 * The fresh world's advancements are new, so SplitReporter refuses to
 * send the same split twice - the server would reject the repeat and
 * keep it as a rejection on the match, which is anti-cheat's audit
 * trail and not a place for noise.
 *
 * The drop and barter schedules restart with the world. They are the
 * same sequence the opponent draws from, so starting it again is the
 * fair choice - carrying the counters over would hand a player who
 * reset a later stretch of luck that nobody else ever sees.
 *
 * The replay trace is NOT reset. The jump back to spawn is one
 * isolated move, which the speed check already allows for pearls and
 * portals, and a "reset" event marks it on the timeline.
 *
 * Driven from a tick, like the matchmaker and the replay launcher:
 * disconnect() runs a nested render loop and hangs when called from a
 * button callback.
 */
public final class RaceReset {
	private static volatile boolean pending = false;
	private static int resets = 0;
	/** Said once the player is back in a world to read it. */
	private static boolean failed = false;

	private RaceReset() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RaceReset::tick);
	}

	/**
	 * Only once the race is under way, and only until it ends. Before
	 * the clock starts there is nothing to reset to that the player is
	 * not already standing in.
	 */
	public static boolean available() {
		return MatchState.inMatch() && !MatchState.replayMode && MatchState.result == null;
	}

	public static void request() {
		if (available()) {
			pending = true;
		}
	}

	/** Forget the count - a new match starts at zero resets. */
	public static void clear() {
		pending = false;
		resets = 0;
		failed = false;
	}

	private static void tick(MinecraftClient client) {
		if (failed && client.player != null) {
			failed = false;
			client.player.sendMessage(new LiteralText(
					"Could not reset the world - you are back in the one you left.")
					.formatted(Formatting.RED), false);
		}
		if (!pending) {
			return;
		}
		if (!available()) {
			// The match ended while the request was in flight - a
			// result landed from the poller. Leave the player where
			// they are.
			pending = false;
			return;
		}
		if (client.world != null) {
			ReplayRecorder.event("reset", String.valueOf(resets + 1));
			client.world.disconnect();
			client.disconnect(new net.minecraft.client.gui.screen.SaveLevelScreen(
					new net.minecraft.text.TranslatableText("menu.savingLevel")));
			return;   // world gone next tick
		}
		pending = false;

		String name = MatchWorlds.nameFor(MatchState.matchId);
		boolean deleted = false;
		try {
			Path saves = client.getLevelStorage().getSavesDirectory();
			Path world = saves.resolve(name);
			GeneratedWorlds.delete(saves, world, MatchWorlds.PREFIX);
			// delete() refuses quietly on a path it does not trust, so
			// ask the disk rather than taking its return as an answer.
			deleted = !Files.exists(world);
		} catch (Exception e) {
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Could not delete {} for a reset", name, e);
		}

		if (deleted) {
			resets++;
			MatchState.resetWorld();
			SpeedrunMcAlt.LOGGER.info("[speedrunmcalt] Race world reset ({}), clock still running",
					resets);
		}
		// Either way the player goes back into a match world. If the old
		// one could not be removed this reopens it as it was - a failed
		// reset must leave them in their run, not on the title screen
		// with the clock going.
		MatchWorldCreator.createMatchWorld(client, name,
				MatchState.overworldSeed, MatchState.netherSeed);
		failed = !deleted;
	}
}
