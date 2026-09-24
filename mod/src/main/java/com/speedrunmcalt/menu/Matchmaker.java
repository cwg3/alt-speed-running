package com.speedrunmcalt.menu;

import com.speedrunmcalt.SpeedrunMcAlt;
import com.speedrunmcalt.match.LiveMatchPoller;
import com.speedrunmcalt.match.MatchState;
import com.speedrunmcalt.match.ReplayRecorder;
import com.speedrunmcalt.net.BackendClient;
import com.speedrunmcalt.net.QueueJoinResult;
import com.speedrunmcalt.world.MatchWorldCreator;
import net.minecraft.client.MinecraftClient;

/**
 * Queue search driven by the menu, replacing the old fire-once flow
 * that ran automatically on client launch.
 *
 * Volatile state for the same reason as AltSession: the search runs on
 * a background thread and the screen reads it every frame.
 */
public final class Matchmaker {
	public enum State { IDLE, SEARCHING, LAUNCHING, ERROR }

	private static final long POLL_INTERVAL_MS = 3000;

	private static volatile State state = State.IDLE;
	private static volatile String error;
	private static volatile String opponent;
	private static volatile long searchStartMillis;
	// Bumped on cancel so an in-flight search thread knows to stop
	// without needing to interrupt it mid-request.
	private static volatile int searchGeneration = 0;

	private Matchmaker() {
	}

	/**
	 * A match found but not yet entered.
	 *
	 * Handed to a client TICK rather than to client.execute(), and that
	 * distinction is the whole reason this exists.
	 *
	 * Players queue and then practise in another world instead of
	 * sitting on a menu, so a match is normally found with an
	 * integrated server already running and the client has to leave it
	 * first. MinecraftClient.disconnect() begins with cancelTasks() -
	 * it CLEARS the client task queue - and then spins
	 * `while (!server.isStopping()) render(false)`, a nested render
	 * loop. Called from inside a queued task, as this used to be, it
	 * wipes the queue it is running from (taking the follow-up world
	 * creation with it) and re-enters render from within the render
	 * loop's own task drain. The client hangs on a black screen with no
	 * exception and cannot recover.
	 *
	 * A tick handler runs outside that drain, which is the same place
	 * vanilla's own "save and quit to title" ends up, so disconnect
	 * behaves the way it was written to.
	 */
	private static volatile QueueJoinResult pending;
	private static volatile String pendingToken;

	/** Registered once, from the client initializer. */
	public static void init() {
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
				.END_CLIENT_TICK.register(Matchmaker::tick);
	}

	private static void tick(MinecraftClient client) {
		QueueJoinResult match = pending;
		if (match == null) {
			return;
		}

		// Leave the practice world first and do nothing else this tick.
		// disconnect() returns only once the integrated server has
		// stopped, and the next tick sees a client with no world.
		if (client.world != null) {
			// BOTH calls, in this order. This is exactly what vanilla's
			// "Save and Quit to Title" does, and the first one is not
			// optional: ClientWorld.disconnect() closes the connection,
			// which is what makes the integrated server begin stopping.
			//
			// MinecraftClient.disconnect() on its own ends in
			// `while (!integratedServer.isStopping()) render(false)` -
			// a spin waiting for a shutdown that, without the first
			// call, nobody ever asked for. It never returns.
			client.world.disconnect();
			client.disconnect(new net.minecraft.client.gui.screen.SaveLevelScreen(
					new net.minecraft.text.TranslatableText("menu.savingLevel")));
			return;
		}

		pending = null;
		String token = pendingToken;
		pendingToken = null;
		try {
			MatchState.reset();
			ReplayRecorder.reset();
			MatchState.matchId = match.matchId;
			MatchState.sessionToken = token;
			// Deliberately NOT started here. MatchClock starts it on the
			// first tick the player can actually play, so world
			// generation, setup and loading are not charged to the run.
			// Set before the world is created: the setup hook fires as
			// soon as the integrated server starts and reads these to
			// know what to guarantee.
			MatchState.seedType = match.seedType;
			MatchState.structureX = match.structureX;
			MatchState.structureZ = match.structureZ;
			MatchState.overworldSeed = match.overworldSeed;
			MatchState.netherSeed = match.netherSeed;
			MatchState.bastionX = match.bastionX;
			MatchState.bastionZ = match.bastionZ;
			MatchState.smithX = match.smithX;
			MatchState.smithZ = match.smithZ;
			MatchState.runAlreadyStarted = match.runAlreadyStarted;
			MatchWorldCreator.createMatchWorld(client, "match-" + match.matchId,
					match.overworldSeed, match.netherSeed);
			LiveMatchPoller.start();
			state = State.IDLE;
		} catch (Exception e) {
			MatchState.reset();
			ReplayRecorder.reset();
			error = e.getMessage();
			state = State.ERROR;
			SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Match world creation failed", e);
		}
	}


	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static String opponent() {
		return opponent;
	}

	public static long searchSeconds() {
		return state == State.SEARCHING
				? (System.currentTimeMillis() - searchStartMillis) / 1000
				: 0;
	}

	public static void cancel() {
		searchGeneration++;
		state = State.IDLE;
		opponent = null;
	}

	public static void search(MinecraftClient client) {
		if (state == State.SEARCHING || state == State.LAUNCHING) {
			return;
		}
		String token = AltSession.sessionToken();
		if (token == null) {
			error = "not connected";
			state = State.ERROR;
			return;
		}

		final int generation = ++searchGeneration;
		state = State.SEARCHING;
		error = null;
		opponent = null;
		searchStartMillis = System.currentTimeMillis();

		Thread thread = new Thread(() -> {
			try {
				QueueJoinResult result = BackendClient.joinQueue(token);
				while (!result.matched) {
					if (generation != searchGeneration) {
						return; // cancelled
					}
					Thread.sleep(POLL_INTERVAL_MS);
					if (generation != searchGeneration) {
						return;
					}
					result = BackendClient.joinQueue(token);
				}
				if (generation != searchGeneration) {
					return;
				}

				opponent = result.opponentUsername;
				state = State.LAUNCHING;
				Cues.matchFound();
				SpeedrunMcAlt.LOGGER.info(
						"[speedrunmcalt] Matched vs {} - matchId={} type={} structure={},{} "
								+ "bastion={}@{},{} overworldSeed={} netherSeed={}",
						result.opponentUsername, result.matchId, result.seedType,
						result.structureX, result.structureZ, result.bastionType,
						result.bastionX, result.bastionZ,
						result.overworldSeed, result.netherSeed);

				// Nothing client-side happens on this thread. The tick
				// handler picks it up from here.
				pendingToken = token;
				pending = result;
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				if (generation == searchGeneration) {
					error = e.getMessage();
					state = State.ERROR;
					SpeedrunMcAlt.LOGGER.error("[speedrunmcalt] Matchmaking failed", e);
				}
			}
		}, "speedrunmcalt-matchmaker");
		thread.setDaemon(true);
		thread.start();
	}
}
